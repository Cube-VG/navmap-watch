import Toybox.Application;
import Toybox.Cryptography;
import Toybox.Lang;
import Toybox.StringUtil;

// Nothing secret is built into NavMap, so one .prg works for everyone. The first time it runs, the watch
// makes up its own token and sends it as "k" with every /nav request. NavBridge remembers the first token it
// sees while pairing is open, then answers only that one. NavBridge then hands over the Google Maps key that
// the map pictures need ("mk" in a /nav answer), and the watch keeps it.
module Keys {
    var _token as String? = null;
    var _mapsKey as String? = null;

    // 32 hex characters, made once and kept in the app's storage.
    function token() as String {
        var t = _token;
        if (t != null) {
            return t;
        }
        var kept = Application.Storage.getValue("tok");
        if (kept instanceof String && kept.length() == 32) {
            t = kept;
        } else {
            t = StringUtil.convertEncodedString(Cryptography.randomBytes(16), {
                :fromRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
                :toRepresentation => StringUtil.REPRESENTATION_STRING_HEX
            }) as String;
            Application.Storage.setValue("tok", t);
        }
        _token = t;
        return t;
    }

    // The Google Maps key NavBridge last sent, or null until NavBridge has sent one.
    function mapsKey() as String? {
        if (_mapsKey == null) {
            var kept = Application.Storage.getValue("mk");
            if (kept instanceof String && kept.length() > 0) {
                _mapsKey = kept;
            }
        }
        return _mapsKey;
    }

    // The key's last 6 characters, sent with every /nav request, so NavBridge sends the key only when the
    // watch has none or an old one. "none" rather than "", which might not survive as a query value.
    function mapsKeyTail() as String {
        var k = mapsKey();
        if (k == null) {
            return "none";
        }
        if (k.length() <= 6) {
            return k;
        }
        var tail = k.substring(k.length() - 6, k.length());
        return tail != null ? tail : "";
    }

    function setMapsKey(k as String) as Void {
        if (!k.equals(_mapsKey)) {
            _mapsKey = k;
            Application.Storage.setValue("mk", k);
        }
    }
}
