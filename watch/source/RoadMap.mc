import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;

// Road lines from NavBridge ("roads" messages, format in tools/roads.py): OpenStreetMap roads in a square
// around us that the watch draws itself, so zooming and moving need no download.
const ROAD_CLASSES = 7;   // 0 service (not sent yet), 1 minor, 2 tertiary, 3 secondary, 4 primary, 5 trunk, 6 motorway
// For the near-white ground in sunlight: grey streets, darker main roads, orange highways.
const ROAD_COLOR = [0xc4c9d1, 0x9ca3af, 0x6b7280, 0x4b5563, 0x2d3440, 0xe8890c, 0xe8890c];
const ROAD_WIDTH = [3, 5, 6, 7, 8, 10, 10];   // pixels at zoom 16

// One square of roads, filled in chunk by chunk.
class RoadArea {
    var id as Number;
    var ox as Number;          // top-left corner, z18 Web Mercator pixels
    var oy as Number;
    var size as Number;        // side, z18 pixels
    var chunks as Number;
    var got as Number = 0;     // one bit per chunk received
    var gotCount as Number = 0;
    var points as Number = 0;
    var firstAt as Number;
    var lastAt as Number;
    // Per chunk: the coordinates [x0, y0, x1, y1, ...], and per road class its lines as
    // [start, end, minX, minY, maxX, maxY, ...], start and end (exclusive) indexing the coordinates.
    var xy as Array<Array<Number>?>;
    var lines as Array<Array<Array<Number>>?>;

    function initialize(id as Number, ox as Number, oy as Number, size as Number, chunks as Number) {
        self.id = id;
        self.ox = ox;
        self.oy = oy;
        self.size = size;
        self.chunks = chunks;
        xy = new [chunks] as Array<Array<Number>?>;
        lines = new [chunks] as Array<Array<Array<Number>>?>;
        firstAt = System.getTimer();
        lastAt = firstAt;
    }

    function complete() as Boolean {
        return gotCount >= chunks;
    }

    // Decodes one chunk; false for a repeat or a malformed one. Every array is allocated once at its
    // final size (counted in a first pass), because growing arrays point by point is slow on the watch.
    function add(c as Number, p as Array) as Boolean {
        if (c < 0 || c >= chunks || (got & (1 << c)) != 0) {
            return false;
        }
        var n = p.size();
        if (n == 0 || !(p[0] instanceof Number) || ((p[0] as Number) & 0x20000000) == 0) {
            return false;
        }
        var count = [0, 0, 0, 0, 0, 0, 0] as Array<Number>;
        for (var i = 0; i < n; i++) {
            var v = p[i];
            if (!(v instanceof Number)) {
                return false;
            }
            if ((v & 0x20000000) != 0) {
                var k = (v >> 26) & 7;
                if (k < ROAD_CLASSES) {
                    count[k] += 6;
                }
            }
        }
        var cxy = new [2 * n] as Array<Number>;
        var cl = new [ROAD_CLASSES] as Array<Array<Number>>;
        var fill = [0, 0, 0, 0, 0, 0, 0] as Array<Number>;
        for (var k = 0; k < ROAD_CLASSES; k++) {
            cl[k] = new [count[k]] as Array<Number>;
        }
        var cls = -1;
        var start = 0;
        var minX = 0;
        var minY = 0;
        var maxX = 0;
        var maxY = 0;
        var j = 0;
        for (var i = 0; i < n; i++) {
            var v = p[i] as Number;
            var x = v & 0x1FFF;
            var y = (v >> 13) & 0x1FFF;
            if ((v & 0x20000000) != 0) {
                if (cls >= 0 && cls < ROAD_CLASSES) {
                    var f = fill[cls];
                    var line = cl[cls];
                    line[f] = start;
                    line[f + 1] = j;
                    line[f + 2] = minX;
                    line[f + 3] = minY;
                    line[f + 4] = maxX;
                    line[f + 5] = maxY;
                    fill[cls] = f + 6;
                }
                cls = (v >> 26) & 7;
                start = j;
                minX = x;
                maxX = x;
                minY = y;
                maxY = y;
            } else {
                if (x < minX) { minX = x; } else if (x > maxX) { maxX = x; }
                if (y < minY) { minY = y; } else if (y > maxY) { maxY = y; }
            }
            cxy[j] = x;
            cxy[j + 1] = y;
            j += 2;
        }
        if (cls >= 0 && cls < ROAD_CLASSES) {
            var f = fill[cls];
            var line = cl[cls];
            line[f] = start;
            line[f + 1] = j;
            line[f + 2] = minX;
            line[f + 3] = minY;
            line[f + 4] = maxX;
            line[f + 5] = maxY;
        }
        xy[c] = cxy;
        lines[c] = cl;
        got |= 1 << c;
        gotCount++;
        points += n;
        lastAt = System.getTimer();
        return true;
    }
}

