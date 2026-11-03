# NavMap and NavBridge: everything we've planned (saved 2026-10-28)

**Saved, not started.** On 2026-10-28 you asked for everything we discussed in one plan, kept for later. The only code change so far is one that isn't committed yet (see "Where things stand"). This file replaces the two earlier plans in `~/.claude/plans/`: `make-a-proper-plan-curried-hearth.md` (Garmin store, money, legal) and `this-conversation-continues-from-dazzling-blossom.md` (Play, and the small "Google" label).

**When you come back:** say which step to start. Step 0 (accounts) has the longest waits. Step 1 is a short README line. Step 2 is fully designed.

## Context
NavMap (Garmin watch app for the Forerunner 165 and 165 Music) and NavBridge (Android phone app) put Google Maps' next turn, a Google map picture and the route line on the watch. Today they live in the public GitHub repo Cube-VG/navmap-watch, which has no licence file. People install them over USB with `setup.sh` or `setup.ps1`, and the script also creates each user's own Google Cloud project and Maps key.

What you asked between 2026-10-22 and 10-27:
1. Remove the Google credits box that kept popping up, and bring it back only for the Garmin Connect store.
2. Why the label now says "Google Maps" instead of "Google".
3. A proper plan for publishing on Garmin's store, making money from it, whether it's legal, and the side matters.
4. Redesign the credit so it doesn't cover the screen. In the end: "can I just have small google and when I tap on it it shows the info credit".
5. Let people download the APK for free in a way they trust. Then: "we will go ahead with google play account only and start with showing GitHub apk link first few days".

## Where things stand (checked 2026-10-28)
- **Repo:** `/home/vp2722/airterminal/air-terminal-ai/navmap/d7bc9e` (Cube-VG/navmap-watch), branch `main`, last commit `ce656b2` (Windows setup script).
- **Published:** release v0.14, "NavMap 0.14 and NavBridge 0.15" (NavBridge versionCode 15).
- **Not committed (made 2026-10-27), 8 files.** The credits box now goes only into `--publish` builds:
  - `watch/source/MapView.mc`: the box code sits behind a `creditsOn`/`creditsOff` annotation switch.
  - `watch/monkey.jungle`, `watch/sim.jungle`: leave `creditsOn` out by default.
  - `tools/build-watch.sh --publish` (optionally with `fr165`/`fr165m`) writes `dist/NavMap-publish.prg` with the box. `.gitignore` covers the generated `watch/publish.jungle` and `watch/perf.jungle`.
  - `README.md`, `docs/SETUP.md`, `docs/DEVELOPING.md`: no more "press START for credits".
  - All three builds (normal, `--publish` and simulator) compiled. None was tried in the simulator or on a watch. The resulting `NavMap-fr165.prg` and `NavMap-fr165m.prg` were sent in the chat.
- **Signing key:** the published APK is signed with your release key `~/.config/navmap/navbridge-release.jks` (`O=Cube-VG, CN=NavBridge`, valid until 2054). Certificate SHA-256: `64:F6:83:7A:0E:A7:4E:19:E8:CF:FD:72:BD:D1:29:F0:B4:B2:3D:B3:D0:F1:19:EF:C9:B1:5B:28:70:16:81:34`.
- **Builds:** this server (ARM, no Android SDK) can build the watch app but not NavBridge. The APK and Play bundle need an x86-64 PC or a Mac (`docs/DEVELOPING.md:53`), or GitHub's build machines.

## Decisions so far
| Date | Decision | State |
|---|---|---|
| 2026-10-27 | Credits box out of your own builds; only the `--publish` store build keeps it | Done, not committed |
| 2026-10-27 | A small "Google" label that is always there. Tap it (or press START) to see Google's data line for 5 s. The store build says "Google Maps" | Designed (step 2), not built |
| 2026-10-27 | Google Play only, with the GitHub APK link for the first few days. No website, IzzyOnDroid or VirusTotal | Planned (steps 1, 3 and 4) |

