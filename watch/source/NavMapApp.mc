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
    private var _helloAt as Number = -100000;
    private var _hellos as Number = 0;

    function initialize() {
        AppBase.initialize();
        model = new NavModel();
        link = new NavLink(model);
    }

    function onStart(state as Dictionary?) as Void {
        Position.enableLocationEvents(Position.LOCATION_CONTINUOUS, method(:onPosition));
        Communications.registerForPhoneAppMessages(method(:onPhone));
    }

    function onStop(state as Dictionary?) as Void {
        Position.enableLocationEvents(Position.LOCATION_DISABLE, method(:onPosition));
        if (model.phoneAnswered) {
            send({"t" => "bye"});
        }
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return [new MapView(model), new MapDelegate()];
    }

    function onPosition(info as Position.Info) as Void {
        model.onPosition(info);
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
        if (model.onPhoneMessage(data)) {
            model.alertUser();
        }
        WatchUi.requestUpdate();
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
        send(withPosition({"t" => "hello", "v" => 2}));
    }

    private function withPosition(msg as Dictionary<String, String or Number>) as Dictionary<String, String or Number> {
        var lat = model.lat;
        var lon = model.lon;
        if (lat != null && lon != null) {
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
