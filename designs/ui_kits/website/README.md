# Website UI kit — simonrowe.dev (public)

A click-thru recreation of the public site. Open `index.html` to see the full
prototype: hero → about → career timeline → blog → chat drawer → footer, plus
working theme toggle and a hash-route for `#chat`.

## Components
- `TopNav.jsx` — fixed glass-blur nav, theme toggle, "Let's chat" CTA
- `Hero.jsx` — full-bleed hero with eyebrow + colored-accent name + CTAs
- `About.jsx` — portrait + first-person bio block
- `CareerTimeline.jsx` — alternating left/right cards on a centered spine
- `BlogGrid.jsx` — 3-up of cards with cover, tags, meta
- `ChatDrawer.jsx` — slide-in right panel with prompt chips, bubbles, composer
- `Footer.jsx` — three-column footer with social row
- `ui.jsx` — shared bits (Button, Pill, IconBtn, Lucide loader)

All assets live one folder up at `../../assets/`. Photos use Unsplash placeholders
since `/uploads/*` lives behind the running backend.
