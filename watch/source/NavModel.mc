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
    var arrow as Array<Number>? = null;   // 48 rows x 2 ints, see drawArrow
    var arrowHash as Number = 0;
    var route as Array<Number>? = null;   // flat [lat*1e5, lon*1e5, ...]
    var endAt as Number? = null;          // System.getTimer() when "end" arrived
    var msgCount as Number = 0;

    private const ALERT_METERS = [400, 150, 40];

    function initialize() {
        var info = Position.getInfo();
        var pos = info != null ? info.position : null;
        if (pos != null) {
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
            if (data["a"] instanceof Array) {
                arrow = data["a"] as Array<Number>;
            }
            arrowHash = newHash;
            return alert;
        } else if (t != null && "route".equals(t)) {
            if (data["p"] instanceof Array) {
                route = data["p"] as Array<Number>;
            }
        } else if (t != null && "end".equals(t)) {
            endAt = System.getTimer();
        }
        return false;
    }

    function alertUser() as Void {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(80, 350), new Attention.VibeProfile(0, 150), new Attention.VibeProfile(80, 350)]);
        }
        if (Attention has :backlight) {
            try {
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
