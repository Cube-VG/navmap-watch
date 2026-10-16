import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// Night style tuned for AMOLED: dark background, bright roads, no POIs or transit clutter.
const MAP_STYLE =
    "style=element:geometry%7Ccolor:0x1b2433" +
    "&style=element:labels.text.fill%7Ccolor:0xe8eaed" +
    "&style=element:labels.text.stroke%7Ccolor:0x1b2433%7Cweight:3" +
    "&style=feature:poi%7Cvisibility:off" +
    "&style=feature:transit%7Cvisibility:off" +
    "&style=feature:administrative%7Celement:geometry%7Cvisibility:off" +
    "&style=feature:road%7Celement:labels.icons%7Cvisibility:off" +
    "&style=feature:road%7Celement:geometry%7Ccolor:0x6f7a94" +
    "&style=feature:road.arterial%7Celement:geometry%7Ccolor:0x9aa3b8" +
    "&style=feature:road.highway%7Celement:geometry%7Ccolor:0xd8953a" +
    "&style=feature:water%7Celement:geometry%7Ccolor:0x0e2a47" +
    "&style=feature:landscape.man_made%7Celement:geometry%7Ccolor:0x222d3f";

const SCREEN = 390;
const HALF = 195;
const ROUTE_BLUE = 0x4f8ff7;

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
    private var _inFlight as Boolean = false;
    private var _reqStart as Number = 0;
    private var _reqLat as Double = 0.0d;
    private var _reqLon as Double = 0.0d;
    private var _reqZoom as Number = 16;
    private const SIZES = [280, 240, 200];   // Static Maps points; image is 2x (scale=2)
    private var _sizeIdx as Number = 0;
    private var _fetchSecs as Float = 8.0;
    private var _lastErr as Number = 0;
    private var _fetches as Number = 0;

    // Zoom
    private var _autoZoom as Number = 16;
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
        // Navigation ended: keep the map 25 s in case Maps is rerouting, show "Arrived", leave after 60 s more.
        var endAt = _m.endAt;
        if (endAt != null) {
            var since = System.getTimer() - endAt;
            if (since > 25000) {
                _m.navActive = false;
            }
            if (since > 85000) {
                WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
                return;
            }
        }
        maybeFetch();
        WatchUi.requestUpdate();
    }

    // ---- map fetching ----------------------------------------------------

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
        var lat = _m.lat;
        var lon = _m.lon;
        if (_inFlight || lat == null || lon == null) {
            return;
        }
        if (!System.getDeviceSettings().phoneConnected) {
            return;
        }
        var z = desiredZoom();
        var need = false;
        var margin = (_imgHalfW < _imgHalfH ? _imgHalfW : _imgHalfH) - HALF;
        var off = [0.0d, 0.0d] as [Double, Double];
        if (_img == null || z != _imgZoom) {
            need = true;
        } else {
            off = offsetPx(lat, lon, _imgLat, _imgLon, _imgZoom);
            var mpp = 40075016.686d * Math.cos(_imgLat * Math.PI / 180.0d) / (256.0d * (1 << _imgZoom) * 2.0d);
            // Where will we be by the time a new image could arrive?
            var adv = _m.speed * (_fetchSecs + 4.0) / mpp;
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
        var leadPx = _m.speed * (_fetchSecs + 4.0) / mppNew;
        var maxLead = (size - HALF) * 0.6;
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
            :packingFormat => Communications.PACKING_FORMAT_PNG,
            :dithering => Communications.IMAGE_DITHERING_NONE
        };
        _inFlight = true;
        _reqStart = System.getTimer();
        _reqLat = cLat;
        _reqLon = cLon;
        _reqZoom = z;
        Communications.makeImageRequest(url, null, opts, method(:onImage));
    }

    function onImage(code as Number, data as WatchUi.BitmapResource or Graphics.BitmapReference or Null) as Void {
        _inFlight = false;
        _fetches++;
        var secs = (System.getTimer() - _reqStart) / 1000.0;
        if (code == 200 && data != null) {
            _fetchSecs = _fetchSecs * 0.5 + secs * 0.5;
            var bmp = data as WatchUi.BitmapResource or Graphics.BitmapReference;
            try {
                // Pin the image so the graphics pool can't purge it; keep the reference if that fails.
                if (data instanceof Graphics.BitmapReference) {
                    var got = data.get();
                    if (got instanceof WatchUi.BitmapResource) {
                        bmp = got;
                    }
                }
                _imgHalfW = bmp.getWidth() / 2;
                _imgHalfH = bmp.getHeight() / 2;
                _img = bmp;
                _imgLat = _reqLat;
                _imgLon = _reqLon;
                _imgZoom = _reqZoom;
                _lastErr = 0;
            } catch (e) {
                _lastErr = -9001;
            }
        } else {
            _lastErr = code;
            // Too large for the transfer or memory: step down to a smaller image.
            if ((code == -402 || code == -403 || code == -1006) && _sizeIdx < SIZES.size() - 1) {
                _sizeIdx++;
            }
        }
        WatchUi.requestUpdate();
    }

    // ---- drawing ------------------------------------------------------------

    function onUpdate(dc as Graphics.Dc) as Void {
        var t0 = System.getTimer();
        var lowPower = false;
        if (System has :getDisplayMode) {
            lowPower = System.getDisplayMode() == System.DISPLAY_MODE_LOW_POWER;
        }
        if (lowPower) {
            _lowPowerFrames++;
        }

        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        var lat = _m.lat;
        var lon = _m.lon;
        var img = _img;
        if (img != null && lat != null && lon != null) {
            var off = offsetPx(lat, lon, _imgLat, _imgLon, _imgZoom);
            var x = HALF - _imgHalfW - off[0].toNumber();
            var y = HALF - _imgHalfH - off[1].toNumber();
            dc.drawBitmap(x, y, img);
            drawRoute(dc, lat, lon);
        } else {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            var msg = lat == null ? "Waiting for GPS" : (System.getDeviceSettings().phoneConnected ? "Loading map" : "Phone not connected");
            dc.drawText(HALF, HALF + 40, Graphics.FONT_SMALL, msg, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        drawMarker(dc);
        if (_m.navActive) {
            drawBanner(dc);
        } else if (_m.endAt != null) {
            drawCenteredPill(dc, 70, "Arrived");
        }
        drawFooter(dc);
        if (showDiag) {
            drawDiag(dc, lowPower);
        }
        _updMs = System.getTimer() - t0;
    }

    private function drawRoute(dc as Graphics.Dc, lat as Double, lon as Double) as Void {
        var r = _m.route;
        if (r == null || r.size() < 4) {
            return;
        }
        var k = pxPerDeg(_imgZoom);
        var kc = k / Math.cos(lat * Math.PI / 180.0d);
        dc.setColor(ROUTE_BLUE, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(8);
        var px = 0;
        var py = 0;
        var prevOn = false;
        for (var i = 0; i + 1 < r.size(); i += 2) {
            var qLat = r[i] / 100000.0d;
            var qLon = r[i + 1] / 100000.0d;
            var x = (HALF + (qLon - lon) * k).toNumber();
            var y = (HALF - (qLat - lat) * kc).toNumber();
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
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(HALF, HALF, 24);
        dc.setColor(_m.hasFix ? 0x1a73e8 : Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.fillPolygon(poly);
    }

    private function drawBanner(dc as Graphics.Dc) as Void {
        dc.setColor(0x0b3d2e, 0x0b3d2e);
        dc.fillRectangle(0, 0, SCREEN, 128);
        // Google's own maneuver arrow (48x48 bitmap) drawn at 1.5x.
        drawArrow(dc, 84, 26);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(166, 58, Graphics.FONT_NUMBER_MILD, _m.distText, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        if (_m.instr.length() > 0) {
            var txt = Graphics.fitTextToArea(_m.instr, Graphics.FONT_XTINY, 300, 30, true);
            dc.drawText(HALF, 110, Graphics.FONT_XTINY, txt, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // Arrow rows: 48 rows, 2 ints each. Int 0 holds x=0..31 (bit 31 = x0), int 1 holds x=32..47 (bit 15 = x32).
    private function drawArrow(dc as Graphics.Dc, ox as Number, oy as Number) as Void {
        var a = _m.arrow;
        if (a == null || a.size() < 96) {
            return;
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        for (var row = 0; row < 48; row++) {
            var y0 = oy + (row * 3) / 2;
            var y1 = oy + ((row + 1) * 3) / 2;
            var runStart = -1;
            for (var x = 0; x <= 48; x++) {
                var on = false;
                if (x < 32) {
                    on = ((a[row * 2] >> (31 - x)) & 1) == 1;
                } else if (x < 48) {
                    on = ((a[row * 2 + 1] >> (47 - x)) & 1) == 1;
                }
                if (on && runStart < 0) {
                    runStart = x;
                } else if (!on && runStart >= 0) {
                    var x0 = ox + (runStart * 3) / 2;
                    var x1 = ox + (x * 3) / 2;
                    dc.fillRectangle(x0, y0, x1 - x0, y1 - y0);
                    runStart = -1;
                }
            }
        }
    }

    private function drawFooter(dc as Graphics.Dc) as Void {
        if (_m.navActive && _m.eta.length() > 0) {
            drawCenteredPill(dc, 318, _m.eta);
        }
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(HALF, 360, Graphics.FONT_XTINY, "Google", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        if (_inFlight) {
            dc.setColor(0x1a73e8, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(HALF, 340, 4);
        }
    }

    private function drawCenteredPill(dc as Graphics.Dc, y as Number, text as String) as Void {
        var w = dc.getTextWidthInPixels(text, Graphics.FONT_TINY) + 24;
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(HALF - w / 2, y - 17, w, 34, 17);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(HALF, y, Graphics.FONT_TINY, text, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    private function drawDiag(dc as Graphics.Dc, lowPower as Boolean) as Void {
        var lines = [
            "fetch " + _fetchSecs.format("%.1f") + "s n" + _fetches + " err " + _lastErr,
            "img " + (_imgHalfW * 2) + " z" + _imgZoom + " sz" + SIZES[_sizeIdx],
            "spd " + (_m.speed * 3.6).format("%.0f") + " gps " + (_m.hasFix ? "ok" : "--"),
            "draw " + _updMs + "ms " + (lowPower ? "LOW" : "HIGH") + " lp" + _lowPowerFrames,
            "msgs " + _m.msgCount
        ];
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.fillRectangle(60, 140, 270, 5 * 22 + 8);
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

    // Up = zoom in, Down = zoom out
    function onPreviousPage() as Boolean {
        if (_v.zoomOffset < 3) { _v.zoomOffset++; }
        _v.maybeFetch();
        return true;
    }

    function onNextPage() as Boolean {
        if (_v.zoomOffset > -3) { _v.zoomOffset--; }
        _v.maybeFetch();
        return true;
    }

    // Start = diagnostics overlay
    function onSelect() as Boolean {
        _v.showDiag = !_v.showDiag;
        WatchUi.requestUpdate();
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
