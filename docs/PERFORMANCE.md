# Performance: what we measure, and what we deliberately don't do

NavMap is a static site on a CDN, an Android app that talks to Google's APIs and a local socket, and a watch app.
That shape decides which performance advice applies. This file records the decisions so they don't get re-litigated
every time someone shares a generic "web performance checklist".

## What we changed

### 1. The redraw loop stops shouting once a second

`MainActivity` ran `render()` every second forever, and `render()` wrote three Crashlytics custom keys each time. On a
two-hour ride with the app open that is 7,200 full redraws and ~21,600 custom-key writes to display values that change
once a session.

Now the interval adapts: **1 s while a ride, a simulator run or a Garmin Connect handshake is live, 5 s when idle**, and
the Crashlytics keys are written **only when they change**.

### 2. Screenshots are WebP

| | Before | After |
|---|---|---|
| watch-map | 52.6 KB PNG | 17.6 KB |
| watch-turn | 50.9 KB PNG | 18.7 KB |
| watch-dimmed | 33.9 KB PNG | 2.9 KB |
| **total** | **137.4 KB** | **39.1 KB (−72%)** |

`og:image` stays a JPEG: social crawlers do not reliably accept WebP.

### 3. Real-user timing

- **Android:** Firebase Performance is enabled (`firebase-perf`), which adds automatic app-start, screen-render and
  HTTP traces. The HTTP traces matter most — they show how slow Google's Routes and Static Maps calls are on real
  phones and networks, which is the only place this app can be slow.
- **Website:** enable **Cloudflare Web Analytics** in the dashboard. It is free, cookieless, and for a proxied domain it
  needs **no code at all**.

## What we already do, so nobody "adds" it again

| Practice | Status |
|---|---|
| Brotli/gzip | Cloudflare sends `content-encoding: br` |
| CDN for static files | Cloudflare, `cf-cache-status: HIT` |
| Build-time rendering | The site is static HTML, built by hand and pushed |
| HTTP timeouts | `CreditProbe` 5s/5s; `RoutesClient` 10s/15s and 5s/8s |
| Self-hosted, limited fonts | DM Sans **variable** woff2, one file covering weights 400–800 |
| Lazy-loading images | Every below-the-fold image on the home page |
| Avoiding duplicate map requests | `MapView.mc` refetches only when the current picture stops covering the rider, with a 25 s floor, and holds off near turns |
| Background threads on Android | `CreditProbe` and `RoutesClient` do their own thread work |

## What we deliberately skip

These are sound practices for a server with a database. NavMap has neither.

| Practice | Why not |
|---|---|
| Redis cache, connection pooling, N+1 fixes, indexes, `SELECT` only needed columns | No database |
| Background job queue | No long server work; Android already threads its I/O |
| OpenTelemetry tracing, servers near a database, load balancing | No servers |
| Paginating API results, virtualizing long lists | One JSON object per poll; the longest list is a 10-row checklist |
| Debouncing search/autocomplete | There is no search box on any surface |
| Splitting JS by page | One 6.7 KB deferred file |
| Streaming AI tokens | No AI in the product |
| Skeleton loading screens | The page paints from cache; a skeleton would flash. The watch's own "no map yet" state is the equivalent |

If any of these ever becomes true — a backend for licence checks, say — the entry moves out of this table.

## How to look at the numbers once they exist

- **Firebase console → Performance:** start-up time, screen render times, and per-request network traces, split by
  device and country.
- **Cloudflare → Web Analytics:** page views, LCP, INP and CLS from real browsers, no cookie banner needed.
