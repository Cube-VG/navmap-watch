import Toybox.Attention;
import Toybox.Lang;
import Toybox.Math;
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
    var instr as String = "";
    var eta as String = "";
    var arrowRects as Array<Number>? = null;   // [x, y, w, h, ...] at 1.5x, built once per arrow
    var arrowHash as Number = 0;
    var route as Array<Number>? = null;        // flat [lat*1e5, lon*1e5, ...]
    var endAt as Number? = null;               // System.getTimer() when "end" arrived
    var endText as String = "";
    var msgCount as Number = 0;
    var phoneAnswered as Boolean = false;
    var hellos as Number = 0;

    private const ALERT_METERS = [400, 150, 50];

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
            return;
        }
        var deg = pos.toDegrees();
        lat = deg[0].toDouble();
        lon = deg[1].toDouble();
        hasFix = true;
        if (info.speed != null) {
            speed = info.speed as Float;
        }
        // Heading is direction of travel when moving; freeze it when slow so the arrow doesn't spin.
        if (info.heading != null && speed > 1.0) {
            heading = info.heading as Float;
        }
    }

    // Returns true when a new maneuver arrived or an alert distance was crossed.
    function onPhoneMessage(data as Object?) as Boolean {
        if (!(data instanceof Dictionary)) {
            return false;
        }
        msgCount++;
        phoneAnswered = true;
        var t = data["t"] as Object?;
        if (t != null && "nav".equals(t)) {
            var alert = false;
            var newInstr = str(data["i"] as Object?);
            var newHash = data["ah"] instanceof Number ? data["ah"] as Number : arrowHash;
            if (!navActive || !newInstr.equals(instr) || newHash != arrowHash) {
                alert = true;
            }
            var newMeters = data["dm"] instanceof Number ? data["dm"] as Number : -1;
            if (distMeters > 0 && newMeters >= 0) {
                for (var i = 0; i < ALERT_METERS.size(); i++) {
                    var m = ALERT_METERS[i];
                    if (distMeters > m && newMeters <= m) {
                        alert = true;
                    }
                }
            }
            navActive = true;
            endAt = null;
            instr = newInstr;
            distText = str(data["d"] as Object?);
            distMeters = newMeters;
            eta = str(data["e"] as Object?);
            // No "a" means keep the current arrow; "a":[] clears it.
            if (data["a"] instanceof Array) {
                arrowRects = buildArrow(data["a"] as Array);
            }
            arrowHash = newHash;
            return alert;
        } else if (t != null && "route".equals(t)) {
            if (data["p"] instanceof Array) {
                route = data["p"] as Array<Number>;
            }
        } else if (t != null && "end".equals(t)) {
            // The phone already waited a few seconds for a reroute before sending this.
            navActive = false;
            route = null;
            endAt = System.getTimer();
            endText = data["ar"] == true ? "Arrived" : "Navigation ended";
            return true;
        } else if (t != null && "idle".equals(t)) {
            navActive = false;
        }
        return false;
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

    function alertUser() as Void {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(80, 350), new Attention.VibeProfile(0, 150), new Attention.VibeProfile(80, 350)]);
        }
        if (Attention has :backlight) {
            try {
                // Wakes the screen for the turn.
                Attention.backlight(true);
            } catch (e) {
                // AMOLED burn-in protection limits how long the backlight may be forced on.
            }
        }
    }

    private function str(v as Object?) as String {
        return v == null ? "" : v.toString();
    }
}
