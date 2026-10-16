import Toybox.Communications;
import Toybox.Lang;
import Toybox.Math;
import Toybox.PersistedContent;
import Toybox.System;
import Toybox.WatchUi;

// NavBridge answers on the phone itself. Garmin Connect runs the watch's web requests on the phone, and
// plain HTTP is allowed to 127.0.0.1, so this is the same path the map pictures take. It doesn't depend
// on Connect IQ phone messages, which Garmin Connect for Android stopped delivering (bug CIQQA-4631).
const NAV_URL = "http://127.0.0.1:18165/nav";

// Asks NavBridge for the current turn every 2 s while navigating, every second in the last 500 m before a
// turn, every 5 s otherwise.
class NavLink {
    var fails as Number = 0;   // requests in a row that got no answer

    private var _m as NavModel;
    private var _inFlight as Boolean = false;
    private var _sentAt as Number = 0;
    private var _nextAt as Number = 0;

    function initialize(model as NavModel) {
        _m = model;
    }

    // Called every second.
    function tick() as Void {
        var now = System.getTimer();
        if (_inFlight) {
            if (now - _sentAt < 15000) {
                return;
            }
            _inFlight = false;   // the answer was lost; ask again
        }
        if (now < _nextAt || !System.getDeviceSettings().phoneConnected) {
            return;
        }
        var params = {"k" => NAVBRIDGE_TOKEN, "ah" => _m.arrowHash.toString(), "rv" => _m.routeVersion.toString(), "c" => "1"};
        var lat = _m.lat;
        var lon = _m.lon;
        if (lat != null && lon != null) {
            params["la"] = Math.round(lat * 100000.0d).toNumber().toString();
            params["lo"] = Math.round(lon * 100000.0d).toNumber().toString();
        }
        _inFlight = true;
        _sentAt = now;
        try {
            Communications.makeWebRequest(NAV_URL, params, {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
            }, method(:onAnswer));
        } catch (e) {
            _inFlight = false;
            _nextAt = now + 5000;
        }
    }

    function onAnswer(code as Number, data as Dictionary or String or PersistedContent.Iterator or Null) as Void {
        _inFlight = false;
        if (code == 200 && data instanceof Dictionary) {
            if (fails > 0 || !_m.phoneAnswered) {
                System.println("nav answer");
            }
            fails = 0;
            _m.lagMs = (System.getTimer() - _sentAt) / 2;
            if (_m.onPhoneMessage(data)) {
                _m.alertUser();
            }
            // Counted from the request, less a little, so the 1 s timer that sends it doesn't skip a beat.
            var near = _m.liveMeters >= 0 && _m.liveMeters <= 500;
            _nextAt = _sentAt + (_m.navActive ? (near ? 1000 : 2000) : 5000) - 300;
        } else {
            fails++;
            if (fails <= 3) {
                System.println("nav " + code);
            }
            _nextAt = System.getTimer() + 5000;
        }
        WatchUi.requestUpdate();
    }
}