## Short answers to your questions
- **Why "Google Maps" and not "Google"?** Up to NavMap 0.9 the label said "Google". In 0.10 (commit `72dc3a0`) it became "Google Maps", the text Google allows instead of its logo where the logo doesn't fit. Apps that already used plain "Google" may keep it for now, but Google asks new ones to use "Google Maps" (Map Tiles policies). So your own builds can say "Google", and the store build must say "Google Maps".
- **How long does "Map data ©2026" stay?** In the step 2 design it shows for 5 seconds after a tap on the label or a START press, or until the next tap or press. Nothing pops up by itself.
- **Is it legal?** No law makes it a crime. It comes down to contract terms:
  1. **Google Maps' user terms** (§2, 27 Jan 2026) say you may not "use any part of Google Maps with other people's products or services for or in connection with real-time navigation". The only exception is Google's own features, such as Android Auto. Reading Maps' navigation notification for the watch falls under this.
     - Similar apps have done this for years, and I found no case of Google acting against them: Navigation Wear (500K+ on Play, also on Garmin's store) and Navigation Pro (1M+).
     - The real risks:
       - Play can remove NavBridge, because its Device and Network Abuse policy bans apps that use a service "in a manner that violates its terms of service".
       - Google can break it technically. Maps' "Live Updates" notifications already broke Gadgetbridge.
  2. **Google Maps Platform terms** (map pictures and route line) are mostly met: you already ask for location consent and show the end-user terms notice. Attribution is the open item. Google's credit must not be "obscure[d] or delete[d]" (§3.2.2(b)), and the Routes policies say "always visible". Step 2 covers this with a label that is always shown plus the data line on a tap. One risk remains, shown in the checklist.
  3. **Grey zone, "re-creating Google features"** (§3.2.3(d)). Since July 2025 Google has had a free official Google Maps app for Garmin, with text and arrows only and no map. NavMap's own value has to be the map, the route line and the motorbike timing, and the listing should say so.
- **Can it make money?** Yes, but not through Garmin's own payments: developers in India can't sign up for them. The workable route is selling inside NavBridge through Google Play Billing (15% fee, paid out in rupees). Garmin's store allows this if the listing says payment is required.
- **Biggest practical blockers:**
  - Store users can't run `setup.sh`, so the Maps key setup has to move into the phone app.
  - Only one watch model is supported.
  - Play requires a 14-day closed test with 12 testers before the app can go public.

## Legal and policy checklist
| Area | Rule (source) | Status today | Action |
|---|---|---|---|
| Maps attribution | GMP ToS §3.2.2(b); Routes policies "always visible" | Box only in `--publish` (not committed); the logo is cropped off the picture | Step 2: label always on, data line on tap or START |
| "Google Maps" text instead of the logo | Routes policies: allowed "where space is limited", 12–16 sp, #5E5E5E, 4.5:1 contrast, "always visible and legible" | "Google Maps" since 0.10 (`drawAttribution`), in the watch's smallest font (≈13 sp) | Store build: "Google Maps". Your own builds: "Google" |
| Third-party data credits | "only 'Google Maps'… is not proper attribution" where Google names other data providers | Box only in `--publish` | Step 2: Google's own line, copied as it is from the picture |
| Credit shown on demand | Map Tiles policies: where the full credit doesn't fit, "consider adding a hover-over or clickable UI element labeled 'Data sources', which opens within the map window" | — | Step 2 follows this. Google's suggested label says "Data sources", and the Maps Static policy page is gone, so before the store submission ask Google Maps Platform support in writing. **Fallback, store build only:** the data line always on above "Google Maps" |
| No caching or prefetching of map pictures | GMP ToS §3.2.3(a)(b) | Only the current picture is shown | Keep it that way |
| Route line without a map | Service Terms §19.1 allows it | Drawn on plain ground | Fine |
| No non-Google map near Google Maps content | GMP ToS §3.2.3(e) | Unused OpenStreetMap roads code in NavBridge | Remove (step 3) |
| End-user terms and privacy policy | GMP ToS §3.2.2(a); Routes ("publicly accessible Terms of Use and a Privacy Policy"); Play; Garmin | README text only | `docs/PRIVACY.md` (step 3) |
| Location consent | GMP ToS §4.4(c)(iii) | Done (`Consent.kt`) | Keep it, and add Play's in-app disclosure |
| Brand names | Google brand rules; Play's impersonation policy | Names are fine | No "Google" or "Garmin" in titles or icons. Use "works with Google Maps" plus "Google Maps is a trademark of Google LLC" |
| Google Maps user terms §2 | See "Is it legal?" | It is the core feature | Decide in step 0 |
| Safety | Garmin may reject apps it finds "unsafe"; Motor Vehicles Act s.184 (handheld devices while driving) | Focused on riding | Describe it for walking, cycling and riding with "glance, don't touch" wording. No driving claims; add a safety disclaimer |
| Licence | No licence means all rights reserved | Missing | Choose one in step 0 |
| India data law (DPDP Act 2023, Rules Nov 2025) | Main duties from about 13 May 2027 | No user data reaches you | Fine as long as you run no server |

## Business model
Launch with **$1 one-time lifetime purchase on Garmin Connect IQ Store**, with users bringing their own free Google Maps key to avoid server/API operating costs.

- **No precompiled watch binaries on public GitHub:** Do not upload `.prg` or `.iq` to GitHub releases or the website. General users must install via Garmin Connect IQ Store ($1). Developers who wish to use it without paying must clone the repo, install the Connect IQ SDK, and build it themselves from source (`bash tools/build-watch.sh`).
- **Companion phone app:** NavBridge is distributed freely as an APK on the website/GitHub, and later via Google Play.
- **Price:** $1 one-time lifetime unlock (₹89 in India / $0.99 worldwide).
- **Pro Gating:** Turns, next maneuver text, distances, and haptic vibration buzzes remain accessible. Full live AMOLED map rendering and route polylines are unlocked with the $1 purchase.
- **Play billing rules:**
  - A paid unlock must go through Play Billing.
  - Neither the app nor its listing may link or point people to pay elsewhere.
  - Fee: 15% on the first $1M a year, once you're enrolled in the 15% tier.
  - India's "user choice billing" lets you offer your own payment option alongside Play's, for 4% less in fees. It needs PCI DSS certification and reporting each sale within 24 hours, which is too heavy for now.
- **Rejected:**
  - Garmin's own payments: India isn't eligible. You'd need a foreign company and $100 a year.
  - KiezelPay: 27% fee, payouts only through PayPal, and an extra code step.
  - Donations alone: little income, and Play forbids donation links in the app. The GitHub page can have them.

## Order of work

### Step 0. Decisions and accounts (start now; the ID checks take days)
1. **Google Maps user-terms risk:** accept it as similar apps do, or first get a one-hour opinion from an Indian tech lawyer.
2. **Licence.** If the code stays public under MIT, anyone can republish it for free. Options are a private repo, or a public repo with a non-commercial or GPL licence. (Apache-2.0 came up for IzzyOnDroid, which is now dropped.) Keep the MIT notice for PebbleNTN (`android/core/src/test/resources/pebblentn/LICENSE`).
3. **Trademark search** for "NavMap" and "NavBridge" in IP India and the USPTO. Not done yet.
4. **Accounts:**
   - **Play Console, personal:**
     - $25, paid once.
     - Your legal name and address come from your Google Payments profile.
     - Your contact email and phone are verified with one-time codes.
     - Google may ask for a government ID and a credit card in your name.
     - You verify access to an Android device in the Play Console app.
     - Your name, country and email are shown publicly. Once you sell, your full address is shown too; a business address avoids that.
   - **Garmin developer account:** you must be 18+, with ID and address checks. Your name and location are public.
5. **Testers:** collect 15 or more Google account emails from GitHub users, or have them join a Google Group. Play's closed test needs 12 of them to stay opted in for 14 days in a row, and the spares cover anyone who leaves.

### Step 1. Now: share the GitHub APK link
- **APK:** `https://github.com/Cube-VG/navmap-watch/releases/latest/download/NavBridge.apk`. It points to release v0.14 with NavBridge 0.15, so no new build is needed.
- **Release page**, which shows each file's SHA-256: `https://github.com/Cube-VG/navmap-watch/releases/latest`.
- **`README.md`:** add a short **Download** line under the intro, with:
  - the APK link and the release page;
  - "New here? Run the setup script on a computer (it also puts NavMap on the watch).";
  - "If Android says the app was blocked, use the computer setup. Don't turn off Play Protect."
- **What to send people:** the repo link for first-time setup, because the APK alone doesn't put NavMap on the watch. The APK link is for phone updates.
- **The India catch:** since Oct 2024, Play Protect's fraud protection has been rolling out to all Indian phones with Play services.
  - It blocks installing an app that asks for notification access if the APK came from a browser, a messaging app or a file manager. A link tapped in WhatsApp downloads through the browser.
  - Installs over USB (adb) and from Play aren't affected.

### Step 2. Small "Google" label; tap it for Google's data line (both apps)
This builds on the change that isn't committed yet. Install the two updates together: with the current NavBridge, a tap would still bring up the old big box.

**What you see**
- **The label:** always on while map content shows, at the bottom of the screen (y≈352). It uses FONT_XTINY, the watch's smallest font: about 13 sp, against Google's 12 sp minimum. It says "Google" in your own builds and "Google Maps" in the `--publish` store build.
- **A tap on the label, or START,** shows Google's own data line, "Map data ©2026" (168×30 px in India).
  - The tap counts on the label or up to about 20 px around it.
  - The line is copied as it is from the map picture and centred just above the label. It covers the ETA while it shows.
  - It hides after 5 s, or on a second tap or press.
  - A line wider than about 260 px splits into two rows, at a gap between words.
- **Taps anywhere else do nothing**, as in 0.14.
- Nothing pops up when the first map arrives any more, and the ETA and the arrow stay where they are.

**How the watch knows the line's size:** a Connect IQ app can't read a picture's pixels, so NavBridge measures the line.
- **Probe request:** `staticmap?center=<watch la,lo>&zoom=<z>&size=280x60&scale=2&format=png32&style=visibility:off&key=…`. With every map feature hidden, the picture is transparent apart from Google's logo and the credit box.
- NavBridge measures the box's width, height and the word gap nearest its middle, and sends them to the watch as `cw`/`ch`/`cs`.
- It probes again after 25 km, when the zoom changes, or after 12 hours. That's a few requests per ride.
- Until `cw` is known (for example with an old NavBridge), a tap shows the old two-row strip (`drawCredits`).

**Watch** (`watch/source`)
- `MapView.mc`:
  - `drawAttribution` takes the label text from a build-switched function and remembers the label's rectangle.
  - `creditsShow` draws the bottom-right `cw×ch` of `_img` with `setClip`/`drawBitmap`, as `drawCredits` does, inside the same try/catch. It splits at `cs` and falls back to `drawCredits` when `cw` is unknown.
  - `showCredits()` shows and hides the popup (`CREDITS_MS` = 5000).
  - Delete `creditsOnFirstMap` and its call in `onImage`.
  - The popup goes into every build. The `creditsOn`/`creditsOff` switch is renamed `storeOn`/`storeOff` and now only picks the label text.
- `MapDelegate` (in `MapView.mc`):
  - In `onTap`, a tap whose `evt.getCoordinates()` falls inside the enlarged label rectangle calls `showCredits()`. All taps return true.
  - `onSelect` (START) keeps calling `showCredits()`.
- `NavModel.mc` reads `cw`/`ch`/`cs`. `NavLink.mc` sends `z` and the current `cw` the way it already sends `ah`/`rv`, so NavBridge only resends when they change.

**NavBridge** (`android`)
- New `core/.../CreditStrip.kt`: a pure function that finds the box's width, height and word gap from ARGB pixels. Unit test `CreditStripTest.kt`, with real probe strips from Chennai and London as test resources.
- New `app/.../CreditProbe.kt`: fetches the probe with `HttpURLConnection` and the stored key, as `RoutesClient.kt` does. It decodes the picture with `BitmapFactory`, keeps the result per area and runs off the main thread.
- `core/.../WatchProtocol.kt`: reads `z`/`cw` from the watch's poll and adds `cw`/`ch`/`cs` to the reply when the watch's `cw` differs. Wire this up in `NavServer.kt` and `NavSession.kt`.

**Build and docs**
- `tools/build-watch.sh --publish` stays, and now switches the label to "Google Maps". The jungles (`monkey`, `sim` and `publish`) exclude the renamed annotation.
- `README.md:52`, `docs/SETUP.md:58`, `docs/DEVELOPING.md:26`: "Tap 'Google' (or press START) to see the map data credits."
- Bump both apps' versions. Commit together with the change that isn't committed yet, then make a GitHub release.
- **Open:** the store build could say "Google Maps ⓘ" so the label is clearly something to tap. Google's own wording is a "Data sources" button.

### Step 3. NavBridge ready for Play (before the first upload)
- **Battery:** Play forbids the direct "ignore battery optimisation" prompt unless the app's core function is harmed. Android's accepted list covers only companion devices that keep a persistent connection to give the device internet access.
  - Remove `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and the prompt (`MainActivity.kt:159`).
  - Open NavBridge's App info page instead (`ACTION_APPLICATION_DETAILS_SETTINGS`), with the existing hint "Battery → Unrestricted" (`MainActivity.kt:164`). No permission is needed, and the same build works for GitHub and Play.
- **Disclosure:** a notice inside the app before asking for notification access. Play says it "must be within the app itself". Location already has one in `Consent.kt`.
- **Location:** keep it foreground-only (`Locator.kt` uses the last known fix). Background location would need a declaration form.
- **Foreground service** (Android 14+): declare its type in Play's App content form, with a description, the user impact and a video. The best fit is `connectedDevice`. Add `location` only if NavBridge reads the phone's GPS.
- **Key setup screen** in `MainActivity.kt`, because Play users have no setup script.
  - Reuse the existing paste field.
  - Give step-by-step links to create a project, turn on billing, enable the Maps Static API and Routes API, and restrict the key.
  - Test the key the way `setup.sh`'s `check_key` does.
  - Without a key, the turns still work.
- **Reviewer demo** with no watch and no key: reuse `Simulator.kt` ("Simulate navigation").
- **Remove the OpenStreetMap roads code**, after checking it's off in every build:
  - `Roads.kt` and `RoadTiles.kt` (app); `Mvt.kt`, `RoadArea.kt` and `RoadPlanner.kt` (core), with their tests;
  - the `roads` message in `WatchProtocol.kt`, and the lines that use it in `NavSession.kt` and `MainActivity.kt`;
  - `tools/roads.py`.
- **Privacy policy and terms:** `docs/PRIVACY.md` in the repo. A GitHub page is a public URL, so no website is needed.
  - It covers the Google Maps End User Additional Terms, the Google Privacy Policy, what is sent where, the safety disclaimer, and that the app isn't affiliated with Google or Garmin.
  - Link it from NavBridge and from the Play listing.
- **Target API 36:** already set. It's required for new apps and updates since 31 Aug 2026.
- **No donation or KiezelPay links** in the Play build.
- **Version:** the Play build's versionCode must be higher than the newest GitHub APK's: 15 today, 16 if step 2 ships first.
- **Signing:** use Play App Signing with your **existing** key. Upload `~/.config/navmap/navbridge-release.jks` with Google's PEPK tool, and make a separate upload key, `~/.config/navmap/navbridge-upload.jks`.
  - Then people with the GitHub APK get the Play version as a normal update, because the package and key are the same.
  - If Google made a new key instead, they would have to uninstall first and lose NavBridge's settings: Maps key, watch pairing and consent.
- **Bundle build:** a new `tools/build-phone.sh --play` runs `:core:test :app:bundleRelease`, signs with the upload key and writes `dist/NavBridge.aab`. It runs on a PC or Mac, or on GitHub's build machines with the upload key in the repo's secrets (open question 6).

### Step 4. Play closed test, then production
- Upload the bundle (AAB) to the closed test track and invite the 15+ testers from step 0.
- **Listing:**
  - Title "NavBridge for NavMap", with "works with Google Maps", the trademark line and the safety disclaimer.
  - 2–4 screenshots taken on your phone, and the privacy policy URL.
  - Data safety: precise location, sent only to Google, not stored and not shared. Declare it as "ephemeral"; the form is required even if an app collects nothing.
  - Content rating; target audience 18+.
  - The foreground-service declaration with its video.
- After 14 days in a row with 12 or more testers opted in, apply for production.
- Then the README and release notes point to Play. GitHub releases stay, signed with the same key, for people without Play.
- **Android developer verification:**
  - From 2027 it applies to every app on certified Android phones. India has no date yet; enforcement started on 30 Sep 2026 in Brazil, Indonesia, Singapore and Thailand.
  - Apps on Play are registered automatically. Because Play will use the same key as the GitHub APK, check in the Android Developer Console that the GitHub APK is covered, and register the key if not.
  - Installs over USB with the setup script never need it.

### Step 5. NavMap on Garmin's Connect IQ Store (alongside step 4)
- **Store assets:**
  - a 500×500 icon, screenshots under 150 KB each, and an optional 1440×720 hero image;
  - a support email and the privacy policy URL;
  - "Companion App Required", pointing to the Play listing;
  - the monetization question answered: payment happens in the phone app.
- **Build:** `tools/build-watch.sh --publish` (label "Google Maps"), then export the `.iq` file.
- **Review time:** about 2 weeks at the moment (the5krunner, 16 Oct 2026). Fixes on the phone side (parsing in NavBridge) reach people faster than watch fixes.
- **App ID:** keep the store app ID separate from the sideloaded one, and test a store update over a GitHub install. The watch keeps storage per `.prg` file name (`docs/DEVELOPING.md`, "Pairing").
- **Listing text:** walking, cycling and riding, with "glance, don't touch". Say what sets it apart from Google's own Garmin app: the map, the route line and the motorbike timing.
- Once it's live, new users don't need a computer at all.

### Step 6. Pro unlock
- Add the Play Billing Library to NavBridge: a one-time product and a purchase check on the phone.
- The `/nav` answer carries whether the user has Pro (`NavEndpoint.kt`, `WatchProtocol.kt`). The watch shows the map only with Pro or during the trial. Turns always show.
- Test with Play licence testers, and declare the payment on the Garmin listing.

### Step 7. Later
- **More watches.** COMPLETED (2026-11-03): Circular AMOLED layout is dynamically responsive across 360×360 to 454×454 displays (`SCREEN`, `HALF`, `MARK_Y`, `ATTR_CY`). 19 flagship AMOLED devices (31 hardware variations) are fully enabled in `manifest.xml` and package built in `dist/NavMap-publish.iq` (737 KB):
  - Forerunner 165, 165 Music, 265, 265s, 965.
  - Fenix 8 AMOLED (43mm, 47mm).
  - Epix (Gen 2), Epix Pro (42mm, 47mm, 51mm).
  - Venu 2, Venu 2 Plus, Venu 2s, Venu 3, Venu 3s, vívoactive 5.
  - MARQ Gen 2, D2 Mach 1.
  *(Future: MIP screens like FR255/955, fēnix 7, Instinct, which need 64-color palette quantization).*
- **Fewer map pictures per hour:** re-fetch less often and zoom out at speed. Measure with `tools/build-watch.sh --perf` logging.
- **Optional managed subscription:** a small server that signs Static Maps URLs (with the unsigned quota set to 0), calls the Routes API itself and limits use per subscriber.
  - It must return signed URLs, not pass the images through.
  - It would make you a "Data Fiduciary" under the DPDP Act (notice, security, breach reporting) from about May 2027.
  - Check the Maps Platform terms for using your own key first. The EEA version requires "Safety Requirements" for real-time navigation, and the global version may too.

## Side matters
- **Tax (ask a chartered accountant):**
  - Link your PAN to Aadhaar. Play deducts 0.1% TDS (5% without a PAN) and issues quarterly Form 131 certificates.
  - GST registration is needed above ₹20 lakh a year, but exporters may need it earlier to file an LUT.
  - The income likely falls under the presumptive scheme in section 58 of the Income-tax Act 2025 (6% of digital receipts).
  - Keep the FIRA/e-FIRA for any payment from abroad.
- **EU:** Garmin treats anyone who sells apps or takes tips as a "trader", and distribution in the EEA needs Garmin's approval. An Indian billing address keeps you on Google's non-EEA terms.
- **Road law:**
  - Motor Vehicles Act s.184 makes using a handheld communication device while driving "dangerous driving".
  - The 2020 CMVR amendment allows such devices "solely" for route navigation.
  - Smartwatches aren't addressed. Glancing is probably fine; tapping while moving is risky. Hence the "glance, don't touch" wording, and START as an alternative to tapping.
- **Upkeep:** Google Maps' notification formats change (Live Updates).
  - Keep the PebbleNTN tests and add new notification captures.
  - Re-test after every Garmin Connect update; Garmin bug CIQQA-4631 is still open.

## Considered and dropped
- **The credits box on START and with the first map** (0.14): it covered about a third of the screen, over the road ahead. Step 2 replaces it.
- **The data line always on, under the ETA** ("Arrive 22:40 / Map data ©2026 / Google Maps"): three labels stacked at the bottom, about a seventh of the screen in all. You found it too busy. It stays as the store-build fallback if Google won't accept the tap design.
- **A free, trusted download site** (2026-10-27), dropped when you chose Play only. It would have had:
  - a GitHub Pages site at `cube-vg.github.io/navmap-watch`;
  - VirusTotal reports and GitHub build records proving the APK was built from the public code;
  - IzzyOnDroid, which only lists open-source apps and flags Play's encrypted library list (one `dependenciesInfo` build setting removes it);
  - Samsung Galaxy Store or Uptodown, if the code stays closed;
  - the signing fingerprint on the site, and Fastlane store text.
- **Other ways to get paid:** Garmin's own payments, KiezelPay and donations alone (see "Business model").

## Critical files (repo `navmap/d7bc9e`)
- **Watch:** `watch/source/MapView.mc` (label, credits, layout, `MapDelegate`), `watch/source/NavModel.mc`, `watch/source/NavLink.mc`, `watch/manifest.xml` (devices), `watch/monkey.jungle`, `watch/sim.jungle`, `tools/build-watch.sh`.
- **NavBridge app,** in `android/app/src/main/kotlin/com/cubevg/navbridge/`: `MainActivity.kt`, `Consent.kt`, `Locator.kt`, `NavSession.kt`, `NavServer.kt`, `RoutesClient.kt`, `Simulator.kt`, `Roads.kt` and `RoadTiles.kt` (to remove), and the new `CreditProbe.kt`. Also `android/app/src/main/AndroidManifest.xml` and `android/app/build.gradle.kts` (version).
- **NavBridge core,** in `android/core/src/main/kotlin/com/cubevg/navmap/core/`: `WatchProtocol.kt`, `NavEndpoint.kt`, `Mvt.kt`, `RoadArea.kt` and `RoadPlanner.kt` (to remove), and the new `CreditStrip.kt`.
- **Tools:** `tools/build-phone.sh` (new `--play` option), `tools/roads.py` (to remove), `setup.sh` and `setup.ps1`.
- **Docs:** `README.md`, `docs/SETUP.md`, `docs/DEVELOPING.md`, and the new `docs/PRIVACY.md`.

## Verification
1. **GitHub link:**
   - The downloaded APK's SHA-256 matches `SHA256SUMS`.
   - Install it from Chrome on your phone and note whether Play Protect blocks it.
   - The computer setup works.
2. **Tappable label (step 2):**
   - Probe live at Chennai and London (zoom 16). The blank probe's box must match the boxes in the normal map pictures: 168×30 and 244×30. Also probe a place with a long credit (Tokyo or Berlin) to test the split.
   - `./gradlew :core:test` passes. The normal, `--publish` and simulator watch builds compile, and NavBridge builds.
   - In the simulator:
     - A tap on the label shows the line, and it hides after 5 s or on a second tap.
     - Taps on the map or the banner do nothing.
     - START shows the line.
     - With no `cw`, the old strip appears.
     - Take screenshots of both labels.
   - On the watch: install the `.prg`, go for a short ride, and brush the screen to check the popup doesn't come up by accident.
3. **Ready for Play:**
   - The battery step opens App info.
   - The reviewer demo works with no watch and no key.
   - Play's pre-launch report is clean.
   - The Data safety answers match the app's real traffic, checked with a proxy.
4. **Signing and updates:**
   - Play Console → App signing shows the certificate SHA-256 `64:F6:83:7A:…:70:16:81:34`.
   - A phone with the GitHub APK that joins the closed test sees **Update**, not Install, and keeps its Maps key, pairing and consent.
   - 12 or more testers stay for 14 days, and Play grants production access.
5. **Garmin store:**
   - Simulator screenshots of every watch state show the Google label, readable: map, no map, dimmed, banner, arrival and ride-ending text.
   - A real ride on the FR165 with the store builds of both apps.
6. **Pro:** a test purchase with a Play licence tester switches the watch from trial to Pro.
7. **Always:**
   - Set a Google Cloud budget alert.
   - Re-read the quoted terms just before each submission, because they change. The GMP ToS last changed on 26 Aug 2026, and the Service Terms on 15 Oct 2026.

## Open questions for you
1. Google Maps user terms: go ahead as similar apps do, or get a lawyer's opinion first?
2. Business model: free plus a one-time Pro on users' own keys (recommended), a managed subscription, or free only?
3. Keep the GitHub repo public? Under which licence?
4. Which watches after the FR165?
5. May I add the Download line to the top of the README (step 1)?
6. Where will the Play bundle be built: your PC or Mac, or GitHub's build machines (with the upload key in the repo's secrets)?
7. Build step 2 now? For the store label, "Google Maps" or "Google Maps ⓘ"?
8. Commit the box removal (not committed yet) on its own now, or together with step 2?

## Sources (checked 2026-10-27)
- **Google Maps:**
  - Google Maps user terms: https://www.google.com/help/terms_maps/
  - Maps Platform terms: https://cloud.google.com/maps-platform/terms; Service Terms: https://cloud.google.com/maps-platform/terms/service-terms
  - Routes API policies: https://developers.google.com/maps/documentation/routes/policies
  - Map Tiles API policies (the "Data sources" button): https://developers.google.com/maps/documentation/tile/policies
  - Pricing: https://developers.google.com/maps/billing-and-pricing/pricing; India: https://developers.google.com/maps/billing-and-pricing/pricing-india
  - Google's brand rules for Maps: https://about.google/brand-resource-center/products-and-services/geo-guidelines/
- **Play and Android:**
  - Testing requirements for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
  - Play App Signing: https://support.google.com/googleplay/android-developer/answer/9842756
  - Service fees: https://support.google.com/googleplay/android-developer/answer/112622
  - Battery optimisation and Play policy: https://developer.android.com/training/monitoring-device-state/doze-standby
  - Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
  - Android developer verification: https://developer.android.com/developer-verification
  - The block on sideloaded apps in India: https://blog.google/intl/en-in/products/launching-enhanced-fraud-protection-pilot-in-india/
- **Garmin:**
  - Publishing to the store: https://developer.garmin.com/connect-iq/core-topics/publishing-to-the-store/
  - App review guidelines: https://developer.garmin.com/connect-iq/app-review-guidelines/
  - App sales: https://developer.garmin.com/connect-iq/articles/monetization/App_Sales.html
  - Merchant onboarding: https://developer.garmin.com/connect-iq/articles/monetization/Merchant_Onboarding.html
  - Brand guidelines: https://developer.garmin.com/brand-guidelines/connect-iq/
  - Store review delays: https://the5krunner.com/2026/10/16/connect-iq-store-delays/
  - Google Maps on Garmin (July 2025): https://the5krunner.com/2025/07/08/google-maps-on-garmin-a-new-navigation-option-with-caveats/
- **Similar apps:**
  - Navigation Wear: https://play.google.com/store/apps/details?id=com.headupnav.navigationwear
  - Navigation Pro: https://play.google.com/store/apps/details?id=smartwatchstudios.app.gears3navigation
  - Gadgetbridge's navigation notes: https://gadgetbridge.org/basics/integrations/navigation/