// The current square plus the next one while it arrives.
class RoadMap {
    private var _cur as RoadArea? = null;
    private var _next as RoadArea? = null;
    var chunksIn as Number = 0;

    function hasData() as Boolean {
        return _cur != null || _next != null;
    }

    function onRoads(data as Dictionary) as Void {
        var id = data["id"] as Object?;
        var c = data["c"] as Object?;
        var n = data["n"] as Object?;
        var o = data["o"] as Object?;
        var p = data["p"] as Object?;
        if (!(id instanceof Number) || !(c instanceof Number) || !(n instanceof Number) || n < 1 || n > 30 ||
                !(o instanceof Array) || o.size() < 3 || !(p instanceof Array)) {
            return;
        }
        var ox = o[0];
        var oy = o[1];
        var size = o[2];
        if (!(ox instanceof Number) || !(oy instanceof Number) || !(size instanceof Number) || size < 1 || size > 8191) {
            return;
        }
        var cur = _cur;
        var next = _next;
        var area = null;
        // The id comes from the square, so a resend is the same area; a different chunk count means the
        // phone rebuilt it from newer tiles, and that is started afresh.
        if (cur != null && cur.id == id && cur.chunks == n) {
            area = cur;
        } else if (next != null && next.id == id && next.chunks == n) {
            area = next;
        } else {
            // A newer square replaces one that was still arriving.
            next = null;
            _next = null;
            area = new RoadArea(id, ox, oy, size, n);
            _next = area;
        }
        // A chunk takes at most about 25 bytes a point once decoded; keep room for drawing and pictures
        // rather than run out of memory in a very dense square.
        if (System.getSystemStats().freeMemory < p.size() * 25 + 96 * 1024) {
            System.println("roads " + id + " " + (c + 1) + "/" + n + " dropped, low memory");
            return;
        }
        if (!area.add(c, p)) {
            return;
        }
        chunksIn++;
        System.println("roads " + id + " " + (c + 1) + "/" + n + " " + p.size() + "pts +" + (area.lastAt - area.firstAt) + "ms");
        if (area == _next && area.complete()) {
            _cur = area;
            _next = null;
        }
    }

    // For the debug panel: chunks of the current (or arriving) square, its points, and how long it took.
    function status() as String {
        var a = _next != null ? _next : _cur;
        if (a == null) {
            return "roads none";
        }
        return "roads " + a.gotCount + "/" + a.chunks + " " + a.points + "pts " + ((a.lastAt - a.firstAt) / 1000.0).format("%.1f") + "s";
    }

    // True when a complete square covers the whole screen at (lat, lon) and zoom z.
    function covers(lat as Double, lon as Double, z as Number) as Boolean {
        var a = _cur;
        if (a == null) {
            return false;
        }
        var w = world(lat, lon);
        return coversArea(a, (w[0] - a.ox).toNumber(), (w[1] - a.oy).toNumber(), z);
    }

    private function coversArea(a as RoadArea, ux as Number, uy as Number, z as Number) as Boolean {
        var half = z >= 17 ? HALF >> (z - 17) : HALF << (17 - z);
        return ux - half >= 0 && uy - half >= 0 && ux + half <= a.size && uy + half <= a.size;
    }

