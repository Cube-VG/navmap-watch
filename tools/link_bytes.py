#!/usr/bin/env python3
"""What NavMap really costs over Bluetooth, worked out the way Garmin Connect (Android 5.30) does it.

How a reply reaches the watch, read from the Garmin Connect app (com.garmin.android.apps.connectmobile):
  * JSON (makeWebRequest): the phone parses the JSON and re-serializes it in Monkey C's own format
    (sim-phone.py encode(): every number 5 bytes, every distinct string once in a string table).
    If the watch asks for it, that is then LZW-compressed with 9-bit codes, kept only if smaller.
  * Pictures (makeImageRequest): Garmin's cloud (api.gcs.garmin.com/image-service) or, failing that,
    the phone fetches the picture, maps every pixel to the nearest palette colour, packs 1/2/4/8 bits
    per pixel (palette size + 1 for transparent: 2 -> 1 bit, 4 -> 2, 16 -> 4) and LZW-compresses it
    with 10-bit codes.

lzw() is that compressor (class aj2.a), so sizes here are what the Bluetooth link carries.

  python3 tools/link_bytes.py msg              # the /nav answers, old and new encodings
  python3 tools/link_bytes.py map [lat lon]    # map pictures with different palettes and styles
"""
import importlib.util
import io
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def _load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def lzw(data, window):
    """Garmin's LZW: alphabet = the byte values present, variable code width from just enough for the
    alphabet up to `window` bits, dictionary reset when full, codes packed least significant bit first."""
    used = [False] * 256
    for b in data:
        used[b] = True
    n_used = sum(used)
    if n_used < 255 and ((n_used + 3) & (n_used + 2)) != 0:
        used[max(i for i in range(256) if not used[i])] = True
    syms = [i for i in range(256) if used[i]]
    code_of = {s: i for i, s in enumerate(syms)}
    n = len(syms)
    min_bits = (n + 2).bit_length()
    max_bits = max(window, min_bits)
    out = bytearray([0x10, max_bits & 255, n & 255])
    if n < 256:
        out += bytes(syms)
    acc = nacc = 0

    def emit(code, bits):
        nonlocal acc, nacc
        acc |= code << nacc
        nacc += bits
        while nacc >= 8:
            out.append(acc & 255)
            acc >>= 8
            nacc -= 8

    table = {}
    next_code = n + 1
    bits = min_bits
    limit = (1 << bits) - 1
    prefix = -1
    for b in data:
        s = code_of[b]
        if prefix < 0:
            prefix = s
            continue
        code = table.get((prefix, s))
        if code is not None:
            prefix = code
            continue
        table[(prefix, s)] = next_code
        next_code += 1
        emit(prefix, bits)
        prefix = s
        if next_code == limit:
            if bits == max_bits:
                table.clear()
                next_code = n + 1
                bits = min_bits
            else:
                bits += 1
            limit = (1 << bits) - 1
    if prefix >= 0:
        emit(prefix, bits)
    emit(n, bits + 1 if next_code == limit - 1 else bits)
    if nacc:
        out.append(acc & 255)
    return bytes(out)


def over_air(obj):
    """(serialized, sent) bytes for one JSON reply: sent is the LZW form when that is smaller."""
    raw = _load("simphone", "sim-phone.py").encode(obj)
    return len(raw), min(len(raw), len(lzw(raw, 9)))


def pack_image(img, palette):
    """Nearest palette colour per pixel, packed like the phone does (transparent counts as a colour)."""
    from PIL import Image
    colours = len(palette) + 1
    bpp = 1 if colours <= 2 else 2 if colours <= 4 else 4 if colours <= 16 else 8
    pal = Image.new("P", (1, 1))
    flat = []
    for c in palette:
        flat += [(c >> 16) & 255, (c >> 8) & 255, c & 255]
    pal.putpalette(flat + flat[:3] * (256 - len(palette)))
    q = img.convert("RGB").quantize(palette=pal, dither=Image.Dither.NONE)
    px = q.tobytes()
    w, h = q.size
    out = bytearray()
    per = 8 // bpp
    for y in range(h):
        row = px[y * w:(y + 1) * w]
        for x in range(0, w, per):
            v = 0
            for k in range(per):
                v = (v << bpp) | ((row[x + k] + 1) if x + k < w else 0)   # index 0 = transparent
            out.append(v)
    return q, bpp, bytes(out)


# ---- /nav answers ---------------------------------------------------------------------------------

