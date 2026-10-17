import Toybox.Attention;
import Toybox.Lang;
import Toybox.Position;
import Toybox.System;

// Shared state: watch GPS fix plus the navigation instruction relayed from the phone.
class NavModel {
    // GPS
    var lat as Double? = null;
    var lon as Double? = null;
    var heading as Float = 0.0;   // radians, 0 = north, clockwise
    var speed as Float = 0.0;     // m/s
    var hasFix as Boolean = false;

    // Navigation (from NavBridge on the phone)
    var navActive as Boolean = false;
    var distText as String = "";
    var distMeters as Number = -1;
    // The same distance counted down with the watch's own GPS between NavBridge's answers, which come
    // every 1-2 s and take a moment over Bluetooth: at 40 km/h that is 20-30 m.
    var liveMeters as Number = -1;
    var liveText as String = "";
    var lagMs as Number = 0;                   // half the round trip of the answer being read
    var instr as String = "";
    var eta as String = "";
    var arrowRects as Array<Number>? = null;   // [x, y, w, h, ...] at 1.5x, built once per arrow
    var arrowHash as Number = 0;
    var route as Array<Number>? = null;        // flat [lat*1e5, lon*1e5, ...]
    var routeVersion as Number = 0;            // NavBridge's number for the route above; 0 = none
    var endAt as Number? = null;               // System.getTimer() when "end" arrived
    var endText as String = "";
    var phoneAnswered as Boolean = false;
    var consentNeeded as Boolean = false;     // NavBridge answered {"t":"consent"}: the user hasn't agreed there

    // Two buzzes per turn, timed by seconds to the turn at the current speed, not fixed metres: one long
    // buzz about SOON_S before ("turn coming"), three short strong ones about NOW_S before ("turn now").
    // A turn first seen too close for both gets only "turn now". No buzz within 5 s of the last one.
    private const SOON_S = 25;
    private const NOW_S = 6;
    var turnNow as Boolean = false;              // "turn now" reached: the banner turns blue
    // From this distance the screen is kept lit until the turn, for at most HOLD_MS: AMOLED burn-in
    // protection refuses to keep it on for much more than a minute.
    private const HOLD_METERS = 120;
    private const HOLD_MS = 40000;
    private var _holdStarted as Boolean = false;   // for the current turn
    private var _holdUntil as Number = 0;
    private var _litAt as Number = 0;
    private var _fired as Number = 0;              // this turn: 1 = "coming" reached, 2 = "now" reached
    private var _buzz as Number = 1;               // the pattern alertUser() plays: 1 coming, 2 now
    private var _buzzAt as Number = -5000;
    private var _odo as Float = 0.0;               // metres travelled, from GPS speed
    private var _odoAt as Number = 0;              // System.getTimer() of the fix that last added to _odo
    private var _baseOdo as Float = 0.0;           // _odo when Google's distance last changed
    private const MAX_COUNTDOWN = 150;             // never more than this below Google's own figure
    private var _lowQuality as Number = -2;
    private const ROUTE_STEP = 600;               // route characters decoded per step
    private var _pBytes as Array<Number>? = null; // route text being decoded, and where decodeStep() is
    private var _pOut as Array<Number>? = null;
    private var _pAt as Number = 0;
    private var _pK as Number = 0;
    private var _pV as Number = 0;
    private var _pShift as Number = 0;
    private var _pLat as Number = 0;
    private var _pLon as Number = 0;   // last GPS quality logged as too low, so the log isn't flooded

    function initialize() {
        var info = Position.getInfo();
        var pos = info.position;
        var acc = info.accuracy;
        // Only trust a recent, usable fix; otherwise wait for the first GPS event.
        if (pos != null && acc != null && acc >= Position.QUALITY_POOR) {
            var deg = pos.toDegrees();
            lat = deg[0].toDouble();
            lon = deg[1].toDouble();
        }
    }

    function onPosition(info as Position.Info) as Void {
        var pos = info.position;
        var acc = info.accuracy;
        if (pos == null || acc == null || acc < Position.QUALITY_POOR) {
            var q = -1;
            if (acc != null) {
                q = acc as Number;
            }
            if (q != _lowQuality) {
                _lowQuality = q;
                System.println("gps quality " + q + " too low (needs 2 or more), waiting");
            }
            return;
        }
        var deg = pos.toDegrees();
        lat = deg[0].toDouble();
        lon = deg[1].toDouble();
        if (!hasFix) {
            System.println("gps fix " + lat + "," + lon);
        }
        hasFix = true;
        if (info.speed != null) {
            speed = info.speed as Float;
        }
        // Standing still, GPS speed jitters around a few tenths of a m/s: that isn't distance travelled.
        var now = System.getTimer();
        if (_odoAt != 0 && now - _odoAt < 5000 && speed > 1.0) {
            _odo += speed * (now - _odoAt) / 1000.0;
        }
        _odoAt = now;
        // Heading is direction of travel when moving; freeze it when slow so the arrow doesn't spin.
        if (info.heading != null && speed > 1.0) {
            heading = info.heading as Float;
        }
    }

