#!/usr/bin/env python3
"""Builds NavMap "roads" messages: OpenStreetMap road lines around a point, packed for the watch.

The watch draws these lines itself, so zooming and moving need no new download. This is the reference
for NavBridge (same input tiles, same output) and a way to feed the Connect IQ simulator:

  tools/roads.py build <lat> <lon> [--size-m 2400] [--json out.json] [--lines in.json]
                                    print stats; save the messages and/or the decoded tile lines (test fixtures)
  tools/roads.py send <lat> <lon> [--size-m 2400]                       send them through a running sim-phone.py

Message (phone -> watch), one per chunk:
  {"t":"roads", "id":area, "c":chunk, "n":chunks, "o":[ox, oy, size], "p":[point, ...]}
  ox, oy  top-left corner of the square, in z18 Web Mercator pixels (256-pixel tiles, so the world is 2^26 wide)
  size    side of the square in z18 pixels (at most 8191; 2400 m at 13 deg N is about 4126)
  point   one integer: bit 29 = first point of a line, bits 26-28 = road class (on the first point),
          bits 13-25 = y - oy, bits 0-12 = x - ox (0..size, lines are clipped exactly to the square)
  class   1 minor, 2 tertiary, 3 secondary, 4 primary, 5 trunk, 6 motorway (0 service and 7 are not sent)
Chunks hold at most 500 points, nearest lines first, and a line is never split across chunks.
At most 30 chunks; lines past that (the farthest) are left out. The area id is
((ox * 31 + oy) ^ size) & 0x3FFFFFFF, so the same square always has the same id.

Steps, in this order (NavBridge does the same, so its output matches bit for bit for the same tiles):
  1. Decode the z14 tiles that touch the square, x-major then y, features in file order.
  2. Clip each line to its own tile's 4096 square (tiles carry a buffer past their edges), round half to even.
  3. Clip to the square (Liang-Barsky), relative to its corner, and round half to even again.
  4. Merge lines of the same class that meet end to end: classes in ascending order, lines in decoding
     order; each line grows forward, then backward, taking the first unused line at its end point.
  5. Douglas-Peucker with a tolerance of 2 z18 pixels.
  6. Split lines longer than 500 points, stable-sort by the nearest point's distance to the centre,
     fill chunks of at most 500 points in that order, and keep the first 30 chunks.

Data: OpenFreeMap vector tiles (OpenMapTiles schema, layer "transportation", z14), no key needed.
(c) OpenStreetMap contributors. Needs only Python 3.
"""
import argparse
import gzip
import json
import math
import os
import socket
import struct
import sys
import urllib.request

TILEJSON = "https://tiles.openfreemap.org/planet"
CLASSES = {"minor": 1, "tertiary": 2, "secondary": 3, "primary": 4, "trunk": 5, "motorway": 6}
WORLD = 1 << 26            # z18 world size in pixels
TILE_Z = 14
TILE_PX = WORLD >> TILE_Z  # 4096 z18 pixels per z14 tile
SIMPLIFY = 2.0             # Douglas-Peucker tolerance in z18 pixels (about 1.2 m)
CHUNK_POINTS = 500
MAX_CHUNKS = 30            # the watch rejects n > 30 (one bit per chunk)
CACHE = os.path.expanduser("~/.cache/navmap-roads")
UA = {"User-Agent": "NavMap-roads/0.1 (+https://github.com/Cube-VG/navmap-watch)"}


# ---- tiles -----------------------------------------------------------------

def fetch(url, path):
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
            data = r.read()
        with open(path + ".part", "wb") as f:
            f.write(data)
        os.replace(path + ".part", path)
    with open(path, "rb") as f:
        return f.read()


def tile_url():
    meta = json.loads(fetch(TILEJSON, os.path.join(CACHE, "planet.json")))
    return meta["tiles"][0]


def varint(b, i):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        s += 7
        if c < 0x80:
            return r, i


def fields(b):
    i = 0
    while i < len(b):
        k, i = varint(b, i)
        f, t = k >> 3, k & 7
        if t == 0:
            v, i = varint(b, i)
        elif t == 2:
            n, i = varint(b, i)
            v = b[i:i + n]
            i += n
        elif t == 5:
            v, i = b[i:i + 4], i + 4
        elif t == 1:
            v, i = b[i:i + 8], i + 8
        else:
            raise ValueError("protobuf wire type %d" % t)
        yield f, v


def packed(b):
    out, i = [], 0
    while i < len(b):
        v, i = varint(b, i)
        out.append(v)
    return out


def value(b):
    for f, v in fields(b):
        if f == 1:
            return v.decode()
        if f == 2:
            return struct.unpack("<f", v)[0]
        if f == 3:
            return struct.unpack("<d", v)[0]
        if f in (4, 5):
            return v
        if f == 6:
            return (v >> 1) ^ -(v & 1)
        if f == 7:
            return bool(v)
    return None


