#!/usr/bin/env python3
"""NavMap's compact /nav encodings (watch 0.6+), reference for NavBridge. Run it to self-test.

Garmin Connect re-serializes JSON for the watch: each number costs 5 bytes, text 1 byte per ASCII
character (tools/link_bytes.py). So everything bulky travels as text, with the characters
'?'..'~' (63..126) standing for 0..63, which the watch reads back by subtracting 63.

The watch asks for these by adding c=1 to its poll; without it NavBridge answers as 0.4 did.

  "n": the turn, one line instead of 7 fields: "dm|ah|rv|d|e|i"
       dm metres to the turn (-1 unknown), ah arrow hash, rv route version, d distance text,
       e arrival time, i instruction (last, so it may itself contain '|'). The "t":"nav" is implied.
  "A": the 48x48 arrow as runs. Blocks of identical rows, each: rows, runs, then x0 x1 per run
       (x1 exclusive), all +63. "" clears the arrow. Sent only when the poll's ah differs, as "a" was.
  "P": the route as a Google encoded polyline, precision 1e-5 (the same text PolyUtil.encode gives),
       after dropping points that move the line by less than 2 m. Sent only when the poll's rv differs.
"""
import math

ZERO = 63


def nav_line(dm, ah, rv, d, e, i):
    return f"{dm}|{ah}|{rv}|{d}|{e}|{i}"


def nav_parse(s):
    dm, ah, rv, d, e, i = s.split("|", 5)
    return int(dm), int(ah), int(rv), d, e, i


# ---- arrow ----------------------------------------------------------------------------------------

def _row_runs(w0, w1):
    """The filled runs of one row, from the 0.4 packing: int 0 bit 31 = x0, int 1 bit 15 = x32."""
    runs, start = [], -1
    for x in range(49):
        on = x < 32 and (w0 >> (31 - x)) & 1 or 32 <= x < 48 and (w1 >> (47 - x)) & 1
        if on and start < 0:
            start = x
        elif not on and start >= 0:
            runs.append((start, x))
            start = -1
    return runs


def arrow_encode(bits):
    """96 ints (0.4 "a") -> "A" text."""
    rows = [_row_runs(bits[r * 2] & 0xFFFFFFFF, bits[r * 2 + 1]) for r in range(48)]
    if not any(rows):
        return ""
    out, r = [], 0
    while r < 48:
        n = 1
        while r + n < 48 and rows[r + n] == rows[r]:
            n += 1
        out += [n, len(rows[r])] + [v for run in rows[r] for v in run]
        r += n
    return "".join(chr(ZERO + v) for v in out)


def arrow_rects(s):
    """What the watch makes of "A": [x, y, w, h, ...] at 1.5x, as NavModel.buildArrowRuns does."""
    b = [ord(c) - ZERO for c in s]
    rects, i, row = [], 0, 0
    while i + 1 < len(b):
        y0 = row * 3 // 2
        row += b[i]
        y1 = row * 3 // 2
        runs = b[i + 1]
        i += 2
        for _ in range(runs):
            x0, x1 = b[i] * 3 // 2, b[i + 1] * 3 // 2
            rects += [x0, y0, x1 - x0, y1 - y0]
            i += 2
    return rects


# ---- route ----------------------------------------------------------------------------------------

def route_encode(flat):
    """[lat*1e5, lon*1e5, ...] -> Google encoded polyline text."""
    out, plat, plon = [], 0, 0
    for k in range(0, len(flat) - 1, 2):
        for v, prev in ((flat[k], plat), (flat[k + 1], plon)):
            d = v - prev
            z = ~(d << 1) if d < 0 else d << 1
            while z >= 0x20:
                out.append(chr((0x20 | (z & 0x1F)) + ZERO))
                z >>= 5
            out.append(chr(z + ZERO))
        plat, plon = flat[k], flat[k + 1]
    return "".join(out)


def route_decode(s):
    out, acc, v, shift = [], [0, 0], 0, 0
    for c in s:
        b = ord(c) - ZERO
        v |= (b & 0x1F) << shift
        shift += 5
        if b < 0x20:
            k = len(out) & 1
            acc[k] += ~(v >> 1) if v & 1 else v >> 1
            out.append(acc[k])
            v = shift = 0
    return out


def simplify(flat, tol_m=2.0):
    """Douglas-Peucker: drop points that move the line by less than tol_m metres."""
    n = len(flat) // 2
    if n < 3:
        return list(flat)
    kx = 1.11320 * math.cos(math.radians(flat[0] / 1e5))   # metres per 1e-5 degree
    pts = [(flat[2 * i + 1] * kx, flat[2 * i] * 1.11320) for i in range(n)]
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = pts[a], pts[b]
        dx, dy = bx - ax, by - ay
        ll = dx * dx + dy * dy
        best, far = -1.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            if ll == 0:
                dist = math.hypot(px - ax, py - ay)
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / ll))
                dist = math.hypot(px - ax - t * dx, py - ay - t * dy)
            if dist > best:
                best, far = dist, i
        if best > tol_m:
            keep[far] = True
            stack += [(a, far), (far, b)]
    return [v for i in range(n) if keep[i] for v in (flat[2 * i], flat[2 * i + 1])]


def _self_test():
    import importlib.util
    import os
    import random
    spec = importlib.util.spec_from_file_location("sp", os.path.join(os.path.dirname(__file__), "sim-phone.py"))
    sp = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sp)

    def old_rects(bits):   # NavModel.buildArrow, the 0.5 decoder
        rects = []
        for row in range(48):
            for x0, x1 in _row_runs(bits[row * 2] & 0xFFFFFFFF, bits[row * 2 + 1]):
                rects.append((x0 * 3 // 2, row * 3 // 2, x1 * 3 // 2, (row + 1) * 3 // 2))
        return rects

    def cover(rects):
        return {(x, y) for x0, y0, x1, y1 in rects for x in range(x0, x1) for y in range(y0, y1)}

    for name in ("STRAIGHT", "RIGHT", "LEFT", "UTURN", "ARRIVE"):
        bits = sp.arrow_bits(name)
        s = arrow_encode(bits)
        assert all(63 <= ord(c) <= 126 for c in s), name
        new = arrow_rects(s)
        new = [(new[i], new[i + 1], new[i] + new[i + 2], new[i + 1] + new[i + 3]) for i in range(0, len(new), 4)]
        assert cover(new) == cover(old_rects(bits)), name
        print(f"arrow {name:8} {len(s):3} chars, {len(new):2} rects (was {len(old_rects(bits))})")
    assert arrow_encode([0] * 96) == ""
    random.seed(7)
    flat = []
    la, lo = 3885591, -9479964
    for _ in range(500):
        la += random.randint(-400, 400)
        lo += random.randint(-400, 400)
        flat += [la, lo]
    assert route_decode(route_encode(flat)) == flat
    assert route_encode([3850000, -12020000, 4070000, -12095000, 4325200, -12645300]) == "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    assert nav_parse(nav_line(-1, 5, 7, "", "", "Exit | left")) == (-1, 5, 7, "", "", "Exit | left")
    print("navcodec self-test ok")


if __name__ == "__main__":
    _self_test()