    // The same messages come from the /nav answer and from Connect IQ phone messages.
    // Returns true when it is time to buzz: see updateLive().
    function onPhoneMessage(data as Object?) as Boolean {
        if (!(data instanceof Dictionary)) {
            return false;
        }
        phoneAnswered = true;
        var t = data["t"] as Object?;
        // The user hasn't agreed in NavBridge to the terms and to the use of their location, or took it back:
        // forget the key (so no map) and the route line, and stop sending the position, until NavBridge
        // answers normally again.
        consentNeeded = t != null && "consent".equals(t);
        if (consentNeeded) {
            Keys.clearMapsKey();
            navActive = false;
            route = null;
            routeVersion = 0;
            _pBytes = null;
            return false;
        }
        // NavBridge's Google Maps key for the map pictures (see Keys), sent when the watch has none or an old one.
        var mk = data["mk"] as Object?;
        if (mk instanceof String && mk.length() > 0) {
            Keys.setMapsKey(mk);
        }
        var line = data["n"] as Object?;
        if (line instanceof String) {
            // Compact answer (asked for with c=1): "dm|ah|rv|d|e|i", see tools/navcodec.py.
            var f = splitLine(line, 5);
            if (f == null) {
                return false;
            }
            var ah = f[1].toNumber();
            return onNav(f[0].toNumber(), ah != null ? ah : arrowHash, f[2].toNumber(), f[3], f[4], f[5], data);
        } else if (t != null && "nav".equals(t)) {
            return onNav(data["dm"] instanceof Number ? data["dm"] as Number : null,
                data["ah"] instanceof Number ? data["ah"] as Number : arrowHash,
                data["rv"] instanceof Number ? data["rv"] as Number : null, str(data["d"] as Object?), str(data["e"] as Object?), str(data["i"] as Object?), data);
        } else if (t != null && "route".equals(t)) {
            if (data["p"] instanceof Array) {
                setRoute(data["p"] as Array<Number>);
            }
        } else if (t != null && "end".equals(t)) {
            // Only a navigation this NavMap was showing ends it; opened later, NavMap just waits.
            if (!navActive) {
                return false;
            }
            navActive = false;
            route = null;
            _pBytes = null;   // and drop a route still being decoded
            endAt = System.getTimer();
            endText = data["ar"] == true ? "Arrived" : "Navigation ended";
            light();       // no buzz: "turn now" already announced the arrival
            return false;
        } else if (t != null && "idle".equals(t)) {
            navActive = false;
        }
        return false;
    }

    private function onNav(dm as Number?, newHash as Number, rv as Number?, d as String, e as String,
            newInstr as String, data as Dictionary) as Boolean {
        var newMeters = dm != null ? dm : -1;
        if (!navActive || !newInstr.equals(instr) || newHash != arrowHash) {
            _holdStarted = false;   // a new turn gets its own lit time and buzzes
            _fired = 0;
            turnNow = false;
            distMeters = -2;        // and the countdown restarts below
        }
        // Count down from Google's figure only when it changes: it repeats the same one for a while.
        // It was already lagMs old on arrival.
        if (newMeters != distMeters) {
            _baseOdo = odoNow() - speed * lagMs / 1000.0;
        }
        lagMs = 0;
        navActive = true;
        endAt = null;
        instr = newInstr;
        distText = d;
        distMeters = newMeters;
        var alert = updateLive();
        eta = e;
        // No arrow means keep the current one; "" or [] clears it.
        var a = data["A"] as Object?;
        if (a instanceof String) {
            arrowRects = buildArrowRuns(a);
        } else if (data["a"] instanceof Array) {
            arrowRects = buildArrow(data["a"] as Array);
        }
        arrowHash = newHash;
        // The answer carries the route only when it differs from routeVersion.
        var p = data["P"] as Object?;
        if (rv != null && p instanceof String) {
            startRoute(p);
            routeVersion = rv;
        } else if (rv != null && data["p"] instanceof Array) {
            setRoute(data["p"] as Array<Number>);
            routeVersion = rv;
        }
        return alert;
    }

