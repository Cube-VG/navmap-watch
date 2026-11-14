# NavMap website

The GitHub Pages site is served from `main` → `/docs` at https://navmap.download (custom domain configured, with automatic fallback from https://cube-vg.github.io/navmap-watch/). The landing page, setup guide and legal pages are plain HTML, CSS and deferred JavaScript. No package installation or application build is required. GitHub Pages' existing Jekyll build remains enabled so the original Markdown and uppercase documentation routes stay available.

## Design

**Direction: "Instrument."** The site is built to look like the thing it sells — a navigation
display — rather than like a landing-page template. The palette is taken from the watch's own
screen: green-black, signal lime, map-paper cream and route blue. Mono telemetry labels, hairlines
and tight display type carry the rest. `PLAN-DESIGN.md` in the repository root records the
direction, the token table and which ideas came from where.

- Dark is the primary treatment; the light theme is a warm map-paper counterpart, not an
  afterthought. Both are driven by the same tokens in `assets/site.css`. Appearance follows the
  operating system until a visitor chooses otherwise (`navmap-theme` in local storage).
- Type: **Archivo** (variable, width axis — display at 112%, body at 100%) and **JetBrains Mono**
  for labels. Both are self-hosted, subset to Latin + punctuation + ₹, about 64 KB together.
- Other pieces: a header that folds into a glass pill on scroll, masked line reveals, a spotlight
  hover on cards, a scroll-driven "how it works" rail, a waypoint minimap on the right edge,
  count-up statistics and a paper-coloured compatibility band.
- Motion is mechanical and quiet, and every animation is skipped under
  `prefers-reduced-motion: reduce`. Nothing is hidden from a visitor without JavaScript: the
  `html.js` class is only added when scripts run, and a watchdog removes it if they never start.

Files:

- `index.html`: hero, capabilities bento, real-ride section, scroll rail,
  compatibility band, downloads, FAQs.
- `setup.html`: the complete browser-readable installation guide, with a quick-start card, sticky
  contents and build-from-source instructions.
- `privacy.html`, `refunds.html`: the existing `PRIVACY.md` / refund text, unchanged, presented as
  a paper document on the shared site chrome. Keep both versions in sync when the policy changes.
- `assets/site.css`: tokens, primitives, header, footer, guide and prose styles.
- `assets/home.css`: homepage sections only.
- `assets/site.js`: appearance, floating header, mobile drawer, reveals, waypoints, guide
  contents, copy buttons, magnetic buttons and card spotlights.
- `assets/home.js`: watch-view tabs, watch filters, counters, scroll rail,
  pointer tilt and parallax, FAQ accordion.

The site makes no network calls for analytics or product data, and no third-party scripts are
loaded — every effect is CSS plus about 26 KB of vanilla JavaScript. Images, icons and fonts are
hosted with the site. Appearance is the only value saved in browser local storage.

## Content updates

Supported watch products are listed with `data-product` identifiers in `index.html`. Keep that list equal to the product IDs in `watch/manifest.xml`; the current set contains 19 models. Filters group them into Forerunner, fēnix/epix, Venu/vívoactive, and MARQ/D2. The count shown in the lede, the "19" stat and the filter status all need to change together with that list.

NavMap is listed on the Connect IQ Store at `f18fe040-5bb7-41a0-afb0-f61edf418dab`, and the download section, setup guide and README all link to it. If that listing is ever unpublished or replaced, update those four places together. The store lists NavMap as free because it uses a trial; the price is charged by the unlock service. The site says ₹199 in India is live and US$1.99 outside India is coming soon; change that once PayPal opens. The NavBridge APK link resolves to the latest GitHub release. Do not publish watch binaries here.

Maps API billing is separate from the watch app purchase. The site links to Google's current pricing and cost-control documentation instead of promising a number of free navigation hours. The old default setup-script flow expects watch binaries that are no longer in the current release; the new guide points developers to source builds.

## Image and type provenance

- `assets/navmap-studio.webp`, `assets/navmap-studio-500.webp`, and `assets/navmap-studio.jpg`: generated with the built-in image generation tool for this website, then resized and compressed with FFmpeg. The visible caption identifies it as a product illustration. The generation prompt appears below.
- `assets/navmap-on-wrist-810.webp` and `assets/navmap-on-wrist-540.webp`: optimized versions of the project's existing `navmap-on-wrist.jpg`. The original is retained.
- `assets/watch-map.webp`, `assets/watch-turn.webp`, `assets/watch-dimmed.webp`: existing NavMap simulator captures, copied without alteration. They are identified as simulator screenshots on the page.
- Archivo and JetBrains Mono: self-hosted subset variable fonts from the upstream OFL projects; the licence and the subsetting note are in `assets/fonts/OFL.txt`.
- Tabler outline icons: self-hosted SVG files; MIT license in `assets/icons/LICENSE.txt`.
- The design brief also takes pattern inspiration from the Annnimate starter pack (floating
  navbar, text reveal, accordion, process rail), React Bits (spotlight card,
  marquee, magnetic button, count-up, dot grid) and dqnamo's Kitchen (tactile, mechanical detail).
  Every component is re-implemented in the NavMap system; no third-party code ships.

### Final image prompt

Use case: product-mockup. Asset type: hero product image for NavMap, a Google Maps navigation app on Garmin watches. Create an exceptionally polished, photorealistic studio product photograph of a SINGLE black Garmin Forerunner 165 smartwatch with black perforated silicone strap, centered and floating upright at a slight 12-degree clockwise tilt, viewed almost front-on so the display is highly legible. Input image 1 is a real NavMap watch screenshot: preserve that screen content faithfully inside the round watch face, the dark green upper turn banner with a white left arrow, '150 m', 'Turn left onto M G Rd', pale road map with blue route and blue position arrow, bottom 'Arrive 09:42' and 'Google Maps' attribution. Input image 2 is a real watch photo for the HARDWARE geometry only; do not include the wrist, phone overlay, or background from it. Physical black round bezel with GARMIN at bottom, subtle realistic button details and silicone texture. The screen must be circular, not square, and look like the actual reference. Scene: a perfectly seamless pale cool gray studio background #e9edf1, soft subtle shadow just below the floating watch. Composition: square 1:1 image, entire watch and both ends of strap fully visible, watch occupies 82% of image height, generous margins. Sophisticated directional softbox lighting, satin matte graphite case edges, subtle silver rim highlights, no glow. Only one watch. No hands, no people, no extra labels or text outside the watch, no props, no decorative graphics, no watermark. This is an honest product illustration based on the actual NavMap screen, not an invented dashboard.

## Preview

From the repository root, run `python3 -m http.server 4178 --bind 127.0.0.1 --directory docs`, then open http://127.0.0.1:4178/. No live credentials are used by the site.

## Verification for this redesign

Checked in Chromium at 320, 360, 390, 768, 1024, 1440 and 1920 pixels, in both appearances, with
system appearance defaults, with reduced motion, and with JavaScript disabled. The watch tabs were
driven by pointer and by keyboard; the header docked, hid on scroll-down and returned on scroll-up; the
mobile drawer opened, closed on Escape and on link click, and trapped nothing behind it; every
family filter was exercised; the FAQ accordion opened, closed and set `inert` on closed panels;
the copy button was checked for success and denial. All 19 `data-product` values were checked
against the current watch manifest, every internal link and anchor resolves, and the privacy text
was compared with the rendered policy.

Automated WCAG A/AA checks (axe-core) report no violations on all four pages in both appearances.
JavaScript throws no console errors on any page.
