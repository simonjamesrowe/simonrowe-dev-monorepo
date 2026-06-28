# Handoff: simonrowe.dev Design System

## Overview

This bundle is a complete brand and UI system for **simonrowe.dev** — Simon
Rowe's personal site (public hero / about / career timeline / blog / chat
drawer / footer) plus the authenticated admin console (sidebar shell +
dashboard, blog list & editor, event list, media library, experience editor).

The design system is derived from the existing repo
`simonjamesrowe/simonrowe-dev-monorepo` (frontend in React 19 + Vite + TS,
backend in Spring Boot + Kafka). Tokens, type, voice, and visual conventions
are lifted from `frontend/src/styles.css` and the reference HTML in
`/designs`, then expanded into a documented system + working prototypes.

## About the design files

**The HTML/JSX in this bundle is a design reference, not production code.**

The prototypes use:
- React 18 (loaded inline via UMD `<script>`, not the project's React 19)
- Babel Standalone for JSX (no Vite pipeline)
- Lucide UMD CDN for icons (the real codebase uses `lucide-react`)
- Plain CSS in `colors_and_type.css` + per-kit `styles.css` (the real
  codebase uses one big `styles.css` with BEM)

When you reimplement in the real monorepo, follow the existing
`frontend/src/components/**` patterns: TS components, `lucide-react` imports,
React Router, Auth0 for admin, STOMP for chat. Pull design *intent*
(layout, color, type, motion, copy) from these files, not the runtime setup.

## Fidelity

**High-fidelity.** Final colors, typography, spacing, motion, and copy.
Photographic placeholders are gradient-filled monogram blocks because the
real `/uploads/*` assets live behind the running backend — wire those in once
you have the URLs.

## How to use this with Claude Code

1. **Download the project** (the parent `Simon Rowe Design System` folder)
   as a zip from this chat.
2. Unzip it into a sibling folder next to your monorepo, e.g.
   `~/code/simonrowe-design-system/` next to `~/code/simonrowe-dev-monorepo/`.
3. In your monorepo, start Claude Code:
   ```bash
   cd ~/code/simonrowe-dev-monorepo
   claude
   ```
4. Point Claude Code at this handoff with a prompt like:
   ```
   Read ~/code/simonrowe-design-system/README.md and
   ~/code/simonrowe-design-system/design_handoff/README.md, then port the
   public-site UI kit at ui_kits/website/ into our React 19 + Vite frontend.
   Keep our existing routing, BEM class naming, and styles.css token system —
   only update tokens to match the design system's colors_and_type.css.
   ```
5. Iterate screen-by-screen. Good chunks to ask for one at a time:
   - "Update `frontend/src/styles.css` `:root` and `[data-theme=light]` tokens
     from `colors_and_type.css`."
   - "Reimplement the Hero component to match `ui_kits/website/Hero.jsx`."
   - "Rebuild the career timeline alternation pattern from
     `ui_kits/website/CareerTimeline.jsx`."
   - "Wire the chat drawer prompt chips + send flow from
     `ui_kits/website/ChatDrawer.jsx`."

Tell Claude Code to **read the prototype files** (not just the README) before
writing — the JSX shows exactly which Lucide icon, which gradient, which
hover transform to use.

## What's in the bundle

| Path | What it is |
|---|---|
| `README.md` (root) | Brand context, content fundamentals, visual foundations, iconography |
| `colors_and_type.css` | All CSS custom-property tokens (dark + light) and semantic type classes |
| `assets/favicon.ico` | Real favicon copied from the codebase |
| `preview/*.html` | Small preview cards for each design-system token (colors, type, spacing, components) |
| `ui_kits/website/` | Public-site React/JSX prototype — open `index.html` to run |
| `ui_kits/admin/` | Admin-console React/JSX prototype — open `index.html` to run |
| `designs/` | Original reference HTML imported from the codebase (`/designs/*.html`) |

## Design tokens

All tokens live in `colors_and_type.css`. Highlights:

**Color (dark default):**
- `--surface` `#0f131c` → 7-step ladder up to `--surface-bright` `#353943`
- `--primary` `#77d1ff` (sky cyan), `--secondary` `#ffb690` (warm — education only)
- `--on-surface` `#dfe2ef`, `--on-surface-variant` `#bec8cf`

**Color (light):**
- `--surface` `#f8f9fc`, `--primary` `#2563eb`

**Type:**
- Heading: `Space Grotesk` 500/600/700, tight tracking (-0.02 to -0.03em)
- Body / UI: `Inter` 400/500/600, line-height 1.6
- Mono: system mono

**Spacing:** 8pt grid via `--space-1` (4px) → `--space-20` (80px)
**Radii:** sm 6 / md 12 / lg 16 / xl 24 / 2xl 32 / full 999
**Motion:** fast 0.15s / normal 0.25s / slow 0.4s `cubic-bezier(.4,0,.2,1)`

## Voice & content rules

- **First person** ("I am driven…", "I'm a strong advocate for AI-native
  engineering — but I believe AI amplifies good engineering, it doesn't
  replace it.")
- **Sentence case** for headings, with **one accent word in primary** color
  ("Career **Timeline**", "About **Simon**")
- **Em dash (—)** for separators; **eyebrows are UPPERCASE + 0.08em tracking**
- Lucide icons everywhere; emoji ONLY for the 🎓 education timeline node

## Asset gaps

These are NOT in the bundle (sit behind the running backend at `/uploads/*`):

- Profile portrait
- Hero background photograph
- Company logos (Global, Y-Tree, Upp, Pivotal, UMPG)

Wire them in by replacing the `.sr-photo-placeholder`, `.sr-hero__bg`, and
`.sr-tl__logo` content with real `<img src="/uploads/...">` elements.

## Career data (verified against simonrowe.dev / public sources)

1. **Head of Engineering** — Global · Commercial Trading · Aug 2021 – Present
2. **Lead Engineer** — Y-Tree · Wealthtech · Jan 2019 – Jul 2021
3. **Senior Engineer** — Upp Technologies · AdTech · Mar 2016 – Dec 2018
4. **Engineer (Pivotal Labs)** — Pivotal Software · Sep 2014 – Feb 2016
5. **Software Engineer** — Universal Music Publishing Group · Jul 2011 – Aug 2014
6. **BSc Computer Science** — University · 2008 – 2011

Replace the placeholder bullets / locations / achievements in
`ui_kits/website/CareerTimeline.jsx` with real values from Simon's CV.
