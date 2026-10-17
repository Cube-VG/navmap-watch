# NavMap

NavMap shows a map and the next turn on a **Garmin Forerunner 165** while **Google Maps** navigates on your **Android phone**.

It's two small apps, NavMap on the watch and NavBridge on the phone, and one script sets up both.

## What you need

- A **Forerunner 165** or **Forerunner 165 Music**. NavMap doesn't run on other watches.
- An **Android phone** (Android 8 or newer) with **Garmin Connect** and **Google Maps**, and the watch paired in Garmin Connect. iPhones can't run NavBridge.
- A **Mac or Linux computer**, the watch's charging cable and a USB cable for the phone.
- A **Google account and a payment card**. Google wants a card on file before it gives you a Maps key, but one person's use normally stays inside the free allowance (see [What it costs](#what-it-costs)).

## Set it up

Open Terminal on the computer and paste this line:

```
curl -fsSLO https://raw.githubusercontent.com/Cube-VG/navmap-watch/main/setup.sh && bash setup.sh
```

The script asks one thing at a time and does everything else. You sign in to Google, plug in the phone and the watch, and tap what it tells you on the phone. It takes about 20 minutes. If anything stops it, paste the same line again: it carries on from where it was.

On Ubuntu, if it says `curl: command not found`, run `sudo apt install curl` first. On Windows, set it up by hand with [docs/SETUP.md](docs/SETUP.md).

## Using it

1. In Google Maps on the phone, choose where to go, tap **Directions**, then **Start**.
2. The watch asks to open NavMap: press **START**.
3. The next turn, its distance and your arrival time appear straight away. Outdoors, the map appears once the watch has GPS, which takes a few seconds to a couple of minutes.

For the blue route line as well, start from Google Maps' **Share** button: open the place, tap **Share** and choose **Navigate with NavMap**.

## What the watch shows

- A light, high-contrast map centred on you, readable in sunlight. It slides as you move and zooms out when you go faster.
- The next turn with Google's arrow, the distance to it, the street and your arrival time, plus the blue route line when you started with Share.
- At each new turn, and 400, 150 and 50 m before it, the watch buzzes and lights up. It stays lit for the last 120 m.
- When the screen dims it shows just the next turn and the distance. Garmin lets an app keep the screen bright for only about a minute at a time.
- When you arrive, it says "Arrived" and closes. It records no activity.
- "Google Maps" under the map. Press **START** for the map's full credits: Google's logo and its map data providers.

GPS needs open sky: indoors, the watch may wait for it forever.

## What it costs

NavMap is free. The map pictures and the route line come from Google Maps Platform, billed to your own Google Cloud account, which has a free allowance every month:

| | Free each month | After that, per 1,000 |
|---|---|---|
| Map pictures (Maps Static API) | 10,000 | $2 |
| Route lines for walking or cycling (Routes API) | 10,000 | $5 |
| Route lines by car | 5,000 | $10 |
| Route lines by motorbike | 1,000 | $15 |

The watch loads about 100 to 200 map pictures per hour of riding, so the free allowance covers roughly 50 to 100 hours a month. A route line is one request per trip, plus one per reroute. These are Google's prices as of October 2026, lower in India; see [Google's pricing page](https://mapsplatform.google.com/pricing/). To avoid surprises, set a [budget alert](https://console.cloud.google.com/billing/budgets) on the billing account.

## If something isn't working

Paste the setup line again: it checks every part and fixes what it can. Then see the troubleshooting list in [docs/SETUP.md](docs/SETUP.md#troubleshooting).

## Privacy

NavBridge asks first, on its first screen, and nothing uses your location until you tap **Agree**. Then the watch sends its position to Google to get the map pictures, which Garmin Connect fetches for it, possibly through Garmin's servers. When you start with **Navigate with NavMap**, NavBridge sends your start and destination to Google for the route line. Both use your own key. NavBridge reads only Google Maps' navigation notification, keeps no record of where you've been, and combines your location with no other provider's data. Nothing is sent to NavMap's makers.

To withdraw, open NavBridge and tap **Withdraw consent** under **Privacy and terms**. NavBridge forgets your location, and the watch forgets your key and route line, until you agree again.

## Terms

NavMap includes Google Maps features and content, from Google Maps Platform. Their use is subject to the current [Google Maps End User Additional Terms of Service](https://maps.google.com/help/terms_maps/) and [Google Privacy Policy](https://policies.google.com/privacy).

NavMap is an independent project, not made by, affiliated with or endorsed by Google or Garmin. Google Maps is a trademark of Google LLC. Garmin and Forerunner are trademarks of Garmin Ltd. or its subsidiaries.

The notification reader is tested against 174 Google Maps notifications in 17 languages from [PebbleNTN](https://github.com/optio/PebbleNTN) (MIT).

## For developers

Building the apps, testing in the simulator, and how the watch and phone talk: [docs/DEVELOPING.md](docs/DEVELOPING.md).