    private function odoNow() as Float {
        var ms = System.getTimer() - _odoAt;
        return _odo + (hasFix && speed > 1.0 && ms < 2000 ? speed * ms / 1000.0 : 0.0);
    }

    // Called every second and with each answer: works out liveMeters and liveText. Returns true when it
    // is time to buzz.
    function updateLive() as Boolean {
        if (distMeters < 0) {
            liveMeters = -1;
            liveText = distText;
            return false;
        }
        var gone = (odoNow() - _baseOdo).toNumber();
        gone = gone < 0 ? 0 : (gone > MAX_COUNTDOWN ? MAX_COUNTDOWN : gone);
        var live = distMeters > gone ? distMeters - gone : 0;
        liveMeters = live;
        liveText = liveDistText(live);
        // Seconds to the turn. Standing still never gets there (only the last 15 m do); without a fix, assume 4 m/s.
        var v = hasFix ? speed : 4.0;
        var secs = v > 1.0 ? live / v : 999.0;
        var stage = 0;
        if (_fired < 2 && (secs <= NOW_S || live <= 15)) {
            stage = 2;
            turnNow = true;
        } else if (_fired < 1 && secs <= SOON_S && secs > NOW_S + 5) {
            stage = 1;
        }
        if (stage == 0) {
            return false;
        }
        _fired = stage;
        return buzz(stage);
    }

    // True if pattern p may play now: not within 5 s of the last buzz.
    private function buzz(p as Number) as Boolean {
        var now = System.getTimer();
        if (now - _buzzAt < 5000) {
            return false;
        }
        _buzzAt = now;
        _buzz = p;
        return true;
    }

    // Google's text with the counted-down number: "350 m" becomes "320 m". Only whole numbers of metres,
    // yards or feet are rewritten; "1.2 km" and "0.3 mi" stay as Google wrote them.
    private function liveDistText(live as Number) as String {
        if (live == distMeters || distMeters <= 0) {
            return distText;
        }
        var chars = distText.toCharArray();
        var n = 0;
        var k = 0;
        var ch = 0;
        while (k < chars.size()) {
            ch = chars[k].toNumber();
            if (ch < 48 || ch > 57) {   // 0-9
                break;
            }
            n = n * 10 + ch - 48;
            k++;
        }
        if (k == 0 || (k < chars.size() && (ch == 46 || ch == 44))) {   // "1.2 km", "1,000 ft"
            return distText;
        }
        var ratio = n.toFloat() / distMeters;   // 1 for metres, 1.09 yards, 3.28 feet, 0.001 km
        if (ratio < 0.5) {
            return distText;
        }
        var step = ratio > 2 ? 50 : 10;
        var v = ((live * ratio / step) + 0.5).toNumber() * step;
        return v.toString() + distText.substring(k, distText.length());
    }

    // "a|b|c": the first n fields and the rest (which may itself hold '|').
    private function splitLine(s as String, n as Number) as Array<String>? {
        var out = [] as Array<String>;
        for (var k = 0; k < n; k++) {
            var at = s.find("|");
            if (at == null) {
                return null;
            }
            out.add(s.substring(0, at) as String);
            s = s.substring(at + 1, s.length()) as String;
        }
        out.add(s);
        return out;
    }

    // "P": a Google encoded polyline in 1e-5 degrees. Decoding is a few steps per character, and the watch
    // stops an app that runs too long at once, so a long route is read ROUTE_STEP characters at a time:
    // one slice now, the rest one per second from decodeStep(). The old line stays until the new is whole.
    private function startRoute(p as String) as Void {
        var b = p.toUtf8Array();
        _pBytes = b;
        _pAt = 0;
        _pOut = new [b.size()] as Array<Number>;   // a number takes at least one character
        _pK = 0;
        _pV = 0;
        _pShift = 0;
        _pLat = 0;
        _pLon = 0;
        decodeStep();
    }

