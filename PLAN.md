# NavMap — where the project actually stands

Last rewritten 2026-11-07. The previous version of this file was saved on 2026-10-28 and described v0.14, a
Forerunner-165-only app, a server in `/home/vp2722/…` and no website. Almost none of that is true any more.

Detail lives in the files this one points at; this is the map.

## What it is

Two apps. **NavMap** on a Garmin watch shows Google Maps' next turn, an arrow and a live map while **NavBridge** on an
Android phone reads the navigation notification and serves it. Nineteen circular AMOLED watches are supported; phones
run Android 8 or newer. iOS cannot work (see `KNOWN-ISSUES.md` §4).

## Where things stand

| | |
|---|---|
| Repo | `Cube-VG/navmap-watch`, branch `main` |
| Watch app | **Live on the Connect IQ Store** — `apps.garmin.com/apps/f18fe040-5bb7-41a0-afb0-f61edf418dab` |
| Phone app | Free. APK from the website and GitHub releases; a Play `.aab` is submitted and awaiting verification |
| Versions | NavMap 0.16 / NavBridge 0.16 (versionCode 16) |
| Website | `navmap.download` — static HTML in `docs/`, Cloudflare in front, deployed by a GitHub Actions workflow |
| Licence | PolyForm Noncommercial 1.0.0 — source-available, not open source |

## How the money works

**NavBridge is free, permanently.** The watch app is where money is made: **seven days free, then a one-time $1.99
unlock**, collected through Garmin's trial flow rather than Garmin's merchant system.

That distinction is forced, not chosen: Garmin's merchant sells a paid app *before* download and has no trial, so a
trial requires an unlock endpoint we run. The service exists in `unlock/` (Cloudflare Worker + Razorpay) and is not
deployed yet. Full reasoning and costs: `docs/MONETIZATION.md`.

## Decisions made

| Decision | Date | State |
|---|---|---|
| NavBridge free; the watch app is the paid product | 2026-11-06 | Done — the Play Billing work is abandoned |
| Seven-day trial then $1.99, via Garmin trial mode + our unlock service | 2026-11-06 | Built, not deployed |
| PolyForm Noncommercial 1.0.0 | 2026-11-05 | Done, on `main` |
| Website as the phone-app channel until Play verifies | 2026-11-06 | Live |

## What's left

The ordered version, with stop-gates, is `docs/RELEASE.md`. In short:

1. Razorpay account + KYC; Garmin OAuth key and secret from the Developer Dashboard.
2. Deploy the unlock Worker, and **prove `https://navmap.download/unlock` answers**.
3. Install all 19 device files in the SDK Manager, then build `--publish --package` with the real developer key.
4. Upload, flag **Payment Required**, add the support email and the refund-policy URL, submit for review.
5. Fix the listing title and publisher name before that review (see open questions).
6. Play verification, then a closed test with 12 testers for 14 days.

## Known limitations

`docs/KNOWN-ISSUES.md` — the trial resets on reinstall, duplicate purchases are possible, refunds don't revoke
unlocks, the store package needs every device file, and the site still says "$1.99 planned".

## Considered and dropped

- **Play Billing inside NavBridge** — built, tested, then dropped when the model moved to Garmin. Branch closed.
- **Route-line paywall** — pointless now: NavBridge is free and the watch trial gates the whole app.
- **Garmin merchant account for a paid app** — no trial, so it cannot express "seven days free".
- **iOS support** — impossible without a separate app that does its own routing.
- **Redis, a job queue, OpenTelemetry, a database** — there is no server or database to put them in. See
  `docs/PERFORMANCE.md` for the full triage of the generic performance checklist.
- **Precompiled `.prg`/`.iq` downloads** — still deliberately not published; source builds only.

## Open questions for you

1. **What does the Connect IQ Store charge right now?** The site still says "$1.99 planned" and that word has to go,
   but the number should match reality.
2. **The listing reads "NavMap: Googl…" published by "Gmaps".** Google's brand rules and this project's own policy say
   no "Google" in a title, and a publisher that reads as "Gmaps" invites an impersonation report. Rename before the
   next review.
3. **Where will the unlock Worker live** — a Cloudflare route on `navmap.download/unlock`, or a separate host?
4. **Play timeline** — is the closed test (12 testers, 14 days) staffed yet?
