# NavMap website — premium redesign plan

Written 2026-11-14, before implementation. Supersedes the visual direction in `docs/SITE.md`
(the content rules there still hold: accurate pricing, 19 models, no watch binaries).

## The idea

The site stops looking like a landing-page template and starts looking like **the instrument
it sells**. One direction, applied everywhere:

> **Instrument.** A navigation display, not a marketing page. Deep green-black, a signal lime,
> map-paper cream, route blue. Hairlines, mono telemetry labels, big tight display type.
> The watch's own UI colours (dark green turn banner, cream map, blue route) become the palette.

Motion is *mechanical and precise* — lines drawing, numbers counting, panels settling — never
bouncy or decorative. Everything respects `prefers-reduced-motion`.

## Design tokens

| Token | Dark (default) | Light |
|---|---|---|
| `--bg` | `#070D0B` green-black | `#F4F1E7` map paper |
| `--panel` | `#0B1411` | `#FFFFFF` |
| `--line` | `rgba(200,245,220,.10)` | `rgba(10,30,20,.12)` |
| `--text` | `#EAF2EC` | `#0C1512` |
| `--muted` | `#9AAFA4` | `#4E5C55` |
| `--accent` | `#C9FA6B` signal lime | `#17553B` pine |
| `--route` | `#5B8CFF` route blue | `#2456D6` |
| `--amber` | `#FFBE5C` turn cue | `#A9640B` |
| `--paper` | `#EFEBDF` | `#EFEBDF` |

- Type: **Archivo** (variable, width axis — display at 112% width, body at 100%) +
  **JetBrains Mono** for telemetry labels. Both self-hosted, subset to ~63 KB total.
- Shape: 999px pills for actions and chips, 20–28px cards, 1px hairlines, no heavy shadows —
  depth comes from one long soft shadow and a top inner highlight.
- Texture: 3% film grain over everything, dot-grid that fades out, two slow radial glows.
- Icons: keep the Tabler set, masked and recoloured; logo mark redrawn in lime.

## Page architecture

1. **Floating header** — full-width at rest; on scroll it narrows into a glass pill, and hides
   on scroll-down / returns on scroll-up. Scroll progress drawn as a hairline under it.
2. **Hero** — masked line-by-line headline, a hand-drawn route underline that draws in, product
   card with rings + slow parallax + pointer tilt, a floating glass "turn cue" chip, three
   micro-facts. Background: dot grid, two glows, contour lines.
3. **Ticker** — infinite mono marquee of the value props with lime separators.
4. **Capabilities bento** — an interactive screen stage (3 real simulator screenshots, tabs
   that work as tabs) beside telemetry stat tiles (25 s, 6 s, 0, 19, Free) with spotlight hover.
5. **Real ride** — the actual wrist photo, parallaxed, with the two real benefits.
6. **How it works** — a sticky scroll rail: a route line draws between three steps and each
   step lights as the line reaches it. Collapses to a plain list on small screens.
7. **Compatibility (paper section)** — cream band, big "19", filters, all 19 models as an
   indexed grid. Tonal break that makes the dark sections feel deliberate.
8. **Downloads** — two plans: ₹199 one-time (live in India, $1.99 coming), free NavBridge APK.
9. **FAQ** — accordion with line-reveal, plus/minus icon.
10. **Footer** — oversized wordmark with a route line through it, link columns, legal.
11. **Waypoint rail** — right-edge minimap of the page: a dot per section, current one live.

Also rebuilt in the same system: `setup.html` (field guide, sticky TOC), `privacy.html`,
`refunds.html` (paper document on dark chrome).

## What comes from where

- **Annnimate starter pack** — techniques adapted and re-themed: floating navbar, text reveal,
  accordion, process rail. Not pasted: the pack's
  components ship their own demo-page CSS/fonts, so each was rebuilt in the NavMap system.
- **React Bits** — the pattern vocabulary: Spotlight Card, Tilted Card, Magnetic Button,
  Marquee, Count Up, Scrambled Text, Dot Grid / Aurora backgrounds, Star Border.
- **Skiper UI** — pill navigation, docked header, oversized display type, micro-interaction
  restraint.
- **dqnamo Kitchen** — the tactile, physical-object feel: pressable controls, scrambles,
  receipt-tight mono labels, honest material depth.

## Engineering rules

- Stays a **static site** — no build step, no framework, no CDN at runtime. GSAP is *not*
  loaded: every effect above is CSS + ~15 KB of vanilla JS, so the site keeps its
  "no third-party network calls" property and stays fast on a phone at 4G.
- Content is preserved: pricing, trial terms, 19 model IDs, disclaimers, privacy/refund text.
- Costs about the same: one CSS bundle + one JS bundle, fonts subset, images reused.
- Accessibility: real buttons/tabs/accordion semantics, visible focus, skip link, no-JS still
  reads and navigates, reduced motion kills every animation, AA contrast in both themes.

## Verification

Chrome screenshots at 360/768/1024/1440/1920, both themes, reduced motion, keyboard tab flow,
no-JS pass, and a link/ID check.

## Dropped after review

A "split-flap departures board" sat between the hero and the capabilities section, cycling the
watch's instructions as mechanical flaps. It was cut: the board read as a station timetable rather
than as the product, and no amount of captioning made "same layout as the watch" true. The page is
stronger without a decorative interruption between the hero and the actual screens, and the site
keep its own logo and mark untouched.
