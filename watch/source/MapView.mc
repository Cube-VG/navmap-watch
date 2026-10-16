import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;
import Toybox.Timer;
import Toybox.WatchUi;

// Day style for reading in sunlight: near-white ground, dark grey roads, dark labels, no POIs or transit.
const MAP_STYLE =
    "style=element:geometry%7Ccolor:0xf7f7f5" +
    "&style=element:labels.text.fill%7Ccolor:0x111827" +
    "&style=element:labels.text.stroke%7Ccolor:0xffffff%7Cweight:4" +
    "&style=feature:poi%7Cvisibility:off" +
    "&style=feature:transit%7Cvisibility:off" +
    "&style=feature:administrative%7Celement:geometry%7Cvisibility:off" +
    "&style=feature:road%7Celement:labels.icons%7Cvisibility:off" +
    "&style=feature:road%7Celement:geometry%7Ccolor:0x9ca3af" +
    "&style=feature:road.arterial%7Celement:geometry%7Ccolor:0x4b5563" +
    "&style=feature:road.highway%7Celement:geometry.fill%7Ccolor:0xf2a33a" +
    "&style=feature:road.highway%7Celement:geometry.stroke%7Ccolor:0xb36b10" +
    "&style=feature:water%7Celement:geometry%7Ccolor:0x8fc1ee" +
    "&style=feature:landscape.man_made%7Celement:geometry%7Ccolor:0xecece8";

// The style's colours plus a few in-between shades for anti-aliased edges. With 16 colours or fewer the
// phone sends 4 bits per pixel instead of 16, so a map is a quarter of the size in Bluetooth and memory.
const MAP_PALETTE = [
    0xffffff, 0xf7f7f5, 0xecece8, 0xd1d5db, 0x9ca3af, 0x6b7280, 0x4b5563, 0x2d3440,
    0x111827, 0xf2a33a, 0xb36b10, 0xf8d29c, 0x8fc1ee, 0xc4def5, 0x000000
];
const MAP_GROUND = 0xf7f7f5;

const SCREEN = 390;
const HALF = 195;
const ROUTE_BLUE = 0x1a73e8;   // Google Maps route blue, strong on a light map
const REQUEST_TIMEOUT_MS = 30000;

// One map download. Each request gets its own callback object, so a reply that arrives after it was
// cancelled or timed out is recognised and dropped instead of being drawn in the wrong place.
class ImageReq {
    var id as Number;
    var lat as Double;
    var lon as Double;
    var zoom as Number;
    private var _view as MapView;

    function initialize(view as MapView, id as Number, lat as Double, lon as Double, zoom as Number) {
        _view = view;
        self.id = id;
        self.lat = lat;
        self.lon = lon;
        self.zoom = zoom;
    }

    function onImage(code as Number, data as WatchUi.BitmapResource or Graphics.BitmapReference or Null) as Void {
        _view.onImage(self, code, data);
    }
}

class MapView extends WatchUi.View {
    private var _m as NavModel;
    private var _timer as Timer.Timer;

    // Current map image and where it was taken
    private var _img as WatchUi.BitmapResource or Graphics.BitmapReference or Null = null;
    private var _imgLat as Double = 0.0d;
    private var _imgLon as Double = 0.0d;
    private var _imgZoom as Number = 16;
    private var _imgHalfW as Number = 280;
    private var _imgHalfH as Number = 280;

    // Request state
    private var _reqId as Number = 0;
    private var _inFlight as Boolean = false;
    private var _reqZoom as Number = 16;
    private var _reqStart as Number = 0;
    private var _retryAt as Number = 0;
    private var _backoff as Number = 0;
    private const SIZES = [280, 240, 200];   // Static Maps points; image is 2x (scale=2)
    private var _sizeIdx as Number = 0;
    private var _fetchSecs as Float = 6.0;
    private var _lastErr as Number = 0;
    private var _fetches as Number = 0;
    private var _timeouts as Number = 0;

    // Zoom
    private var _autoZoom as Number = 16;
    private var _zoomShownAt as Number = -100000;
    var zoomOffset as Number = 0;

