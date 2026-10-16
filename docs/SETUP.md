# Setting up NavMap

NavMap has two parts: the **NavMap watch app** on the Forerunner 165 and the **NavBridge phone app** on Android. NavBridge reads Google Maps' navigation notification, and NavMap fetches each turn from it through Garmin Connect. Garmin Connect must be installed and paired with the watch.

## 1. Install the watch app
1. Plug the watch into the computer. On a Mac, open it with [OpenMTP](https://openmtp.ganeshrvel.com/).
2. Copy `NavMap-FR165-v<version>.prg` (or `NavMap-FR165-Music-v<version>.prg` for the Music model) into **GARMIN → Apps**. Use watch version 0.5 or later with NavBridge 0.4 or later: they talk a new way.
3. Unplug the watch. NavMap appears in the activity list (press Start on the watch face).

## 2. Install NavBridge
1. On the phone, download `NavBridge.apk` and open it.
2. If Android asks, allow your browser or Files app to **install unknown apps**.
3. If Play Protect warns about an unknown developer, tap **More details → Install anyway**.

## 3. Set up NavBridge
Open NavBridge and work down the **Setup** list. Each step turns green when it's done.

| Step | Why |
|---|---|
| **Notification access** | Lets NavBridge read Google Maps' turn-by-turn notification. |
| **Switch greyed out?** | Android blocks this switch for apps installed from a file. Open **App info → ⋮ → Allow restricted settings**, then turn on notification access again. |
| **Notifications** | For the reminder when the watch needs a button press. |
| **Battery: NavBridge** | Set to **Unrestricted** so Android doesn't stop it mid-ride. |
| **Battery: Garmin Connect** | Also **Unrestricted**, so it keeps talking to the watch. |
| **Location (optional)** | Lets the route line start from the phone's position before the watch has GPS. |

Under **I travel by**, pick Motorbike, Car, Bicycle or Walking. It sets the route line's travel mode.

## 4. Test without riding
Tap **Simulate navigation**. The watch shows a prompt to open NavMap. Press Start (top right) to accept. A demo ride appears with turn arrows, distances and an ETA. It runs through the same code path as real Google Maps navigation.

## 5. Ride
- **Turns only:** start navigation in Google Maps as usual. The watch asks to open NavMap. Press Start once and the turns appear.
- **Turns and the blue route line:** in Google Maps, open the destination, tap **Share** and pick **Navigate with NavMap**. NavBridge works out the route and starts Google Maps navigation for you.

The watch vibrates and lights up at each new turn and at 400, 150 and 50 m, and stays lit for the last 120 m before a turn. In between, the screen dims to the next turn's arrow and distance; raise your wrist or press a button to see the map. When you arrive it shows "Arrived" and closes.

For the longest bright screen, on the watch hold **Up → System → Display → General Use**: set **Timeout** to the longest value and turn **Gesture** on, and leave **Always On Display** on.

## Troubleshooting
- **Nothing on the watch:**
  1. Open NavBridge and check that every Setup step is green.
  2. Check that the Watch card shows the watch as connected.
  3. Check that the **Log** has a line for the Google Maps notification.
- **"Press the button on the watch":** Garmin always asks before opening an app on the watch, so one press is the minimum.
- **No route line:** the route line needs the Share → **Navigate with NavMap** start. The Google Cloud key also needs the **Routes API** enabled.
- **Watch says "NavBridge not answering":** open NavBridge on the phone and check that it's running and up to date. Its Watch card should say "Answering NavMap on 127.0.0.1:18165", and "NavMap is open" while the watch fetches turns. "refused: wrong key" means the watch and phone apps were built with different tokens.
- **Watch says "Phone not connected":** the watch has lost Bluetooth to the phone; open Garmin Connect.
- **Reporting a bug:** tap **Copy** under Log and paste it into the chat, with a photo of the watch.
