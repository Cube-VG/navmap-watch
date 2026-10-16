#!/usr/bin/env python3
"""Plays the NavBridge phone app for NavMap running in the Connect IQ simulator.

NavMap reaches NavBridge two ways, and this script answers both:

- /nav on 127.0.0.1:18165, which NavMap asks every few seconds (NavBridge serves the same on the
  phone). The simulator runs the watch's web requests on this computer, so this needs nothing else.
- Connect IQ phone messages: the phone side listens on port 7381 and the simulator connects to it
  (adb Connection > Start). This script prints every message the watch app transmits and sends
  NavMap protocol messages back, in the same Monkey C serialization the Android SDK uses.

  tools/sim-phone.py serve [--ride]   listen; with --ride, start a demo ride when NavMap first asks
  tools/sim-phone.py send '<json>'    send one message through the running server
                                      ("a":"RIGHT" etc. expands to a drawn arrow)
  tools/sim-phone.py ride | stop      start or stop the demo ride through the running server
"""
import argparse
import datetime
import http.server
import json
import math
import os
import socket
import struct
import sys
import threading
import time
import urllib.parse
import zlib

import navcodec

STRING_SENTINEL = 0xABCDABCD
DATA_SENTINEL = 0xDA7ADA7A
SIM_PORT = 7381
CONTROL_PORT = 7382
NAV_PORT = 18165
SECRETS = os.path.expanduser("~/.config/navmap/secrets.properties")
END_SHOWN_S = 60  # NavBridge answers "end" this long after navigation ends, then "idle"


# ---- Monkey C serialization (see com.garmin.monkeybrains.serialization) ----

def encode(obj):
    """Serializes one object: optional string block, then the data block in breadth-first order."""
    offsets = {}
    strings = bytearray()

    def string_offset(s):
        if s not in offsets:
            offsets[s] = len(strings)
            b = s.encode("utf-8")
            strings.extend(struct.pack(">H", len(b) + 1) + b + b"\0")
        return offsets[s]

    data = bytearray()
    queue = [obj]
    while queue:
        o = queue.pop(0)
        if o is None:
            data += b"\x00"
        elif isinstance(o, bool):
            data += bytes([9, 1 if o else 0])
        elif isinstance(o, int):
            data += struct.pack(">bi", 1, o) if -2**31 <= o < 2**31 else struct.pack(">bq", 14, o)
        elif isinstance(o, float):
            f32 = struct.unpack(">f", struct.pack(">f", o))[0]
            data += struct.pack(">bf", 2, o) if abs(f32 - o) < 1e-5 else struct.pack(">bd", 15, o)
        elif isinstance(o, str):
            data += struct.pack(">bI", 3, string_offset(o))
        elif isinstance(o, (list, tuple)):
            data += struct.pack(">bi", 5, len(o))
            queue.extend(o)
        elif isinstance(o, dict):
            data += struct.pack(">bi", 11, len(o))
            for k, v in o.items():
                queue += [k, v]
        elif isinstance(o, (bytes, bytearray)):
            data += struct.pack(">bi", 20, len(o)) + bytes(o)
        else:
            raise TypeError(f"can't serialize {type(o).__name__}")
    out = bytearray()
    if strings:
        out += struct.pack(">II", STRING_SENTINEL, len(strings)) + strings
    out += struct.pack(">II", DATA_SENTINEL, len(data)) + data
    return bytes(out)