    // Diagnostics
    var showDiag as Boolean = false;
    private var _updMs as Number = 0;
    private var _lowPowerFrames as Number = 0;

    function initialize(model as NavModel) {
        View.initialize();
        _m = model;
        _timer = new Timer.Timer();
    }

    function onShow() as Void {
        _timer.start(method(:onTick), 1000, true);
    }

    function onHide() as Void {
        _timer.stop();
    }

    function onTick() as Void {
        var app = getApp();
        if (app.exitRequested) {
            app.exitRequested = false;
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            return;
        }
        // Navigation ended: show "Arrived" / "Navigation ended" for a minute, then leave.
        var endAt = _m.endAt;
        if (endAt != null && System.getTimer() - endAt > 60000) {
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            return;
        }
        app.tickPhone();
        maybeFetch();
        WatchUi.requestUpdate();
    }

    // ---- zoom ----------------------------------------------------------------

    private function desiredZoom() as Number {
        var kmh = _m.speed * 3.6;
        // Hysteresis: only change band when clearly past the threshold.
        if (_autoZoom >= 16 && kmh > 30) { _autoZoom = 15; }
        else if (_autoZoom == 15 && kmh < 22) { _autoZoom = 16; }
        else if (_autoZoom == 15 && kmh > 65) { _autoZoom = 14; }
        else if (_autoZoom <= 14 && kmh < 52) { _autoZoom = 15; }
        var z = _autoZoom + zoomOffset;
        return z < 10 ? 10 : (z > 19 ? 19 : z);
    }

    // Up/Down: the current map is redrawn scaled straight away, and the sharp one is requested at once.
    function zoomBy(step as Number) as Void {
        var next = zoomOffset + step;
        if (next > 4 || next < -4) {
            return;
        }
        zoomOffset = next;
        _zoomShownAt = System.getTimer();
        if (_inFlight && _reqZoom != desiredZoom()) {
            cancelRequest();
        }
        _retryAt = 0;
        maybeFetch();
        WatchUi.requestUpdate();
    }

    // ---- map fetching ----------------------------------------------------

    // Image pixels per degree of longitude at zoom z with scale=2.
    private function pxPerDeg(z as Number) as Double {
        return 256.0d * (1 << z) * 2.0d / 360.0d;
    }

    // Offset in image pixels of (lat, lon) from the point (cLat, cLon).
    private function offsetPx(lat as Double, lon as Double, cLat as Double, cLon as Double, z as Number) as [Double, Double] {
        var k = pxPerDeg(z);
        var dx = (lon - cLon) * k;
        var dy = -(lat - cLat) * k / Math.cos(cLat * Math.PI / 180.0d);
        return [dx, dy];
    }

    private function cancelRequest() as Void {
        Communications.cancelAllRequests();
        _inFlight = false;
        _reqId++;   // any late reply to the old request is ignored
    }

