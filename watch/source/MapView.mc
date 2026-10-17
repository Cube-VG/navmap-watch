import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// Day style for reading in sunlight. Google draws only roads, water and road names; the land comes out
// transparent and the watch fills it with MAP_GROUND. The picture is reduced to MAP_PALETTE before it's
// sent, and fewer colours make a smaller picture; road edges on transparent land are also clean steps
// instead of blended greys, which pack smaller. On 14 maps in 7 cities each was smaller than v0.8's three
// greys, 7% fewer Bluetooth bytes in all, and about half of v0.5's 15 colours.
// Minor roads and water share a blue-grey, main roads and names are dark slate with a thin blue-grey
// outline. Area names, POIs and transit are off.
const MAP_STYLE =
    "style=element:geometry%7Cvisibility:off" +
    "&style=feature:poi%7Cvisibility:off" +
    "&style=feature:transit%7Cvisibility:off" +
    "&style=feature:administrative%7Cvisibility:off" +
    "&style=feature:road%7Celement:labels.icons%7Cvisibility:off" +
    "&style=element:labels.text.fill%7Ccolor:0x3d4654" +
    "&style=element:labels.text.stroke%7Ccolor:0x9fb3c9%7Cweight:2" +
    "&style=feature:road%7Celement:geometry%7Cvisibility:on%7Ccolor:0x9fb3c9" +
    "&style=feature:road.arterial%7Celement:geometry%7Ccolor:0x3d4654" +
    "&style=feature:road.highway%7Celement:geometry%7Ccolor:0x3d4654" +
    "&style=feature:water%7Celement:geometry%7Cvisibility:on%7Ccolor:0x9fb3c9";

const MAP_PALETTE = [0x9fb3c9, 0x3d4654];
const MAP_GROUND = 0xf6f3ec;   // warm paper, drawn by the watch under the transparent land

const SCREEN = 390;
const HALF = 195;
const ROUTE_BLUE = 0x1a73e8;   // Google Maps route blue, strong on a light map
const ROUTE_EDGE = 0x0b4fb3;   // its darker edge, which keeps the line clear of the dark roads
const RUN_START = -99999;      // marks the start of a run of points in drawRoute
const REQUEST_TIMEOUT_MS = 30000;
const CREDITS_MS = 6000;       // how long the map's own credit strip stays up (drawCredits)
const STRIP = 48;              // the bottom rows of a map picture, where Google puts its logo and data credit

// One map download. Each request gets its own callback object, so a reply that arrives after it
// timed out is recognised and dropped instead of being drawn in the wrong place.
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