def _tokens(data, strings):
    i, out = 0, []
    while i < len(data):
        t = data[i]
        if t == 0:
            out.append(("v", None)); i += 1
        elif t == 1:
            out.append(("v", struct.unpack_from(">i", data, i + 1)[0])); i += 5
        elif t == 2:
            out.append(("v", struct.unpack_from(">f", data, i + 1)[0])); i += 5
        elif t == 3:
            off = struct.unpack_from(">I", data, i + 1)[0]
            out.append(("v", strings.get(off, f"<string@{off}>"))); i += 5
        elif t == 5:
            out.append(("a", struct.unpack_from(">i", data, i + 1)[0])); i += 5
        elif t == 9:
            out.append(("v", data[i + 1] > 0)); i += 2
        elif t == 11:
            out.append(("h", struct.unpack_from(">i", data, i + 1)[0])); i += 5
        elif t == 14:
            out.append(("v", struct.unpack_from(">q", data, i + 1)[0])); i += 9
        elif t == 15:
            out.append(("v", struct.unpack_from(">d", data, i + 1)[0])); i += 9
        elif t == 19:
            out.append(("v", chr(struct.unpack_from(">i", data, i + 1)[0]))); i += 5
        elif t == 20:
            n = struct.unpack_from(">i", data, i + 1)[0]
            out.append(("v", bytes(data[i + 5:i + 5 + n]))); i += 5 + n
        else:
            raise ValueError(f"unknown Monkey C type {t} at byte {i}")
    return out


def _decode_data(data, strings):
    toks = _tokens(data, strings)
    pos = 0

    def take():
        nonlocal pos
        kind, v = toks[pos]
        pos += 1
        if kind == "a":
            return [], v, True
        if kind == "h":
            return {}, v, True
        return v, 0, False

    roots = []
    while pos < len(toks):
        root, n, container = take()
        queue = [(root, n)] if container else []
        while queue:
            node, n = queue.pop(0)
            for _ in range(n):
                if isinstance(node, list):
                    child, cn, cc = take()
                    node.append(child)
                    if cc:
                        queue.append((child, cn))
                else:
                    k, kn, kc = take()
                    v, vn, vc = take()
                    node[json.dumps(k) if kc else k] = v
                    if kc:
                        queue.append((k, kn))
                    if vc:
                        queue.append((v, vn))
        roots.append(root)
    return roots[0] if len(roots) == 1 else roots


def decode_messages(buf):
    """Removes every complete message from the front of buf and returns (messages, skipped_bytes)."""
    msgs, skipped = [], bytearray()
    while len(buf) >= 8:
        head = struct.unpack_from(">I", buf, 0)[0]
        if head not in (STRING_SENTINEL, DATA_SENTINEL):
            skipped.append(buf.pop(0))
            continue
        strings, pos = {}, 0
        if head == STRING_SENTINEL:
            n = struct.unpack_from(">I", buf, 4)[0]
            if len(buf) < 8 + n + 8:
                break
            block = bytes(buf[8:8 + n])
            i = 0
            while i + 2 <= len(block):
                ln = struct.unpack_from(">H", block, i)[0]
                strings[i] = block[i + 2:i + 2 + ln - 1].decode("utf-8", "replace")
                i += 2 + ln
            pos = 8 + n
            if struct.unpack_from(">I", buf, pos)[0] != DATA_SENTINEL:
                skipped.append(buf.pop(0))
                continue
        n = struct.unpack_from(">I", buf, pos + 4)[0]
        if len(buf) < pos + 8 + n:
            break
        data = bytes(buf[pos + 8:pos + 8 + n])
        del buf[:pos + 8 + n]
        msgs.append(_decode_data(data, strings))
    return msgs, bytes(skipped)


# ---- turn arrows in NavMap's 48x48 bit format -------------------------------------

def _arrow_shapes():
    return {
        "STRAIGHT": [[(20, 44), (28, 44), (28, 20), (38, 20), (24, 4), (10, 20), (20, 20)]],
        "RIGHT": [[(12, 44), (20, 44), (20, 26), (32, 26), (32, 35), (45, 22), (32, 9), (32, 18), (12, 18)]],
        "LEFT": [[(36, 44), (28, 44), (28, 26), (16, 26), (16, 35), (3, 22), (16, 9), (16, 18), (36, 18)]],
        "UTURN": [[(30, 44), (38, 44), (38, 16), (24, 4), (10, 16), (10, 30), (3, 30), (14, 42), (25, 30),
                   (18, 30), (18, 18), (24, 12), (30, 18)]],
        "ARRIVE": None,
    }