def tile_roads(tx, ty, url):
    """Road lines of one z14 tile as (class, [(x, y), ...]) in z18 world pixels."""
    path = os.path.join(CACHE, url.split("/planet/")[1].split("/")[0], "%d_%d_%d.pbf" % (TILE_Z, tx, ty))
    b = fetch(url.format(z=TILE_Z, x=tx, y=ty), path)
    if b[:2] == b"\x1f\x8b":
        b = gzip.decompress(b)
    out = []
    for f, layer in fields(b):
        if f != 3:
            continue
        name, feats, keys, vals, extent = None, [], [], [], 4096
        for lf, lv in fields(layer):
            if lf == 1:
                name = lv.decode()
            elif lf == 2:
                feats.append(lv)
            elif lf == 3:
                keys.append(lv.decode())
            elif lf == 4:
                vals.append(value(lv))
            elif lf == 5:
                extent = lv
        if name != "transportation":
            continue
        for fb in feats:
            tags, gtype, geom = [], 0, []
            for ff, fv in fields(fb):
                if ff == 2:
                    tags = packed(fv)
                elif ff == 3:
                    gtype = fv
                elif ff == 4:
                    geom = packed(fv)
            if gtype != 2:
                continue
            props = {keys[tags[i]]: vals[tags[i + 1]] for i in range(0, len(tags) - 1, 2)}
            cls = CLASSES.get(props.get("class"))
            if cls is None:
                continue
            k = TILE_PX / extent
            cx = cy = i = 0
            cur = None
            while i < len(geom):
                cmd, cnt = geom[i] & 7, geom[i] >> 3
                i += 1
                for _ in range(cnt):
                    if cmd == 7:
                        continue
                    dx, dy = geom[i], geom[i + 1]
                    i += 2
                    cx += (dx >> 1) ^ -(dx & 1)
                    cy += (dy >> 1) ^ -(dy & 1)
                    p = (tx * TILE_PX + cx * k, ty * TILE_PX + cy * k)
                    if cmd == 1:
                        cur = [p]
                        out.append((cls, cur))
                    else:
                        cur.append(p)
    return out


# ---- geometry ----------------------------------------------------------------

def world18(lat, lon):
    s = math.sin(math.radians(lat))
    return (lon + 180.0) / 360.0 * WORLD, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * WORLD


def metres_per_px(lat):
    return 40075016.686 * math.cos(math.radians(lat)) / WORLD


def clip_segment(a, b, size):
    """Liang-Barsky against [0, size] squared; returns the clipped segment or None."""
    (x0, y0), (x1, y1) = a, b
    dx, dy = x1 - x0, y1 - y0
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, x0), (dx, size - x0), (-dy, y0), (dy, size - y0)):
        if p == 0:
            if q < 0:
                return None
        else:
            t = q / p
            if p < 0:
                t0 = max(t0, t)
            else:
                t1 = min(t1, t)
    if t0 > t1:
        return None
    return (x0 + t0 * dx, y0 + t0 * dy), (x0 + t1 * dx, y0 + t1 * dy)


def clip(pts, size):
    runs, cur = [], []
    for a, b in zip(pts, pts[1:]):
        seg = clip_segment(a, b, size)
        if seg is None:
            if cur:
                runs.append(cur)
                cur = []
            continue
        if not cur or cur[-1] != seg[0]:
            if cur:
                runs.append(cur)
            cur = [seg[0]]
        cur.append(seg[1])
        if seg[1] != b:            # left the square
            runs.append(cur)
            cur = []
    if cur:
        runs.append(cur)
    out = []
    for r in runs:
        ints = []
        for x, y in r:
            p = (min(size, max(0, int(round(x)))), min(size, max(0, int(round(y)))))
            if not ints or ints[-1] != p:
                ints.append(p)
        if len(ints) >= 2:
            out.append(ints)
    return out


def merge(lines):
    """Joins lines of the same class that meet end to end, so shared points are sent once."""
    out = []
    by_class = {}
    for cls, pts in lines:
        by_class.setdefault(cls, []).append(pts)
    for cls, group in sorted(by_class.items()):
        ends = {}
        for i, pts in enumerate(group):
            ends.setdefault(pts[0], []).append(i)
            ends.setdefault(pts[-1], []).append(i)
        used = [False] * len(group)

        def take(p, skip):
            for j in ends.get(p, ()):
                if not used[j] and j != skip:
                    return j
            return None

        for i, pts in enumerate(group):
            if used[i]:
                continue
            used[i] = True
            line = list(pts)
            for forward in (True, False):
                while True:
                    end = line[-1] if forward else line[0]
                    j = take(end, i)
                    if j is None:
                        break
                    used[j] = True
                    other = group[j] if group[j][0] == end else group[j][::-1]
                    if forward:
                        line += other[1:]
                    else:
                        line = other[::-1][:-1] + line
                    if line[0] == line[-1]:
                        break
            out.append((cls, line))
    return out


