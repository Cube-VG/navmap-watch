#!/usr/bin/env python3
"""Writes what tools/navcodec.py makes of a set of arrows, routes and turns, for NavBridge's NavCodecTest,
which checks its Kotlin port gives the same text. Run it again whenever navcodec.py changes:

    python3 tools/navcodec-vectors.py
"""
import importlib.util
import json
import math
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "android", "core", "src", "test", "resources", "navcodec", "vectors.json")
sys.path.insert(0, HERE)
import navcodec  # noqa: E402


def _sim_phone():
    spec = importlib.util.spec_from_file_location("sim_phone", os.path.join(HERE, "sim-phone.py"))
    sp = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sp)
    return sp


def _signed(v):
    return v - (1 << 32) if v >= 1 << 31 else v


def arrows():
    sp = _sim_phone()
    out = [(name, sp.arrow_bits(name)) for name in ("STRAIGHT", "RIGHT", "LEFT", "UTURN", "ARRIVE")]
    out.append(("empty", [0] * 96))
    out.append(("full", [-1, 0xFFFF] * 48))
    # Single pixels at the edges of both ints, and bits above 15 in int 1, which aren't pixels.
    edges = [0] * 96
    for row, (w0, w1) in enumerate([(1 << 31, 0), (1, 0), (0, 1 << 15), (0, 1), (1, 1 << 15), (0, 0x7FFF0000)]):
        edges[row * 2], edges[row * 2 + 1] = _signed(w0), w1
    out.append(("edges", edges))
    rng = random.Random(165)
    for k in range(3):
        bits = []
        for _ in range(48):
            bits += [_signed(rng.getrandbits(32)), rng.getrandbits(16)]
        # A stretch of identical rows, to make a block.
        bits[20:40] = bits[20:22] * 10
        out.append((f"random{k}", bits))
    return [{"name": name, "bits": bits, "A": navcodec.arrow_encode(bits)} for name, bits in out]


def _ride(seed, lat, lon, n=600):
    """Like tools/link_bytes.py: small steps with turns, like a city ride."""
    rng = random.Random(seed)
    la, lo, head, flat = lat, lon, 0.0, []
    for i in range(n):
        head += rng.gauss(0, 0.15) + (math.pi / 2 if i % 120 == 60 else 0)
        la += 9e-5 * math.cos(head)
        lo += 9e-5 * math.sin(head) / math.cos(math.radians(lat))
        flat += [round(la * 1e5), round(lo * 1e5)]
    return flat


def _walk():
    """navcodec's own self-test route."""
    random.seed(7)
    flat, la, lo = [], 3885591, -9479964
    for _ in range(500):
        la += random.randint(-400, 400)
        lo += random.randint(-400, 400)
        flat += [la, lo]
    return flat


def routes():
    out = [
        ("google", [3850000, -12020000, 4070000, -12095000, 4325200, -12645300]),
        ("walk", _walk()),
        ("ride-olathe", _ride(1, 38.85591, -94.79964)),
        ("ride-cubbon-park", _ride(2, 12.97633, 77.59292)),
        # Back where it started: the first span has no length.
        ("loop", [1297633, 7759292, 1297700, 7759400, 1297800, 7759300, 1297701, 7759200, 1297633, 7759292]),
        # Repeated points and points exactly on the line.
        ("repeats", [1297633, 7759292, 1297633, 7759292, 1297643, 7759302, 1297653, 7759312,
                     1297653, 7759312, 1297663, 7759322, 1297700, 7759300]),
        ("antimeridian", [-1000000, 17999999, -1000100, -17999999, 8999999, 0]),
        ("two", [1297633, 7759292, 1297700, 7759400]),
        ("one", [1297633, 7759292]),
        ("none", []),
    ]
    return [{"name": name, "flat": flat, "simple": (s := navcodec.simplify(flat, 2.0)),
             "P": navcodec.route_encode(s), "full": navcodec.route_encode(flat)} for name, flat in out]


def lines():
    out = [
        (150, 555, 42, "150 m", "Arrive 10:01", "Turn right onto Café Road"),
        (-1, -1234567, 0, "", "", "Exit | left"),
        (2300, 2147483647, 1073741823, "2.3 km", "12 min", ""),
    ]
    return [{"dm": dm, "ah": ah, "rv": rv, "d": d, "e": e, "i": i, "n": navcodec.nav_line(dm, ah, rv, d, e, i)}
            for dm, ah, rv, d, e, i in out]


def main():
    vectors = {"arrows": arrows(), "routes": routes(), "lines": lines()}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        f.write("{\n")
        groups = list(vectors.items())
        for g, (key, items) in enumerate(groups):
            f.write(f' "{key}": [\n')
            f.write(",\n".join("  " + json.dumps(v, separators=(",", ":")) for v in items))
            f.write("\n ]" + ("," if g + 1 < len(groups) else "") + "\n")
        f.write("}\n")
    print(f"wrote {os.path.relpath(OUT)}: " + ", ".join(f"{len(v)} {k}" for k, v in vectors.items()))


if __name__ == "__main__":
    main()