def _in_polygon(x, y, poly):
    inside = False
    for (x0, y0), (x1, y1) in zip(poly, poly[-1:] + poly[:-1]):
        if (y0 > y) != (y1 > y) and x < x0 + (y - y0) * (x1 - x0) / (y1 - y0):
            inside = not inside
    return inside


def _in_ellipse(x, y, box):
    left, top, right, bottom = box
    rx, ry = (right - left) / 2, (bottom - top) / 2
    return ((x - left - rx) / rx) ** 2 + ((y - top - ry) / ry) ** 2 <= 1


def _filled(shapes, x, y):
    if shapes is None:  # destination pin: a ring on a downward point
        if _in_ellipse(x, y, (19, 11, 29, 21)):
            return False
        return _in_ellipse(x, y, (12, 4, 36, 28)) or _in_polygon(x, y, [(14, 22), (34, 22), (24, 44)])
    return any(_in_polygon(x, y, poly) for poly in shapes)


def arrow_bits(name):
    """Draws a maneuver arrow and packs it: 48 rows x 2 ints, int 0 bit 31 = x0, int 1 bit 15 = x32.
    Plain Python (no Pillow), so the script runs on any computer with Python 3."""
    shapes = _arrow_shapes()[name]
    bits = []
    for y in range(48):
        a = b = 0
        for x in range(48):
            if _filled(shapes, x + 0.5, y + 0.5):
                if x < 32:
                    a |= 1 << (31 - x)
                else:
                    b |= 1 << (47 - x)
        bits += [a - (1 << 32) if a >= 1 << 31 else a, b]
    return bits


def arrow_hash(bits):
    h = zlib.crc32(struct.pack(">96i", *bits)) & 0x7FFFFFFF
    return h or 1


# ---- the fake phone ------------------------------------------------------------------

def read_secret(wanted):
    """A value from secrets.properties, or None."""
    try:
        with open(SECRETS, encoding="utf-8") as f:
            for line in f:
                name, _, value = line.partition("=")
                if name.strip() == wanted:
                    return value.strip() or None
    except OSError:
        pass
    return None


def well_formed(token):
    """The tokens NavBridge pairs with (WatchPairing.wellFormed): 16 to 64 letters and digits."""
    return 16 <= len(token) <= 64 and token.isascii() and token.isalnum()


def stamp():
    return datetime.datetime.now().strftime("%H:%M:%S")


def short(obj):
    if isinstance(obj, dict):
        return {k: (f"[{len(v)} ints]" if k in ("a", "p") and isinstance(v, list) and len(v) > 8
                    else "(the Google Maps key)" if k == "mk" else v)
                for k, v in obj.items()}
    return obj


def log(*parts):
    print(stamp(), *parts, flush=True)


def offset(lat, lon, north_m, east_m):
    return lat + north_m / 111320.0, lon + east_m / (111320.0 * math.cos(math.radians(lat)))


def dist_text(m):
    if m >= 1000:
        return f"{m / 1000:.1f} km"
    if m >= 100:
        return f"{int(round(m / 10.0)) * 10} m"
    return f"{max(int(round(m / 5.0)) * 5, 0)} m"


