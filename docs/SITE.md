# NavMap website

The GitHub Pages site is served from `main` → `/docs` at https://cube-vg.github.io/navmap-watch/. The landing page, setup guide and privacy page use plain HTML, CSS and a small deferred JavaScript file. No package installation or application build is required. GitHub Pages' existing Jekyll build remains enabled so the original Markdown and uppercase documentation routes stay available.

## Design

A product-focused redesign with a cobalt navigation accent, cool neutral surfaces, DM Sans, a studio watch illustration and actual product screenshots. The existing NavMap wordmark, navigation labels and section anchors are preserved. Dark appearance follows the operating system unless a visitor chooses otherwise. Motion respects reduced-motion preferences.

- `index.html`: product, screenshots, watch-family filters, installation overview, downloads and FAQs.
- `setup.html`: complete browser-readable installation guide and source-build links.
- `privacy.html`: the existing `PRIVACY.md` text, unchanged, presented in the shared page design. Keep both versions in sync when the policy is updated.
- `assets/site.css`: shared responsive layouts, appearance tokens and icon masks.
- `assets/site.js`: accessible tabs, mobile navigation, watch filters, appearance toggle, scroll reveals and copy-button feedback.

The site makes no network calls for analytics or product data. Images, icons and fonts are hosted with the site. Appearance is the only value saved in browser local storage.

## Content updates

Supported watch products are listed with `data-product` identifiers in `index.html`. Keep that list equal to the product IDs in `watch/manifest.xml`; the current set contains 19 models. Filters group them into Forerunner, fēnix/epix, Venu/vívoactive, and MARQ/D2.

The Connect IQ release is shown as coming soon, with the planned $1 lifetime price. The old site had only the Garmin store homepage, not a verified NavMap listing. Once a direct listing exists, update the download section, setup guide and README together. The NavBridge APK link resolves to the latest GitHub release. Do not publish watch binaries here.

Maps API billing is separate from the watch app purchase. The site links to Google's current pricing and cost-control documentation instead of promising a number of free navigation hours. The old default setup-script flow expects watch binaries that are no longer in the current release; the new guide points developers to source builds.

## Image and type provenance

- `assets/navmap-studio.webp`, `assets/navmap-studio-500.webp`, and `assets/navmap-studio.jpg`: generated with the built-in image generation tool for this website, then resized and compressed with FFmpeg. The visible caption identifies it as a product illustration. The generation prompt appears below.
- `assets/navmap-on-wrist-810.webp` and `assets/navmap-on-wrist-540.webp`: optimized versions of the project's existing `navmap-on-wrist.jpg`. The original is retained.
- `assets/watch-map.png`, `assets/watch-turn.png`, `assets/watch-dimmed.png`: existing NavMap simulator captures, copied without alteration. They are identified as simulator screenshots on the page.
- DM Sans: self-hosted Latin variable font from Google Fonts; SIL Open Font License in `assets/fonts/OFL.txt`.
- Tabler outline icons: self-hosted SVG files; MIT license in `assets/icons/LICENSE.txt`.

### Final image prompt

Use case: product-mockup. Asset type: hero product image for NavMap, a Google Maps navigation app on Garmin watches. Create an exceptionally polished, photorealistic studio product photograph of a SINGLE black Garmin Forerunner 165 smartwatch with black perforated silicone strap, centered and floating upright at a slight 12-degree clockwise tilt, viewed almost front-on so the display is highly legible. Input image 1 is a real NavMap watch screenshot: preserve that screen content faithfully inside the round watch face, the dark green upper turn banner with a white left arrow, '150 m', 'Turn left onto M G Rd', pale road map with blue route and blue position arrow, bottom 'Arrive 09:42' and 'Google Maps' attribution. Input image 2 is a real watch photo for the HARDWARE geometry only; do not include the wrist, phone overlay, or background from it. Physical black round bezel with GARMIN at bottom, subtle realistic button details and silicone texture. The screen must be circular, not square, and look like the actual reference. Scene: a perfectly seamless pale cool gray studio background #e9edf1, soft subtle shadow just below the floating watch. Composition: square 1:1 image, entire watch and both ends of strap fully visible, watch occupies 82% of image height, generous margins. Sophisticated directional softbox lighting, satin matte graphite case edges, subtle silver rim highlights, no glow. Only one watch. No hands, no people, no extra labels or text outside the watch, no props, no decorative graphics, no watermark. This is an honest product illustration based on the actual NavMap screen, not an invented dashboard.

## Preview

From the repository root, run `python3 -m http.server 4178 --bind 127.0.0.1 --directory docs`, then open http://127.0.0.1:4178/. No live credentials are used by the site.

## Verification for this redesign

Checked the landing page and both guides in Chromium, including viewport widths from 320 to 1920 pixels, light and dark appearances, system appearance defaults, reduced motion, keyboard tab controls, the mobile menu, every family filter, FAQs, clipboard success and denied-access feedback, local links, and the no-JavaScript path. All 19 `data-product` values were checked against the current watch manifest. Existing privacy text was compared with the rendered policy.

Automated WCAG A/AA checks reported no violations on the three pages in either appearance. A local mobile Lighthouse audit scored 98 performance, 100 accessibility, 100 best practices and 100 SEO, with 2.4 s LCP, 0 layout shift and 0 ms total blocking time. These are local lab results, not field measurements. Safari was not verified in the local Python Playwright installation.
