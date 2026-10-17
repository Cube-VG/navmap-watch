# Setting up NavMap by hand

On a Mac or Linux computer, `bash setup.sh` does all of this for you (see the [README](../README.md)). This page is for Windows, or if you'd rather do it yourself. You need the same things as the README lists.

## 1. Download the two apps
From [the latest release](https://github.com/Cube-VG/navmap-watch/releases/latest), download:

- `NavMap-fr165.prg` for the Forerunner 165, or `NavMap-fr165m.prg` for the Forerunner 165 Music. Garmin Connect's list of devices shows which one you have.
- `NavBridge.apk`, the phone app.

## 2. Make a Google Maps key
The key lets NavMap get map pictures and route lines from Google, billed to you. Google asks for a payment card, but one person's use normally stays inside the free allowance (see [What it costs](../README.md#what-it-costs)).

1. [Create a project](https://console.cloud.google.com/projectcreate) in the Google Cloud console. Any name will do, for example NavMap.
2. Open [Billing](https://console.cloud.google.com/billing) and link a billing account to the project, creating one if you have none.
3. Turn on the two services NavMap uses: open [Maps Static API](https://console.cloud.google.com/apis/library/static-maps-backend.googleapis.com) and [Routes API](https://console.cloud.google.com/apis/library/routes.googleapis.com), and click **Enable** on each.
4. Open [Credentials](https://console.cloud.google.com/apis/credentials), click **Create credentials → API key**, then open the new key. Under **API restrictions** choose **Restrict key**, tick **Maps Static API** and **Routes API**, and save. Leave **Application restrictions** at **None**: the map pictures are fetched by Garmin's servers, so no single app or address fits.
5. Copy the key. It starts with `AIza`.

## 3. Put NavMap on the watch
1. Plug the watch into the computer with its charging cable. On Windows and Linux it shows up like a phone or a USB stick. On a Mac you need [OpenMTP](https://openmtp.ganeshrvel.com/) to open it.
2. Copy the `.prg` file into the watch's **GARMIN** folder, then into **Apps**. If a NavMap file is there already, replace it.
3. Unplug the watch. NavMap is now in the watch's list of activities and apps (press **START** on the watch face).

## 4. Install NavBridge
1. Copy `NavBridge.apk` to the phone, or download it on the phone, and open it.
2. If Android asks, allow your browser or Files app to **install unknown apps**.
3. If Play Protect warns about an unknown developer, tap **More details → Install anyway**.

## 5. Set up NavBridge
Open NavBridge. It first shows **Before you start**: what NavBridge does with your location, and that Google's terms apply to the map and route line. Read it and tap **Agree**. Until you do, NavMap shows no map or turns.

Paste your key into **Google Maps key** and tap **Save key**.

Then work down the **Setup** list. Each step turns green when it's done.

| Step | Why |
|---|---|
| **Google Maps terms and your location** | Your OK from **Before you start**, if you tapped **Not now** there: tap **Read**. You can withdraw it any time under **Privacy and terms**. |
| **Notification access** | Lets NavBridge read Google Maps' turn-by-turn notification. |
| **Switch greyed out?** | Android blocks this switch for apps installed from a file. Open **App info → ⋮ → Allow restricted settings**, then turn on notification access again. |
| **Notifications** | For the reminder when the watch needs a button press. |
| **Battery: NavBridge** | Set to **Unrestricted** so Android doesn't stop it mid-ride. |
| **Battery: Garmin Connect** | Also **Unrestricted**, so it keeps talking to the watch. |
| **Location (optional)** | Lets the route line start from the phone's position before the watch has GPS. |

Under **I travel by**, pick Motorbike, Car, Bicycle or Walking. It sets the route line's travel mode.

## 6. Pair the watch, then test
1. With NavBridge open on the phone, open NavMap on the watch. Within a few seconds NavBridge's **Watch** card says "Paired with NavMap", and NavBridge hands the watch your key. From then on NavBridge answers only this watch.
2. Tap **Simulate navigation**. If NavMap isn't open, the watch asks to open it: press **START**. A demo ride appears with turn arrows, distances and an arrival time, through the same path as real Google Maps navigation. The map and the blue route line appear once the watch has GPS, by a window or outside.

## 7. Ride
- **Turns only:** start navigation in Google Maps as usual. The watch asks to open NavMap. Press **START** once and the turns appear.
- **Turns and the blue route line:** in Google Maps, open the destination, tap **Share** and pick **Navigate with NavMap**. NavBridge works out the route and starts Google Maps navigation for you.

Press **START** on the watch to see the map's credits: Google's logo and the map data providers, as printed on the map picture. "Google Maps" stays under the map.

The watch buzzes twice before each turn, timed by your speed: one long buzz about 25 seconds before, and three short ones about 6 seconds before, when the turn banner turns blue. It lights up with each buzz and stays lit for the last 120 m before a turn. In between, the screen dims to the next turn's arrow and distance; raise your wrist or press a button to see the map. When you arrive it shows "Arrived" and closes.

The map can't stay on all the time: Garmin lets a Connect IQ app hold the screen bright for only about a minute, and the dimmed screen may light only a tenth of its pixels, too few for a map. Garmin's own apps don't have these limits.

For the longest bright screen, on the watch hold **Up → System → Display**, and in both **General Use** and **During Activity** set **Timeout** to the longest value, turn **Gesture** on, and leave **Always On Display** on.

## Troubleshooting
On a Mac or Linux computer, first run `bash setup.sh` again: it checks every part and fixes what it can. `bash setup.sh test` runs just the final test.

- **Nothing on the watch:**
  1. Open NavBridge and check that every Setup step is green.
  2. Check that the **Watch** card shows the watch as connected and says "Paired with NavMap".
  3. Check that the **Log** has a line for the Google Maps notification.
- **"Press the button on the watch":** Garmin always asks before opening an app on the watch, so one press is the minimum.
- **Watch says "Waiting for GPS":** the map, the route line and your arrow need the watch's own GPS, which needs open sky. Indoors it may never find you; outside it takes from a few seconds to a couple of minutes. The phone's location isn't used.
- **Watch says "Open NavBridge to agree":** you haven't agreed in NavBridge yet, or withdrew. Open NavBridge, read **Before you start** and tap **Agree**, or tap **Read** on the first Setup line.
- **Watch says "No map key yet":** NavBridge hasn't given the watch your key. Check that the first Setup line is green and that NavBridge shows "Google Maps key: …" with the last 4 characters of yours, then keep NavMap open for a few seconds.
- **Watch says "Loading map" and no map comes:** check in the Google Cloud console that **Maps Static API** is enabled for the key's project and that billing is linked. The watch gets the pictures through Garmin Connect, so that must be connected too.
- **Watch says "Open NavBridge to pair":** NavBridge is paired with another watch, or NavMap was set up again. Open NavBridge, tap **Pair again**, then open NavMap on the watch within 10 minutes. The Watch card counts these refusals as "N refused: not the paired watch".
- **Watch says "NavBridge not answering":** open NavBridge on the phone and check that it's running and up to date. Its Watch card should say "Answering NavMap on 127.0.0.1:18165", and "NavMap is open" while the watch fetches turns.
- **Watch says "Phone not connected":** the watch has lost Bluetooth to the phone; open Garmin Connect.
- **Watch says "Waiting for Google Maps":** all is well; it's waiting for you to start navigating in Google Maps.
- **No route line:** the route line needs the Share → **Navigate with NavMap** start, because Google Maps doesn't tell other apps the route. The key's project also needs the **Routes API** enabled.
- **"App not installed" when installing NavBridge:** an older NavBridge, signed differently, is in the way. Uninstall it, then install again.
- **Reporting a bug:** tap **Copy** under Log and paste it into [a new issue](https://github.com/Cube-VG/navmap-watch/issues), with a photo of the watch. Read it first: it can show where you were going.