    function maybeFetch() as Void {
        var now = System.getTimer();
        if (_inFlight) {
            // A reply that never comes would otherwise freeze the map for good.
            if (now - _reqStart < REQUEST_TIMEOUT_MS) {
                return;
            }
            cancelRequest();
            _timeouts++;
            _lastErr = -1;
            retryLater();
        }
        var lat = _m.lat;
        var lon = _m.lon;
        if (lat == null || lon == null || now < _retryAt) {
            return;
        }
        if (!System.getDeviceSettings().phoneConnected) {
            return;
        }
        // While the screen is dimmed nobody is looking: only keep the map fresh during navigation.
        if (isLowPower() && !_m.navActive && _img != null) {
            return;
        }
        var z = desiredZoom();
        var need = false;
        var margin = (_imgHalfW < _imgHalfH ? _imgHalfW : _imgHalfH) - HALF;
        if (_img == null || z != _imgZoom) {
            need = true;
        } else {
            var off = offsetPx(lat, lon, _imgLat, _imgLon, _imgZoom);
            var mpp = 40075016.686d * Math.cos(_imgLat * Math.PI / 180.0d) / (256.0d * (1 << _imgZoom) * 2.0d);
            // Where will we be by the time a new image could arrive?
            var adv = _m.speed * (_fetchSecs + 3.0) / mpp;
            var fx = off[0] + Math.sin(_m.heading) * adv;
            var fy = off[1] - Math.cos(_m.heading) * adv;
            var nowOut = off[0].abs() > margin || off[1].abs() > margin;
            var soonOut = fx.abs() > margin || fy.abs() > margin;
            // Near a turn keep Bluetooth free for the turn message unless the map no longer covers us.
            var nearTurn = _m.navActive && _m.distMeters >= 0 && _m.distMeters < 150;
            need = nowOut || (soonOut && !nearTurn);
        }
        if (!need) {
            return;
        }
        // Centre the new image ahead of us so it lasts longer.
        var size = SIZES[_sizeIdx];
        var mppNew = 40075016.686d * Math.cos(lat * Math.PI / 180.0d) / (256.0d * (1 << z) * 2.0d);
        var leadPx = _m.speed * (_fetchSecs + 3.0) / mppNew;
        var maxLead = (size - HALF) * 0.6;   // size is also the image half-width in pixels
        if (leadPx > maxLead) { leadPx = maxLead; }
        var leadM = leadPx * mppNew;
        var cLat = lat + leadM * Math.cos(_m.heading) / 111320.0d;
        var cLon = lon + leadM * Math.sin(_m.heading) / (111320.0d * Math.cos(lat * Math.PI / 180.0d));

        var url = "https://maps.googleapis.com/maps/api/staticmap?center=" +
            cLat.format("%.6f") + "," + cLon.format("%.6f") +
            "&zoom=" + z + "&size=" + size + "x" + size + "&scale=2&maptype=roadmap&" + MAP_STYLE +
            "&key=" + MAPS_API_KEY;
        var opts = {
            :maxWidth => size * 2,
            :maxHeight => size * 2,
            :palette => MAP_PALETTE,
            :packingFormat => Communications.PACKING_FORMAT_PNG,
            :dithering => Communications.IMAGE_DITHERING_NONE
        };
        _reqId++;
        var req = new ImageReq(self, _reqId, cLat, cLon, z);
        _inFlight = true;
        _reqStart = now;
        _reqZoom = z;
        try {
            Communications.makeImageRequest(url, null, opts, req.method(:onImage));
        } catch (e) {
            _inFlight = false;
            _lastErr = -2;
            retryLater();
        }
    }

    // After a failure wait 5 s, then 10, 20, up to 60 s, so a bad key or no internet doesn't flood Bluetooth.
    private function retryLater() as Void {
        _backoff = _backoff == 0 ? 5000 : (_backoff * 2 > 60000 ? 60000 : _backoff * 2);
        _retryAt = System.getTimer() + _backoff;
    }

    function onImage(req as ImageReq, code as Number, data as WatchUi.BitmapResource or Graphics.BitmapReference or Null) as Void {
        if (req.id != _reqId) {
            return;   // cancelled or timed out; a newer request owns the map now
        }
        _inFlight = false;
        _fetches++;
        var secs = (System.getTimer() - _reqStart) / 1000.0;
        if (code == 200 && data != null) {
            _fetchSecs = _fetchSecs * 0.5 + secs * 0.5;
            try {
                var bmp = data as WatchUi.BitmapResource or Graphics.BitmapReference;
                // Pin the image so the graphics pool can't purge it; keep the reference if that fails.
                if (data instanceof Graphics.BitmapReference) {
                    var got = data.get();
                    if (got instanceof WatchUi.BitmapResource) {
                        bmp = got;
                    }
                }
                var w = bmp.getWidth();
                var h = bmp.getHeight();
                _img = null;   // let the old map go before keeping the new one
                _img = bmp;
                _imgHalfW = w / 2;
                _imgHalfH = h / 2;
                _imgLat = req.lat;
                _imgLon = req.lon;
                _imgZoom = req.zoom;
                _lastErr = 0;
                _backoff = 0;
                _retryAt = 0;
            } catch (e) {
                _lastErr = -9001;
                stepDown();
                retryLater();
            }
            // The zoom may have changed while this one was downloading.
            if (_imgZoom != desiredZoom()) {
                maybeFetch();
            }
        } else {
            _lastErr = code;
            // Too large for the transfer or memory: step down to a smaller image.
            if (code == -402 || code == -403 || code == -1006) {
                stepDown();
            }
            retryLater();
        }
        WatchUi.requestUpdate();
    }

