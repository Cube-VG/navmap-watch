import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Math;
import Toybox.Position;
import Toybox.System;
import Toybox.WatchUi;

// NavMap does one thing: while Google Maps navigates on the phone, it shows the map with the next turn,
// and closes when the navigation ends. A plain watch app (no activity session): nothing is recorded,
// and leaving NavMap leaves the watch as it was.
class NavMapApp extends Application.AppBase {
    var model as NavModel;
    var link as NavLink;
    var exitRequested as Boolean = false;
    var closeWhy as String? = null;   // set as NavMap closes itself: "u" Exit chosen, "e" the navigation ended
    private var _drawAt as Number = -100000;   // System.getTimer() when the last frame was asked for
    private var _gpsAt as Number = -100000;    // and of the last GPS event
    private var _helloAt as Number = -100000;
    private var _hellos as Number = 0;
    private var _startedAt as Number = 0;
    private var _glance as Boolean = false;

    function initialize() {
        AppBase.initialize();
        model = new NavModel();
        link = new NavLink(model);
    }

    function onStart(state as Dictionary?) as Void {
        _startedAt = System.getTimer();
        // For NavBridge's log: Garmin closes an app opened from the glance list after a while without a button
        // press, and not one opened from the app list.
        if (state != null && state[:launchedFromGlance] == true) {
            _glance = true;
        }
        startGps();
        Communications.registerForPhoneAppMessages(method(:onPhone));
    }

    // Every satellite system the watch has (on the FR165: GPS, GLONASS, Galileo and BeiDou), not just GPS,
    // which is what Connect IQ uses by default. Garmin's manual: faster position acquisition than GPS only,
    // for a little more battery.
    private function startGps() as Void {
        if (Position has :hasConfigurationSupport && Position has :CONFIGURATION_GPS_GLONASS_GALILEO_BEIDOU_L1
                && Position.hasConfigurationSupport(Position.CONFIGURATION_GPS_GLONASS_GALILEO_BEIDOU_L1)) {
            try {
                Position.enableLocationEvents({
                    :acquisitionType => Position.LOCATION_CONTINUOUS,
                    :configuration => Position.CONFIGURATION_GPS_GLONASS_GALILEO_BEIDOU_L1
                }, method(:onPosition));
                System.println("gps: all satellite systems");
                return;
            } catch (e) {
                // Refused after all: GPS alone, as before.
            }
        }
        Position.enableLocationEvents(Position.LOCATION_CONTINUOUS, method(:onPosition));
        System.println("gps: GPS only");
    }

    function onStop(state as Dictionary?) as Void {
        Position.enableLocationEvents(Position.LOCATION_DISABLE, method(:onPosition));
        if (model.phoneAnswered) {
            // With how long NavMap ran and why it closes: with no "why", the watch closed it, and NavBridge asks it
            // to open NavMap again if Google Maps is still navigating.
            var bye = {"t" => "bye", "id" => link.session, "up" => (System.getTimer() - _startedAt) / 1000} as Dictionary<String, String or Number>;
            var why = closeWhy;
            if (why != null) {
                bye["why"] = why;
            }
            if (state != null && state[:suspend] == true) {
                bye["sus"] = 1;
            }
            send(bye);
        }
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        var view = new MapView(model);
        return [view, new MapDelegate(view)];
    }

    function onPosition(info as Position.Info) as Void {
        model.onPosition(info);
        _gpsAt = System.getTimer();
        frame();
    }

    // Asks for a new frame. The GPS, the 1 s timer, each turn answer and each map picture each asked for one,
    // and a frame redraws the whole turned map, so the watch drew it about three times a second (measured in
    // the simulator) though the position changes once. Now the GPS sets the pace: each fix is drawn at once,
    // and what comes in between shows with the next fix, within a second. Without GPS events the 1 s timer
    // draws. now is for what can't wait, such as the moment a turn buzzes.
    function redraw(now as Boolean) as Void {
        var t = System.getTimer();
        if (now || (t - _gpsAt > 1500 && t - _drawAt >= 900)) {
            frame();
        }
    }

    private function frame() as Void {
        _drawAt = System.getTimer();
        WatchUi.requestUpdate();
    }

    // Connect IQ phone messages: the second way NavBridge reaches the watch, besides NavLink.
    function onPhone(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data instanceof Dictionary && "where".equals(data["t"] as Object?)) {
            model.phoneAnswered = true;
            send(withPosition({"t" => "pos"}));
            return;
        }
        var urgent = model.onPhoneMessage(data);
        if (urgent) {
            model.alertUser();
        }
        redraw(urgent);
    }

    // Called every second. Says hello over Connect IQ until NavBridge answers either way.
    function tickPhone() as Void {
        if (model.phoneAnswered || !System.getDeviceSettings().phoneConnected) {
            return;
        }
        var now = System.getTimer();
        if (now - _helloAt < (_hellos < 6 ? 5000 : 30000)) {
            return;
        }
        _helloAt = now;
        _hellos++;
        // "kh": a fingerprint of the pairing token. Coming through Garmin Connect, it lets NavBridge pair this NavMap
        // in place of one paired before, e.g. after a reinstall, without the user tapping Pair.
        var hello = withPosition({"t" => "hello", "v" => 2, "kh" => Keys.tokenHash()});
        if (_glance) {
            hello["g"] = 1;
        }
        send(hello);
    }

    private function withPosition(msg as Dictionary<String, String or Number>) as Dictionary<String, String or Number> {
        var lat = model.lat;
        var lon = model.lon;
        // As in NavLink: only once the user has agreed in NavBridge, which is when it hands over the key.
        if (lat != null && lon != null && Keys.mapsKey() != null) {
            msg["la"] = Math.round(lat * 100000.0d).toNumber();
            msg["lo"] = Math.round(lon * 100000.0d).toNumber();
        }
        return msg;
    }

    // The Connect IQ simulator (SDK 9.1 and 9.2) segfaults on any transmit over its phone link
    // (Garmin bug CIQQA-4579), so the simulator build (tools/build-watch.sh fr165 --sim,
    // watch/sim.jungle) never transmits. NavLink works there as on the watch.
    (:noTransmit)
    private function send(msg as Dictionary<String, String or Number>) as Void {
    }

    (:transmit)
    private function send(msg as Dictionary<String, String or Number>) as Void {
        try {
            Communications.transmit(msg as Dictionary<Communications.TransmitKeyType, Communications.TransmitType>, null, new PhoneListener());
        } catch (e) {
            // Phone gone; the hello is repeated once it's back.
        }
    }
}

class PhoneListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() as Void {
    }

    function onError() as Void {
        // Phone app not installed or not connected; NavLink keeps asking on its own.
    }
}

function getApp() as NavMapApp {
    return Application.getApp() as NavMapApp;
}
