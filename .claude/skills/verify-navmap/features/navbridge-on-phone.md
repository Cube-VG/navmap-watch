# NavBridge on a phone

NavBridge is the Android app (`com.cubevg.navbridge`). Its main screen lets the rider agree to the terms, save the Google Maps key, pick how they travel, start "Simulate navigation", "Open NavMap on the watch", "Pair again" and read the log. It reads Google Maps' turn-by-turn notification and serves `/nav` on the phone's 127.0.0.1:18165.

## Sub-features

- `install`: `dist/NavBridge.apk` installs over the rider's copy, which needs the same signing key.
- `simulate`: "Simulate navigation" plays a demo ride on `/nav` and opens NavMap on a paired watch.
- `log`: the in-app log under "Log", also on logcat with the tag `NavBridge`.
- `share`: sharing a place from Google Maps to "Navigate with NavMap" computes a route line.

## How to get to it (user POV)

- Open NavBridge from the launcher.
- In Google Maps, open a place, tap Share, then "Navigate with NavMap".

## Driving it with navmap-verify

Preconditions:

- A phone is attached and authorised. Run `adb devices`, which must list exactly one device. This server has no phone by default. The rider's own phone connects over wireless debugging, and it only appears while they turn that on.
- `tools/build-phone.sh` passed, and `dist/NavBridge.apk` exists.
- The rider has said this run may use their phone.

- **Install.** Run `adb install -r dist/NavBridge.apk`. Expect `Success`. A signature mismatch means this APK isn't signed with `~/.config/navmap/navbridge-release.jks`. Stop there, and never uninstall to get round it.
- **Open.** Run `adb shell am start -n com.cubevg.navbridge/.MainActivity`, then `adb exec-out screencap -p > verify-out/<run>/phone-main.png`.
- **Find a button by its text.** Run `adb exec-out uiautomator dump /dev/tty`, find the node whose `text` is the label from `android/app/src/main/res/values/strings.xml` (`Simulate navigation`, `Stop simulation`, `Pair again`), and tap the centre of its `bounds` with `adb shell input tap <x> <y>`.
- **Simulate.** Tap `Simulate navigation`. Read `adb logcat -d -s NavBridge | tail`, take another screencap, and tap `Stop simulation` when done.

Not yet proven with this skill: no phone was attached on 2026-10-28.

## Gotchas

- This is the rider's real phone. "Simulate navigation" asks the watch to open NavMap, and "Pair again" unpairs their watch. Don't tap either unless the rider asked for it.
- Never curl the phone's `/nav` (through `adb forward tcp:18165 tcp:18165`) with a made-up token while pairing is open. NavBridge keeps the first well-formed token it sees, which would replace the rider's watch.
- Never `adb uninstall` NavBridge. It drops the consent, the key and the pairing.
- The notification listener and Google Maps' real notifications can't be faked from adb. Proving a real navigation needs the rider to ride.