    private function stepDown() as Void {
        if (_sizeIdx < SIZES.size() - 1) {
            _sizeIdx++;
        }
    }

    // ---- drawing ------------------------------------------------------------

    private function isLowPower() as Boolean {
        if (System has :getDisplayMode) {
            return System.getDisplayMode() == System.DISPLAY_MODE_LOW_POWER;
        }
        return false;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var t0 = System.getTimer();
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        // Dimmed (always-on) screen: AMOLED burn-in rules allow only a few lit pixels, so no map.
        if (isLowPower()) {
            _lowPowerFrames++;
            drawLowPower(dc);
            return;
        }

        var lat = _m.lat;
        var lon = _m.lon;
        var img = _img;
        var z = desiredZoom();
        if (img != null && lat != null && lon != null) {
            dc.setColor(MAP_GROUND, MAP_GROUND);
            dc.clear();
            drawMap(dc, img, lat, lon, z);
            drawRoute(dc, lat, lon, z);
        } else {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            var msg = lat == null ? "Waiting for GPS" : (System.getDeviceSettings().phoneConnected ? "Loading map" : "Phone not connected");
            dc.drawText(HALF, HALF + 50, Graphics.FONT_SMALL, msg, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        drawMarker(dc);
        if (_m.navActive) {
            drawBanner(dc);
        } else if (_m.endAt != null) {
            drawCenteredPill(dc, 70, _m.endText);
        }
        drawFooter(dc, z);
        if (showDiag) {
            drawDiag(dc);
        }
        _updMs = System.getTimer() - t0;
    }

    // Draws the map so our position is at the centre. While a new zoom level downloads, the current
    // image is shown scaled so Up/Down respond immediately.
    private function drawMap(dc as Graphics.Dc, img as WatchUi.BitmapResource or Graphics.BitmapReference, lat as Double, lon as Double, z as Number) as Void {
        var off = offsetPx(lat, lon, _imgLat, _imgLon, _imgZoom);
        var dz = z - _imgZoom;
        try {
            if (dz == 0 || dz > 2 || dz < -2 || !(dc has :drawScaledBitmap)) {
                dc.drawBitmap(HALF - _imgHalfW - off[0].toNumber(), HALF - _imgHalfH - off[1].toNumber(), img);
            } else {
                var s = dz > 0 ? (1 << dz).toFloat() : 1.0 / (1 << -dz);
                var w = (_imgHalfW * 2 * s).toNumber();
                var h = (_imgHalfH * 2 * s).toNumber();
                var x = HALF - ((_imgHalfW + off[0]) * s).toNumber();
                var y = HALF - ((_imgHalfH + off[1]) * s).toNumber();
                dc.drawScaledBitmap(x, y, w, h, img);
            }
        } catch (e) {
            // The graphics pool dropped the image: fetch a fresh one.
            _img = null;
        }
    }

    private function drawRoute(dc as Graphics.Dc, lat as Double, lon as Double, z as Number) as Void {
        var r = _m.route;
        if (r == null || r.size() < 4) {
            return;
        }
        // Points are in 1e-5 degrees; scale factors are worked out once so the loop is integer-friendly.
        var kx = (pxPerDeg(z) / 100000.0d).toFloat();
        var ky = (kx / Math.cos(lat * Math.PI / 180.0d)).toFloat();
        var lat5 = Math.round(lat * 100000.0d).toNumber();
        var lon5 = Math.round(lon * 100000.0d).toNumber();
        dc.setColor(ROUTE_BLUE, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(8);
        var px = 0;
        var py = 0;
        var prevOn = false;
        for (var i = 0; i + 1 < r.size(); i += 2) {
            var x = HALF + ((r[i + 1] - lon5) * kx).toNumber();
            var y = HALF - ((r[i] - lat5) * ky).toNumber();
            // Only draw segments near the screen (cheap cull).
            var on = x > -400 && x < 790 && y > -400 && y < 790;
            if (i > 0 && (on || prevOn)) {
                dc.drawLine(px, py, x, y);
            }
            px = x;
            py = y;
            prevOn = on;
        }
        dc.setPenWidth(1);
    }

    // Direction-of-travel chevron at the centre of the screen (map is north-up).
    private function drawMarker(dc as Graphics.Dc) as Void {
        var h = _m.heading;
        var s = Math.sin(h);
        var c = Math.cos(h);
        var pts = [[0.0, -20.0], [13.0, 14.0], [0.0, 6.0], [-13.0, 14.0]];
        var poly = [] as Array<[Numeric, Numeric]>;
        for (var i = 0; i < pts.size(); i++) {
            var x = pts[i][0];
            var y = pts[i][1];
            poly.add([(HALF + x * c - y * s).toNumber(), (HALF + x * s + y * c).toNumber()]);
        }
        dc.setColor(0x1f2933, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(HALF, HALF, 26);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(HALF, HALF, 23);
        dc.setColor(_m.hasFix ? 0x1a73e8 : Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.fillPolygon(poly);
    }

    private function drawBanner(dc as Graphics.Dc) as Void {
        dc.setColor(0x0b3d2e, 0x0b3d2e);
        dc.fillRectangle(0, 0, SCREEN, 128);
        // Google's own maneuver arrow (48x48 bitmap) drawn at 1.5x.
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        drawArrow(dc, 84, 26);
        drawDistance(dc, 166, 58, Graphics.TEXT_JUSTIFY_LEFT);
        if (_m.instr.length() > 0) {
            var txt = Graphics.fitTextToArea(_m.instr, Graphics.FONT_XTINY, 300, 30, true);
            dc.drawText(HALF, 110, Graphics.FONT_XTINY, txt, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // "350 m": the number in the big digits font (which has no letters), the unit after it in a text font.
    private function drawDistance(dc as Graphics.Dc, x as Number, y as Number, justify as Number) as Void {
        var text = _m.distText;
        if (text.length() == 0) {
            return;
        }
        var cut = text.find(" ");
        var num = cut != null ? text.substring(0, cut) : text;
        var unit = cut != null ? text.substring(cut + 1, text.length()) : "";
        var numFont = Graphics.FONT_NUMBER_MILD;
        // Anything that isn't digits (e.g. "Now") goes in a normal font.
        var chars = num != null ? num.toCharArray() : [] as Array<Char>;
        for (var i = 0; i < chars.size(); i++) {
            var ch = chars[i].toNumber();
            if (!((ch >= 48 && ch <= 57) || ch == 46 || ch == 44)) {   // 0-9 . ,
                numFont = Graphics.FONT_MEDIUM;
                unit = "";
                num = text;
                break;
            }
        }
        if (num == null) {
            return;
        }
        var nw = dc.getTextWidthInPixels(num, numFont);
        var uw = unit != null && unit.length() > 0 ? dc.getTextWidthInPixels(" " + unit, Graphics.FONT_TINY) : 0;
        var left = justify == Graphics.TEXT_JUSTIFY_CENTER ? x - (nw + uw) / 2 : x;
        var vc = Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER;
        dc.drawText(left, y, numFont, num, vc);
        if (uw > 0) {
            dc.drawText(left + nw, y + 6, Graphics.FONT_TINY, " " + unit, vc);
        }
    }

    private function drawArrow(dc as Graphics.Dc, ox as Number, oy as Number) as Void {
        var r = _m.arrowRects;
        if (r == null) {
            return;
        }
        for (var i = 0; i + 3 < r.size(); i += 4) {
            dc.fillRectangle(ox + r[i], oy + r[i + 1], r[i + 2], r[i + 3]);
        }
    }

    private function drawFooter(dc as Graphics.Dc, z as Number) as Void {
        if (_m.navActive && _m.eta.length() > 0) {
            drawCenteredPill(dc, 318, _m.eta);
        } else if (System.getTimer() - _zoomShownAt < 2500) {
            drawCenteredPill(dc, 318, "Zoom " + z);
        }
        dc.setColor(0x3a4350, Graphics.COLOR_TRANSPARENT);
        dc.drawText(HALF, 360, Graphics.FONT_XTINY, "Google", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        if (_inFlight) {
            dc.setColor(0x1a73e8, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(HALF, 340, 4);
        }
    }

    // Dimmed screen: black, with only the next turn (or the time) in thin white.
    private function drawLowPower(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        if (_m.navActive) {
            drawArrow(dc, HALF - 36, 80);
            drawDistance(dc, HALF, 200, Graphics.TEXT_JUSTIFY_CENTER);
            if (_m.instr.length() > 0) {
                var txt = Graphics.fitTextToArea(_m.instr, Graphics.FONT_XTINY, 280, 60, true);
                dc.drawText(HALF, 262, Graphics.FONT_XTINY, txt, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            }
        } else {
            var t = Gregorian.info(Time.now(), Time.FORMAT_SHORT);
            dc.drawText(HALF, HALF, Graphics.FONT_NUMBER_MEDIUM, t.hour.format("%02d") + ":" + t.min.format("%02d"),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.drawText(HALF, HALF + 70, Graphics.FONT_XTINY, _m.endAt != null ? _m.endText : "NavMap",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    private function drawCenteredPill(dc as Graphics.Dc, y as Number, text as String) as Void {
        var w = dc.getTextWidthInPixels(text, Graphics.FONT_TINY) + 24;
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(HALF - w / 2, y - 17, w, 34, 17);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(HALF, y, Graphics.FONT_TINY, text, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    private function drawDiag(dc as Graphics.Dc) as Void {
        var stats = System.getSystemStats();
        var lines = [
            "fetch " + _fetchSecs.format("%.1f") + "s n" + _fetches + " err " + _lastErr + " to" + _timeouts,
            "img " + (_imgHalfW * 2) + " z" + _imgZoom + " sz" + SIZES[_sizeIdx],
            "spd " + (_m.speed * 3.6).format("%.0f") + " gps " + (_m.hasFix ? "ok" : "--"),
            "draw " + _updMs + "ms lp" + _lowPowerFrames + " mem " + (stats.usedMemory / 1024) + "k",
            "phone " + (_m.phoneAnswered ? "ok" : "hello " + _m.hellos) + " msgs " + _m.msgCount
        ];
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.fillRectangle(45, 140, 300, 5 * 22 + 8);
        dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        for (var i = 0; i < lines.size(); i++) {
            dc.drawText(HALF, 152 + i * 22, Graphics.FONT_XTINY, lines[i], Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }
}

class MapDelegate extends WatchUi.BehaviorDelegate {
    private var _v as MapView;

    function initialize(v as MapView) {
        BehaviorDelegate.initialize();
        _v = v;
    }

    // Up = zoom in, Down = zoom out (swipes do the same)
    function onPreviousPage() as Boolean {
        _v.zoomBy(1);
        return true;
    }

    function onNextPage() as Boolean {
        _v.zoomBy(-1);
        return true;
    }

    // Start = diagnostics overlay
    function onSelect() as Boolean {
        _v.showDiag = !_v.showDiag;
        WatchUi.requestUpdate();
        return true;
    }

    // A wet sleeve or a brush of the screen shouldn't open the debug panel.
    function onTap(evt as WatchUi.ClickEvent) as Boolean {
        return true;
    }

    // Back asks before leaving, so a stray press while riding doesn't close the map.
    function onBack() as Boolean {
        WatchUi.pushView(new WatchUi.Confirmation("Exit NavMap?"), new ExitDelegate(), WatchUi.SLIDE_IMMEDIATE);
        return true;
    }
}

class ExitDelegate extends WatchUi.ConfirmationDelegate {
    function initialize() {
        ConfirmationDelegate.initialize();
    }

    function onResponse(response as WatchUi.Confirm) as Boolean {
        if (response == WatchUi.CONFIRM_YES) {
            getApp().exitRequested = true;
        }
        return true;
    }
}