def msg_report():
    sp = _load("simphone", "sim-phone.py")
    codec = _load("navcodec", "navcodec.py")
    lat, lon = sp.Phone.DEFAULT_START
    route = []
    # A real-looking route: 600 points, small steps with turns, like a 6 km city ride.
    import math
    import random
    random.seed(1)
    la, lo, head = lat, lon, 0.0
    for i in range(600):
        head += random.gauss(0, 0.15) + (math.pi / 2 if i % 120 == 60 else 0)
        la += 9e-5 * math.cos(head)
        lo += 9e-5 * math.sin(head) / math.cos(math.radians(lat))
        route += [round(la * 1e5), round(lo * 1e5)]
    nav = {"t": "nav", "i": "Turn right onto W Santa Fe St", "d": "350 m", "dm": 350, "e": "12:41",
           "ah": 123456789, "rv": 987654321}
    rows = []
    for name in ("RIGHT", "UTURN", "ARRIVE"):
        bits = sp.arrow_bits(name)
        rows.append((f"arrow {name.lower()}: 96 numbers", {"a": bits}))
        rows.append((f"arrow {name.lower()}: runs text", {"A": codec.arrow_encode(bits)}))
    rows.append(("route 600 points: numbers", {"p": route}))
    rows.append(("route 600 points: delta text", {"P": codec.route_encode(route)}))
    simple = codec.simplify(route, 2)
    rows.append((f"route simplified to {len(simple) // 2} points: delta text", {"P": codec.route_encode(simple)}))
    rows.append(("poll answer, while navigating", nav))
    rows.append(("poll answer, while navigating: one line", {"n": codec.nav_line(
        nav["dm"], nav["ah"], nav["rv"], nav["d"], nav["e"], nav["i"])}))
    rows.append(("poll answer, idle", {"t": "idle"}))
    print(f"{'what':52} {'serialized':>10} {'over air':>9}")
    for label, obj in rows:
        s, a = over_air(obj)
        print(f"{label:52} {s:>10} {a:>9}")


# ---- map pictures ---------------------------------------------------------------------------------

def map_report(lat, lon):
    from PIL import Image
    key = None
    for line in open(os.path.expanduser("~/.config/navmap/secrets.properties")):
        if line.startswith("MAPS_API_KEY="):
            key = line.split("=", 1)[1].strip()
    styles = {k: v for k, v in _styles().items()}
    out_dir = os.path.join(HERE, "..", "dist", "map-bytes")
    os.makedirs(out_dir, exist_ok=True)
    print(f"{'variant':34} {'px':>4} {'bpp':>3} {'packed':>7} {'over air':>8}")
    for name, (style, palette, size) in styles.items():
        url = ("https://maps.googleapis.com/maps/api/staticmap?center=%.6f,%.6f&zoom=16&size=%dx%d&scale=2"
               "&maptype=roadmap&%s&key=%s" % (lat, lon, size, size, style, key))
        png = urllib.request.urlopen(url, timeout=30).read()
        img = Image.open(io.BytesIO(png))
        q, bpp, packed = pack_image(img, palette)
        sent = lzw(packed, 10)
        q.convert("RGB").save(os.path.join(out_dir, f"{name}.png"))
        print(f"{name:34} {img.size[0]:>4} {bpp:>3} {len(packed):>7} {len(sent):>8}")


def _styles():
    s = lambda *parts: "&".join("style=" + p.replace("|", "%7C") for p in parts)
    base = ["element:geometry|color:0xf7f7f5",
            "feature:poi|visibility:off", "feature:transit|visibility:off",
            "feature:administrative|element:geometry|visibility:off",
            "feature:road|element:labels.icons|visibility:off"]
    v05 = base + ["element:labels.text.fill|color:0x111827",
                  "element:labels.text.stroke|color:0xffffff|weight:4",
                  "feature:road|element:geometry|color:0x9ca3af",
                  "feature:road.arterial|element:geometry|color:0x4b5563",
                  "feature:road.highway|element:geometry.fill|color:0xf2a33a",
                  "feature:road.highway|element:geometry.stroke|color:0xb36b10",
                  "feature:water|element:geometry|color:0x8fc1ee",
                  "feature:landscape.man_made|element:geometry|color:0xecece8"]
    pal05 = [0xffffff, 0xf7f7f5, 0xecece8, 0xd1d5db, 0x9ca3af, 0x6b7280, 0x4b5563, 0x2d3440,
             0x111827, 0xf2a33a, 0xb36b10, 0xf8d29c, 0x8fc1ee, 0xc4def5, 0x000000]
    # Three greys: ground, minor roads and water mid grey, main roads dark. Anti-aliased edges fall
    # on the mid grey, which reads as smoothing; labels dark with a ground-coloured halo.
    flat3 = base + ["feature:landscape|element:geometry|color:0xf7f7f5",
                    "feature:road|element:geometry|color:0x9ca3af",
                    "feature:road.arterial|element:geometry|color:0x4b5563",
                    "feature:road.highway|element:geometry|color:0x4b5563",
                    "feature:water|element:geometry|color:0x9ca3af",
                    "element:labels.text.fill|color:0x4b5563",
                    "element:labels.text.stroke|color:0xf7f7f5|weight:4"]
    pal3 = [0xf7f7f5, 0x9ca3af, 0x4b5563]
    nolabels = ["element:labels|visibility:off"]
    return {
        "v0.5 (15 colours, labels)": (s(*v05), pal05, 280),
        "v0.5 style, no labels": (s(*(v05 + nolabels)), pal05, 280),
        "3 colours, labels": (s(*flat3), pal3, 280),
        "3 colours, no labels": (s(*(flat3 + nolabels)), pal3, 280),
        "3 colours, no labels, 240": (s(*(flat3 + nolabels)), pal3, 240),
    }


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "map":
        sp = _load("simphone", "sim-phone.py")
        lat, lon = (float(sys.argv[2]), float(sys.argv[3])) if len(sys.argv) > 3 else sp.Phone.DEFAULT_START
        map_report(lat, lon)
    else:
        msg_report()