// The one screen: the map around you, north up, with Google Maps' next turn on top.
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
    private var _reqStart as Number = 0;
    private var _retryAt as Number = 0;
    private var _backoff as Number = 0;
    private const SIZES = [280, 240, 200];   // Static Maps points; image is 2x (scale=2)
    private var _sizeIdx as Number = 0;
    private var _fetchSecs as Float = 6.0;

    // Zoom follows speed only; there are no zoom buttons.
    private var _autoZoom as Number = 16;

    // Until when the credit strip shows (System.getTimer()), and whether it has shown on its own yet
    private var _creditsUntil as Number = 0;
    private var _creditsShown as Boolean = false;

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
        // Exit confirmed, or the navigation ended 5 s ago: NavMap closes, back to the watch face.
        var endAt = _m.endAt;
        if (app.exitRequested || (endAt != null && System.getTimer() - endAt > 5000)) {
            app.exitRequested = false;
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            return;
        }
        app.tickPhone();
        app.link.tick();
        if (_m.updateLive()) {
            _m.alertUser();
        }
        _m.keepAwake();
        _m.decodeStep();
        maybeFetch();
        WatchUi.requestUpdate();
    }

    private function desiredZoom() as Number {
        var kmh = _m.speed * 3.6;
        // Hysteresis: only change band when clearly past the threshold.
        if (_autoZoom >= 16 && kmh > 30) { _autoZoom = 15; }
        else if (_autoZoom == 15 && kmh < 22) { _autoZoom = 16; }
        else if (_autoZoom == 15 && kmh > 65) { _autoZoom = 14; }
        else if (_autoZoom <= 14 && kmh < 52) { _autoZoom = 15; }
        return _autoZoom;
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

    function maybeFetch() as Void {
        var now = System.getTimer();
        if (_inFlight) {
            // A reply that never comes would otherwise freeze the map for good. It isn't cancelled
            // (that would cancel NavLink's request too); if it still arrives, its old id drops it.
            if (now - _reqStart < REQUEST_TIMEOUT_MS) {
                return;
            }
            _inFlight = false;
            _reqId++;
            retryLater();
        }
        var lat = _m.lat;
        var lon = _m.lon;
        var key = Keys.mapsKey();
        if (key == null) {
            // NavBridge hasn't handed over the key, or took it back when the user withdrew consent: no map at
            // all, not even the last one.
            _img = null;
            return;
        }
        if (lat == null || lon == null || now < _retryAt || !System.getDeviceSettings().phoneConnected) {
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
            "&key=" + key;
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
        try {
            Communications.makeImageRequest(url, null, opts, req.method(:onImage));
        } catch (e) {
            _inFlight = false;
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
            return;   // timed out; a newer request owns the map now
        }
        _inFlight = false;
        var secs = (System.getTimer() - _reqStart) / 1000.0;
        System.println("map " + code + " z" + req.zoom + " " + secs.format("%.1f") + "s");
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
                _backoff = 0;
                _retryAt = 0;
                // The first map shows its credit strip once by itself; after that, START shows it.
                if (!_creditsShown) {
                    _creditsShown = true;
                    _creditsUntil = System.getTimer() + CREDITS_MS;
                }
            } catch (e) {
                stepDown();
                retryLater();
            }
            // The zoom may have changed while this one was downloading.
            if (_imgZoom != desiredZoom()) {
                maybeFetch();
            }
        } else {
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
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        // Dimmed (always-on) screen: AMOLED burn-in rules allow only a few lit pixels, so no map.
        if (isLowPower()) {
            drawLowPower(dc);
            return;
        }

        var lat = _m.lat;
        var lon = _m.lon;
        var img = _img;
        if (img != null && lat != null && lon != null) {
            var z = desiredZoom();
            dc.setColor(MAP_GROUND, MAP_GROUND);
            dc.clear();
            drawMap(dc, img, lat, lon, z);
            drawRoute(dc, lat, lon, z);
            drawAttribution(dc);
        } else {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(HALF, HALF + 50, Graphics.FONT_SMALL, lat == null ? "Waiting for GPS" : Keys.mapsKey() == null ? "No map key yet" : "Loading map",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        drawMarker(dc);

        if (_m.navActive) {
            drawBanner(dc);
            if (_m.eta.length() > 0) {
                drawPill(dc, 318, _m.eta, Graphics.FONT_TINY);
            }
        } else if (_m.endAt != null) {
            drawPill(dc, 70, _m.endText, Graphics.FONT_TINY);
        } else {
            // Higher than the ETA: the screen is wider there, and "NavBridge not answering" is long.
            drawPill(dc, 292, waitingText(), Graphics.FONT_XTINY);
        }
        img = _img;   // drawMap drops an image the graphics pool purged
        if (img != null && System.getTimer() < _creditsUntil) {
            drawCredits(dc, img);
        }
    }

    // START or a tap shows the credit strip (MapDelegate).
    function showCredits() as Void {
        if (_img != null) {
            _creditsUntil = System.getTimer() + CREDITS_MS;
            WatchUi.requestUpdate();
        }
    }

    // What NavMap is waiting for when there's no navigation to show.
    private function waitingText() as String {
        if (!System.getDeviceSettings().phoneConnected) {
            return "Phone not connected";
        }
        if (_m.consentNeeded) {
            return "Open NavBridge to agree";
        }
        if (getApp().link.refused) {
            return "Open NavBridge to pair";
        }
        if (getApp().link.fails >= 3) {
            return "NavBridge not answering";
        }
        return "Waiting for Google Maps";
    }

    // Draws the map so our position is at the centre. After a zoom change the current image is shown
    // scaled until the sharp one arrives.
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

    // Google's logo and data credit are printed along the bottom of each map picture, which falls outside the
    // round screen. In their place goes the text form Google allows where its logo doesn't fit: "Google Maps",
    // unchanged and on one line, 12-16 sp (FONT_XTINY here is Roboto at about 13 sp), in Google's grey on a light
    // pill so it stays readable over dark roads. The data credit, which names other providers in some countries,
    // is shown by drawCredits.
    private function drawAttribution(dc as Graphics.Dc) as Void {
        var font = Graphics.FONT_XTINY;
        var w = dc.getTextWidthInPixels("Google Maps", font) + 20;
        var h = dc.getFontHeight(font);
        dc.setColor(MAP_GROUND, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(HALF - w / 2, 352 - h / 2, w, h, h / 2);
        dc.setColor(0x5e5e5e, Graphics.COLOR_TRANSPARENT);
        dc.drawText(HALF, 352, font, "Google Maps", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // The map picture's own credit strip, Google's logo and "Map data ©…" with any other providers, unscaled and
    // cut into two rows so it fits the round screen. It shows by itself with the first map and then whenever START
    // is pressed: Google's suggestion where a screen is too small for the full credit is a "Data sources" control.
    private function drawCredits(dc as Graphics.Dc, img as WatchUi.BitmapResource or Graphics.BitmapReference) as Void {
        var w = _imgHalfW;                 // each row is half the picture
        var top = 2 * _imgHalfH - STRIP;   // the strip's first row in the picture
        var x = HALF - w / 2;
        var y = HALF - STRIP - 4;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(x - 10, y - 10, w + 20, 2 * STRIP + 28, 14);
        try {
            dc.setClip(x, y, w, STRIP);
            dc.drawBitmap(x, y - top, img);
            dc.setClip(x, y + STRIP + 8, w, STRIP);
            dc.drawBitmap(x - w, y + STRIP + 8 - top, img);
        } catch (e) {
            _img = null;
        }
        dc.clearClip();
    }

    // Google-style line: a darker blue edge under the blue, smoothed, with round bends. The screen points are
    // worked out once and drawn twice.
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
        // Segments near the screen (cheap cull), as runs of points: a run ends where the line leaves it.
        var pts = [] as Array<Number>;
        var px = 0;
        var py = 0;
        var prevOn = false;
        for (var i = 0; i + 1 < r.size(); i += 2) {
            var x = HALF + ((r[i + 1] - lon5) * kx).toNumber();
            var y = HALF - ((r[i] - lat5) * ky).toNumber();
            var on = x > -400 && x < 790 && y > -400 && y < 790;
            if (i > 0 && (on || prevOn)) {
                if (!prevOn || pts.size() == 0) {   // the first segment starts a run too
                    pts.add(RUN_START);
                    pts.add(px);
                    pts.add(py);
                }
                pts.add(x);
                pts.add(y);
            }
            px = x;
            py = y;
            prevOn = on;
        }
        if (pts.size() == 0) {
            return;
        }
        if (dc has :setAntiAlias) {
            dc.setAntiAlias(true);
        }
        strokeRoute(dc, pts, ROUTE_EDGE, 12);
        strokeRoute(dc, pts, ROUTE_BLUE, 8);
        dc.setPenWidth(1);
        if (dc has :setAntiAlias) {
            dc.setAntiAlias(false);
        }
    }

    private function strokeRoute(dc as Graphics.Dc, pts as Array<Number>, color as Number, w as Number) as Void {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(w);
        var rad = w / 2;
        var px = 0;
        var py = 0;
        var i = 0;
        while (i + 1 < pts.size()) {
            var start = pts[i] == RUN_START;
            if (start) {
                i++;
            }
            var x = pts[i];
            var y = pts[i + 1];
            if (!start) {
                dc.drawLine(px, py, x, y);
            }
            // Fills the notch a wide line leaves at each bend.
            if (x > -rad && x < SCREEN + rad && y > -rad && y < SCREEN + rad) {
                dc.fillCircle(x, y, rad);
            }
            px = x;
            py = y;
            i += 2;
        }
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
        // Dark green, and route blue from "turn now" on, so a glance says this is the turn.
        var bg = _m.turnNow ? ROUTE_BLUE : 0x0b3d2e;
        dc.setColor(bg, bg);
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
        var text = _m.liveText;
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

    // Dimmed screen: black, with only the next turn (or the time) in grey. Burn-in rules: at most 10% of
    // the pixels lit, and no pixel lit for more than three minutes in a row, so it swaps between an upper
    // and a lower spot every minute.
    private function drawLowPower(dc as Graphics.Dc) as Void {
        var t = System.getClockTime();
        var y = t.min % 2 == 0 ? 62 : 206;
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        if (_m.navActive) {
            drawArrow(dc, HALF - 36, y);
            drawDistance(dc, HALF, y + 96, Graphics.TEXT_JUSTIFY_CENTER);
        } else {
            dc.drawText(HALF, y + 50, Graphics.FONT_NUMBER_MILD, t.hour.format("%02d") + ":" + t.min.format("%02d"),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.drawText(HALF, y + 96, Graphics.FONT_XTINY, _m.endAt != null ? _m.endText : "NavMap",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    private function drawPill(dc as Graphics.Dc, y as Number, text as String, font as Graphics.FontDefinition) as Void {
        var w = dc.getTextWidthInPixels(text, font) + 24;
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(HALF - w / 2, y - 17, w, 34, 17);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(HALF, y, font, text, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

// START or a tap shows the map's credits, and Back asks before leaving. Every other button and swipe is
// swallowed, so nothing else pops up over the map.
class MapDelegate extends WatchUi.BehaviorDelegate {
    private var _view as MapView;

    function initialize(view as MapView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onPreviousPage() as Boolean {
        return true;
    }

    function onNextPage() as Boolean {
        return true;
    }

    function onMenu() as Boolean {
        return true;
    }

    function onSelect() as Boolean {
        _view.showCredits();
        return true;
    }

    function onTap(evt as WatchUi.ClickEvent) as Boolean {
        _view.showCredits();
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