    function decodeStep() as Void {
        var b = _pBytes;
        var out = _pOut;
        if (b == null || out == null) {
            return;
        }
        var end = _pAt + ROUTE_STEP < b.size() ? _pAt + ROUTE_STEP : b.size();
        var k = _pK;
        var v = _pV;
        var shift = _pShift;
        for (var i = _pAt; i < end; i++) {
            var c = b[i] - 63;
            v |= (c & 31) << shift;
            shift += 5;
            if (c < 32) {   // no continuation bit: this number is whole
                var delta = (v & 1) != 0 ? ~(v >> 1) : v >> 1;
                if (k % 2 == 0) {
                    _pLat += delta;
                    out[k] = _pLat;
                } else {
                    _pLon += delta;
                    out[k] = _pLon;
                }
                k++;
                v = 0;
                shift = 0;
            }
        }
        _pAt = end;
        _pK = k;
        _pV = v;
        _pShift = shift;
        if (end < b.size()) {
            return;
        }
        k -= k % 2;
        route = k >= 4 ? out.slice(0, k) : null;
        System.println("route " + b.size() + " chars, " + k / 2 + " points" +
            (k >= 4 ? ", ends " + out[0] + "," + out[1] + " " + out[k - 2] + "," + out[k - 1] : ""));
        _pBytes = null;
        _pOut = null;
    }

    // "A": blocks of identical rows, each: rows, runs, then x0 x1 per run, all +63 (tools/navcodec.py).
    // Already runs, so this is just scaling to 1.5x; a block of rows is one rectangle per run.
    private function buildArrowRuns(s as String) as Array<Number>? {
        var b = s.toUtf8Array();
        var rects = [] as Array<Number>;
        var row = 0;
        var i = 0;
        while (i + 1 < b.size()) {
            var y0 = (row * 3) / 2;
            row += b[i] - 63;
            var y1 = (row * 3) / 2;
            var runs = b[i + 1] - 63;
            i += 2;
            for (var r = 0; r < runs && i + 1 < b.size(); r++) {
                var x0 = ((b[i] - 63) * 3) / 2;
                rects.addAll([x0, y0, ((b[i + 1] - 63) * 3) / 2 - x0, y1 - y0]);
                i += 2;
            }
        }
        return rects.size() > 0 ? rects : null;
    }

    private function setRoute(p as Array<Number>) as Void {
        _pBytes = null;
        route = p.size() >= 4 ? p : null;
    }

    // Arrow rows: 48 rows, 2 ints each. Int 0 holds x=0..31 (bit 31 = x0), int 1 holds x=32..47 (bit 15 = x32).
    // Turned into filled runs once here, so each redraw is a handful of rectangles.
    private function buildArrow(a as Array) as Array<Number>? {
        if (a.size() < 96) {
            return null;
        }
        var rects = [] as Array<Number>;
        for (var row = 0; row < 48; row++) {
            var w0 = a[row * 2] instanceof Number ? a[row * 2] as Number : 0;
            var w1 = a[row * 2 + 1] instanceof Number ? a[row * 2 + 1] as Number : 0;
            var y0 = (row * 3) / 2;
            var y1 = ((row + 1) * 3) / 2;
            var runStart = -1;
            for (var x = 0; x <= 48; x++) {
                var on = false;
                if (x < 32) {
                    on = ((w0 >> (31 - x)) & 1) == 1;
                } else if (x < 48) {
                    on = ((w1 >> (47 - x)) & 1) == 1;
                }
                if (on && runStart < 0) {
                    runStart = x;
                } else if (!on && runStart >= 0) {
                    var x0 = (runStart * 3) / 2;
                    rects.addAll([x0, y0, (x * 3) / 2 - x0, y1 - y0]);
                    runStart = -1;
                }
            }
        }
        return rects;
    }

    // "Turn coming" (one long buzz) or "turn now" (three short strong ones): buzz and light the screen.
    function alertUser() as Void {
        if (Attention has :vibrate) {
            var p = [new Attention.VibeProfile(60, 500)];
            if (_buzz == 2) {
                var on = new Attention.VibeProfile(100, 150);
                var off = new Attention.VibeProfile(0, 100);
                p = [on, off, on, off, on];
            }
            Attention.vibrate(p);
        }
        light();
    }

    // Called every second: close to a turn, keeps the screen lit so the map is there when you look.
    function keepAwake() as Void {
        if (!navActive || liveMeters < 0 || liveMeters > HOLD_METERS) {
            return;
        }
        var now = System.getTimer();
        if (!_holdStarted) {
            _holdStarted = true;
            _holdUntil = now + HOLD_MS;
        }
        if (now < _holdUntil && now - _litAt >= 5000) {
            light();
        }
    }

    private function light() as Void {
        if (!(Attention has :backlight)) {
            return;
        }
        _litAt = System.getTimer();
        try {
            Attention.backlight(true);
        } catch (e) {
            // Burn-in protection: the screen was lit too long. Let it dim until the next turn.
            _holdUntil = 0;
        }
    }

    private function str(v as Object?) as String {
        return v == null ? "" : v.toString();
    }
}