def simplify(pts, tol):
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = pts[a], pts[b]
        dx, dy = bx - ax, by - ay
        # sqrt of an exact integer sum is correctly rounded everywhere (math.hypot only promises < 1 ulp),
        # so the tolerance test comes out the same in NavBridge's Kotlin.
        length = math.sqrt(dx * dx + dy * dy)
        best, bi = -1.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            d = abs(dy * (px - ax) - dx * (py - ay)) / length if length else math.sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
            if d > best:
                best, bi = d, i
        if best > tol:
            keep[bi] = True
            stack += [(a, bi), (bi, b)]
    return [p for p, k in zip(pts, keep) if k]


# ---- messages ----------------------------------------------------------------

def build(lat, lon, size_m=2400.0, area_id=None, decoded=None):
    """Returns (messages, stats) for the square of side size_m centred on (lat, lon).
    If decoded is a list, the tile lines as decoded are appended to it (for test fixtures)."""
    cx, cy = world18(lat, lon)
    size = int(round(size_m / metres_per_px(lat)))
    if size > 8191:
        raise ValueError("square too large for 13-bit coordinates")
    ox, oy = int(round(cx)) - size // 2, int(round(cy)) - size // 2
    url = tile_url()
    lines = []
    for tx in range(ox // TILE_PX, (ox + size) // TILE_PX + 1):
        for ty in range(oy // TILE_PX, (oy + size) // TILE_PX + 1):
            for cls, pts in tile_roads(tx, ty, url):
                if decoded is not None:
                    decoded.append({"tile": [tx, ty], "class": cls, "points": [[x, y] for x, y in pts]})
                bx, by = tx * TILE_PX, ty * TILE_PX
                for part in clip([(x - bx, y - by) for x, y in pts], TILE_PX):
                    rel = [(x + bx - ox, y + by - oy) for x, y in part]
                    lines += [(cls, run) for run in clip(rel, size)]
    lines = [(cls, simplify(pts, SIMPLIFY)) for cls, pts in merge(lines)]
    # Split lines too long for one chunk; the halves share a point.
    split = []
    for cls, pts in lines:
        while len(pts) > CHUNK_POINTS:
            split.append((cls, pts[:CHUNK_POINTS]))
            pts = pts[CHUNK_POINTS - 1:]
        split.append((cls, pts))
    half = size / 2.0
    split.sort(key=lambda l: min((x - half) ** 2 + (y - half) ** 2 for x, y in l[1]))
    chunks, cur = [], []
    for cls, pts in split:
        if cur and len(cur) + len(pts) > CHUNK_POINTS:
            chunks.append(cur)
            cur = []
        for i, (x, y) in enumerate(pts):
            cur.append(((1 << 29) | (cls << 26) if i == 0 else 0) | (y << 13) | x)
    if cur:
        chunks.append(cur)
    del chunks[MAX_CHUNKS:]   # the watch takes at most 30; the farthest lines are left out
    if area_id is None:
        area_id = ((ox * 31 + oy) ^ size) & 0x3FFFFFFF
    msgs = [{"t": "roads", "id": area_id, "c": i, "n": len(chunks), "o": [ox, oy, size], "p": c}
            for i, c in enumerate(chunks)]
    npts = sum(len(c) for c in chunks)
    stats = {"lines": len(split), "points": npts, "chunks": len(chunks), "size_px": size,
             # Monkey C serialization: 5 bytes per number, plus the keys and small fields
             "bytes": sum(5 * len(m["p"]) + 60 for m in msgs)}
    return msgs, stats


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("build", "send"):
        s = sub.add_parser(name)
        s.add_argument("lat", type=float)
        s.add_argument("lon", type=float)
        s.add_argument("--size-m", type=float, default=2400.0, help="side of the square (default 2400 m)")
        if name == "build":
            s.add_argument("--json", help="write the messages to this file")
            s.add_argument("--lines", help="write the decoded tile lines (z18 pixels) to this file")
        else:
            s.add_argument("--port", type=int, default=7382, help="sim-phone.py control port")
    a = p.parse_args()
    decoded = [] if a.cmd == "build" and a.lines else None
    msgs, stats = build(a.lat, a.lon, a.size_m, decoded=decoded)
    print(json.dumps(stats))
    if a.cmd == "build" and a.json:
        with open(a.json, "w") as f:
            json.dump(msgs, f)
    if decoded is not None:
        with open(a.lines, "w") as f:
            json.dump({"lat": a.lat, "lon": a.lon, "size_m": a.size_m, "tile_url": tile_url(), "lines": decoded}, f)
    if a.cmd == "send":
        for m in msgs:
            with socket.create_connection(("127.0.0.1", a.port), timeout=10) as c:
                c.sendall((json.dumps(m) + "\n").encode())
                print("chunk %d/%d (%d points): %s" % (m["c"] + 1, m["n"], len(m["p"]), c.makefile("r").readline().strip()))


if __name__ == "__main__":
    sys.exit(main())