    // Draws the roads around (lat, lon) at zoom z (same scale as the Google pictures: one screen pixel is
    // one z+1 world pixel). Returns true when a complete square covered the whole screen.
    function draw(dc as Graphics.Dc, lat as Double, lon as Double, z as Number) as Boolean {
        var w = world(lat, lon);
        var mul = z > 17 ? 1 << (z - 17) : 1;
        var sh = z < 17 ? 17 - z : 0;
        // Half the screen in z18 pixels, plus a little so thick lines just off screen still show.
        var reach = ((HALF + 12) << sh) / mul;
        // Zoomed out, small streets would be clutter and too much drawing: leave them out.
        var minCls = z >= 16 ? 0 : (z == 15 ? 1 : (z == 14 ? 2 : 3));
        var f = z <= 14 ? 2 : (z == 15 ? 3 : (z == 16 ? 4 : (z == 17 ? 6 : (z == 18 ? 8 : 10))));
        var a = _cur;
        var b = _next;
        var ax = 0;
        var ay = 0;
        var bx = 0;
        var by = 0;
        var covered = false;
        if (a != null) {
            ax = (w[0] - a.ox).toNumber();
            ay = (w[1] - a.oy).toNumber();
            covered = coversArea(a, ax, ay, z);
        }
        if (b != null) {
            if (covered) {
                b = null;   // the arriving square isn't needed yet
            } else {
                bx = (w[0] - b.ox).toNumber();
                by = (w[1] - b.oy).toNumber();
            }
        }
        // Class by class, so main roads are drawn over small ones.
        for (var cls = minCls; cls < ROAD_CLASSES; cls++) {
            var pen = ROAD_WIDTH[cls] * f / 4;
            dc.setPenWidth(pen < 2 ? 2 : pen);
            dc.setColor(ROAD_COLOR[cls], Graphics.COLOR_TRANSPARENT);
            if (a != null) {
                drawClass(dc, a, cls, ax, ay, mul, sh, reach);
            }
            if (b != null) {
                drawClass(dc, b, cls, bx, by, mul, sh, reach);
            }
        }
        dc.setPenWidth(1);
        return covered;
    }

    private function drawClass(dc as Graphics.Dc, a as RoadArea, cls as Number, ux as Number, uy as Number,
            mul as Number, sh as Number, reach as Number) as Void {
        var x0 = ux - reach;
        var x1 = ux + reach;
        var y0 = uy - reach;
        var y1 = uy + reach;
        for (var c = 0; c < a.chunks; c++) {
            var cl = a.lines[c];
            var xy = a.xy[c];
            if (cl == null || xy == null) {
                continue;
            }
            var l = cl[cls];
            var n = l.size();
            for (var i = 0; i < n; i += 6) {
                // Skip lines whose box is off screen.
                if (l[i + 4] < x0 || l[i + 2] > x1 || l[i + 5] < y0 || l[i + 3] > y1) {
                    continue;
                }
                var j = l[i];
                var e = l[i + 1];
                var px = HALF + (((xy[j] - ux) * mul) >> sh);
                var py = HALF + (((xy[j + 1] - uy) * mul) >> sh);
                for (j += 2; j < e; j += 2) {
                    var qx = HALF + (((xy[j] - ux) * mul) >> sh);
                    var qy = HALF + (((xy[j + 1] - uy) * mul) >> sh);
                    dc.drawLine(px, py, qx, qy);
                    px = qx;
                    py = qy;
                }
            }
        }
    }

    // z18 Web Mercator pixel coordinates (the world is 2^26 pixels wide).
    private function world(lat as Double, lon as Double) as [Double, Double] {
        var s = Math.sin(lat * Math.PI / 180.0d);
        var x = (lon + 180.0d) / 360.0d * 67108864.0d;
        var y = (0.5d - Math.ln((1.0d + s) / (1.0d - s)) / (4.0d * Math.PI)) * 67108864.0d;
        return [x, y];
    }
}
