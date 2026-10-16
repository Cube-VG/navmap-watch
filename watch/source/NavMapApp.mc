import Toybox.Activity;
import Toybox.ActivityRecording;
import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Position;
import Toybox.System;
import Toybox.WatchUi;

class NavMapApp extends Application.AppBase {
    var model as NavModel;
    var view as MapView?;
    var exitRequested as Boolean = false;
    private var _session as ActivityRecording.Session?;

    function initialize() {
        AppBase.initialize();
        model = new NavModel();
    }

    function onStart(state as Dictionary?) as Void {
        Position.enableLocationEvents(Position.LOCATION_CONTINUOUS, method(:onPosition));
        Communications.registerForPhoneAppMessages(method(:onPhone));
        // Running as an activity lets the watch's "During Activity: Always On" setting keep the screen on.
        // The session is never saved.
        if (Toybox has :ActivityRecording) {
            try {
                _session = ActivityRecording.createSession({:name => "NavMap", :sport => Activity.SPORT_MOTORCYCLING});
                _session.start();
            } catch (e) {
                _session = null;
            }
        }
        Communications.transmit({"t" => "hello"}, null, new PhoneListener());
    }

    function onStop(state as Dictionary?) as Void {
        Position.enableLocationEvents(Position.LOCATION_DISABLE, method(:onPosition));
        var s = _session;
        if (s != null) {
            if (s.isRecording()) {
                s.stop();
            }
            s.discard();
            _session = null;
        }
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        var v = new MapView(model);
        view = v;
        return [v, new MapDelegate(v)];
    }

    function onPosition(info as Position.Info) as Void {
        model.onPosition(info);
        WatchUi.requestUpdate();
    }

    function onPhone(msg as Communications.PhoneAppMessage) as Void {
        if (model.onPhoneMessage(msg.data)) {
            model.alertUser();
        }
        WatchUi.requestUpdate();
    }
}

class PhoneListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() as Void {
    }

    function onError() as Void {
        // Phone app not installed or not connected; the map still works on its own.
    }
}

function getApp() as NavMapApp {
    return Application.getApp() as NavMapApp;
}