class Phone:
    # Demo ride: (heading, length m, instruction for the turn at the end of the leg, arrow)
    LEGS = [
        ((1, 0), 300, "Turn right onto Test Street", "RIGHT"),
        ((0, 1), 450, "Turn left onto Sample Avenue", "LEFT"),
        ((1, 0), 400, "Turn right onto Example Lane", "RIGHT"),
        ((0, 1), 350, "Demo Café", "ARRIVE"),
    ]
    DEFAULT_START = (38.85591, -94.79964)  # used when the watch didn't send its position

    def __init__(self, ride_on_hello, speed, interval):
        self.ride_on_hello = ride_on_hello
        self.speed = speed
        self.interval = interval
        self.conn = None
        self.lock = threading.Lock()
        self.seq = 0
        self.watch_pos = None
        self.ride_thread = None
        self.ride_stop = threading.Event()
        self.resend_all = threading.Event()
        # What /nav answers: the latest turn, its arrow, the route and when navigation ended.
        self.nav = None
        self.arrow = None
        self.route = []
        self.route_text = None   # (route_version, polyline) so a route is encoded once
        self.route_version = 0
        self.ended = None  # (time, arrived)
        self.last_answer = None
        # Like NavBridge with pairing open: the first watch that asks is kept, then only that one gets answers.
        self.token = None
        self.refused = False
        self.watch_has_key = False
        # The Google Maps key, handed to a NavMap that hasn't got it, like NavBridge does (NavEndpoint.withMapsKey).
        self.maps_key = read_secret("MAPS_API_KEY")

    # -- link --
    def send(self, obj):
        self.remember(obj)
        with self.lock:
            conn = self.conn
            if conn is None:
                return False
            try:
                conn.sendall(encode(obj))
            except OSError as e:
                log("!! send failed:", e)
                return False
        log("->", json.dumps(short(obj), ensure_ascii=False))
        return True

    def on_watch(self, obj):
        log("<-", json.dumps(short(obj), ensure_ascii=False, default=str))
        if not isinstance(obj, dict):
            return
        if isinstance(obj.get("la"), int) and isinstance(obj.get("lo"), int):
            self.watch_pos = (obj["la"] / 1e5, obj["lo"] / 1e5)
        if obj.get("t") == "hello":
            if self.riding():
                self.resend_all.set()
            elif self.ride_on_hello:
                self.start_ride()
            else:
                self.send({"t": "idle"})

    # -- /nav --
    def remember(self, obj):
        if not isinstance(obj, dict):
            return
        with self.lock:
            t = obj.get("t")
            if t == "nav":
                self.nav = {k: v for k, v in obj.items() if k not in ("a", "s")}
                if isinstance(obj.get("a"), list):
                    self.arrow = obj["a"]
                self.ended = None
            elif t == "route" and isinstance(obj.get("p"), list):
                self.route = obj["p"]
                self.route_version += 1
            elif t == "end":
                self.nav = None
                self.ended = (time.monotonic(), bool(obj.get("ar")))
                if self.route:
                    self.route = []
                    self.route_version += 1
            elif t == "idle":
                self.nav = None

    def answer_nav(self, query):
        """The /nav answer for one request, as NavBridge 0.4 gives it."""
        def num(name):
            try:
                return int(query.get(name, [""])[0])
            except ValueError:
                return None
        la, lo = num("la"), num("lo")
        if la is not None and lo is not None:
            self.watch_pos = (la / 1e5, lo / 1e5)
        if self.ride_on_hello and not self.riding() and self.nav is None and self.ended is None:
            self.start_ride()
        with self.lock:
            if self.nav is not None and num("c") == 1:
                # Watch 0.6+: the compact form (tools/navcodec.py), about a tenth of the bytes.
                n = self.nav
                reply = {"n": navcodec.nav_line(n.get("dm", -1), n.get("ah", 0), self.route_version,
                                                n.get("d", ""), n.get("e", ""), n.get("i", ""))}
                if self.arrow is not None and num("ah") != n.get("ah"):
                    reply["A"] = navcodec.arrow_encode(self.arrow)
                if num("rv") != self.route_version:
                    if self.route_text is None or self.route_text[0] != self.route_version:
                        self.route_text = (self.route_version, navcodec.route_encode(navcodec.simplify(self.route)))
                    reply["P"] = self.route_text[1]
            elif self.nav is not None:
                reply = dict(self.nav, rv=self.route_version)
                if self.arrow is not None and num("ah") != self.nav.get("ah"):
                    reply["a"] = self.arrow
                if num("rv") != self.route_version:
                    reply["p"] = self.route
            elif self.ended is not None and time.monotonic() - self.ended[0] < END_SHOWN_S:
                reply = {"t": "end", "ar": self.ended[1]}
            else:
                reply = {"t": "idle"}
        # Every 2 s while navigating, so only log what changed.
        kind = (reply.get("t"), reply.get("i") or reply.get("n", "").split("|")[-1],
                "a" in reply or "A" in reply, "p" in reply or "P" in reply)
        if kind != self.last_answer:
            self.last_answer = kind
            log("/nav ->", json.dumps(short(reply), ensure_ascii=False), "" if query.get("k") else "(no key)")
        return reply

    def serve_nav(self, port):
        phone = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                url = urllib.parse.urlsplit(self.path)
                if url.path != "/nav":
                    self.send_error(404)
                    return
                query = urllib.parse.parse_qs(url.query)
                status, reply = 200, None
                given = query.get("k", [""])[0]
                with phone.lock:
                    if phone.token is None and well_formed(given):
                        phone.token = given
                        log("Paired with NavMap")
                    paired = phone.token is not None and given == phone.token
                if not paired:
                    status, reply = 403, {"t": "denied"}
                    if not phone.refused:
                        phone.refused = True
                        log("/nav -> 403, not the paired watch (said once)")
                else:
                    reply = phone.answer_nav(query)
                    tail = query.get("kf", [None])[0]
                    if tail is not None and phone.maps_key and tail != phone.maps_key[-6:]:
                        reply = dict(reply, mk=phone.maps_key)
                        log("/nav -> also the Google Maps key, which the watch hasn't got", f"(kf={tail})")
                    has_key = tail is not None and bool(phone.maps_key) and tail == phone.maps_key[-6:]
                    if has_key != phone.watch_has_key:
                        phone.watch_has_key = has_key
                        if has_key:
                            log("NavMap has the Google Maps key now (its kf matches)")
                body = json.dumps(reply).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        srv = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
        log(f"Answering /nav on http://127.0.0.1:{port}/nav", "" if self.maps_key else "(no MAPS_API_KEY in secrets.properties, so no map pictures)")
        srv.serve_forever()

    def serve_simulator(self, port):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", port))
        srv.listen(1)
        log(f"Waiting for the simulator on 127.0.0.1:{port} (simulator: adb Connection > Start)")
        while True:
            conn, _ = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            with self.lock:
                self.conn = conn
            log("Simulator connected")
            buf = bytearray()
            while True:
                try:
                    chunk = conn.recv(65536)
                except OSError:
                    chunk = b""
                if not chunk:
                    break
                buf += chunk
                try:
                    msgs, skipped = decode_messages(buf)
                except (ValueError, struct.error, IndexError) as e:
                    log("!! undecodable data:", e, bytes(buf[:64]).hex())
                    buf.clear()
                    continue
                if skipped:
                    log("?? skipped bytes:", skipped[:64].hex())
                for m in msgs:
                    self.on_watch(m)
            with self.lock:
                self.conn = None
            conn.close()
            log("Simulator disconnected")

    def serve_control(self, port):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", port))
        srv.listen(4)
        while True:
            c, _ = srv.accept()
            with c, c.makefile("r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        reply = self.control(json.loads(line))
                    except Exception as e:  # report anything back to the sender
                        reply = f"error: {e}"
                    c.sendall((reply + "\n").encode())

    def control(self, msg):
        cmd = msg.get("cmd") if isinstance(msg, dict) else None
        if cmd == "ride":
            self.start_ride()
            return "ride started"
        if cmd == "stop":
            self.ride_stop.set()
            return "ride stopping"
        if isinstance(msg, dict) and isinstance(msg.get("a"), str):
            bits = arrow_bits(msg["a"].upper())
            msg["a"] = bits
            msg.setdefault("ah", arrow_hash(bits))
        return "sent" if self.send(msg) else "kept for /nav (simulator's phone link not connected)"

    # -- demo ride --
    def riding(self):
        return self.ride_thread is not None and self.ride_thread.is_alive()

    def start_ride(self):
        if self.riding():
            return
        self.ride_stop.clear()
        self.ride_thread = threading.Thread(target=self.ride, daemon=True)
        self.ride_thread.start()

    def ride(self):
        lat, lon = self.watch_pos or self.DEFAULT_START
        corners = [(lat, lon)]
        for (dn, de), length, _, _ in self.LEGS:
            corners.append(offset(*corners[-1], dn * length, de * length))
        route = []
        for i in range(len(corners) - 1):
            (a_lat, a_lon), (b_lat, b_lon) = corners[i], corners[i + 1]
            for k in range(10):
                f = k / 10.0
                route += [round((a_lat + (b_lat - a_lat) * f) * 1e5), round((a_lon + (b_lon - a_lon) * f) * 1e5)]
        route += [round(corners[-1][0] * 1e5), round(corners[-1][1] * 1e5)]
        total = sum(leg[1] for leg in self.LEGS)
        log(f"Demo ride from {lat:.5f},{lon:.5f}: {total} m at {self.speed} m/s")

        sent_hash = None
        travelled = 0.0
        self.send({"t": "route", "p": route})
        for _, length, instr, arrow in self.LEGS:
            bits = arrow_bits(arrow)
            ah = arrow_hash(bits)
            left = float(length)
            while left > 0:
                if self.ride_stop.is_set():
                    log("Ride stopped")
                    self.send({"t": "end", "ar": False})
                    return
                if self.resend_all.is_set():
                    self.resend_all.clear()
                    sent_hash = None
                    self.send({"t": "route", "p": route})
                remaining = total - travelled
                eta = (datetime.datetime.now() + datetime.timedelta(seconds=remaining / self.speed)).strftime("%H:%M")
                self.seq += 1
                msg = {"t": "nav", "s": self.seq, "i": instr, "d": dist_text(left), "dm": int(left),
                       "e": f"Arrive {eta}", "ah": ah}
                if ah != sent_hash:
                    msg["a"] = bits
                if self.send(msg) and ah != sent_hash:
                    sent_hash = ah
                time.sleep(self.interval)
                step = self.speed * self.interval
                left -= step
                travelled += step
        self.seq += 1
        self.send({"t": "nav", "s": self.seq, "i": "Arriving at Demo Café", "d": "", "dm": -1, "e": "", "ah": sent_hash or 0})
        time.sleep(6)  # NavBridge's grace period before "end"
        self.send({"t": "end", "ar": True})
        log("Ride finished")


def control_send(obj, port):
    with socket.create_connection(("127.0.0.1", port), timeout=5) as c:
        c.sendall((json.dumps(obj) + "\n").encode())
        print(c.makefile("r").readline().strip())


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--ride", action="store_true", help="start the demo ride when NavMap first asks or says hello")
    s.add_argument("--speed", type=float, default=12.0, help="demo ride speed in m/s")
    s.add_argument("--interval", type=float, default=2.0, help="seconds between nav updates")
    s.add_argument("--port", type=int, default=SIM_PORT)
    s.add_argument("--nav-port", type=int, default=NAV_PORT)
    s.add_argument("--control-port", type=int, default=CONTROL_PORT)
    snd = sub.add_parser("send")
    snd.add_argument("json")
    snd.add_argument("--control-port", type=int, default=CONTROL_PORT)
    for name in ("ride", "stop"):
        sub.add_parser(name).add_argument("--control-port", type=int, default=CONTROL_PORT)
    args = p.parse_args()

    if args.cmd == "serve":
        phone = Phone(args.ride, args.speed, args.interval)
        threading.Thread(target=phone.serve_control, args=(args.control_port,), daemon=True).start()
        threading.Thread(target=phone.serve_nav, args=(args.nav_port,), daemon=True).start()
        try:
            phone.serve_simulator(args.port)
        except KeyboardInterrupt:
            pass
    elif args.cmd == "send":
        control_send(json.loads(args.json), args.control_port)
    else:
        control_send({"cmd": args.cmd}, args.control_port)


if __name__ == "__main__":
    main()
