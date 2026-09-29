# PROTOTYPE: nav redesign mockups

Throwaway code. It exists to answer one question: **what should the new header, menus, "Ask Simon"
affordance and portfolio section look like?** Do not merge this folder into `main`. Fold the
winning variant into real components (with tests) and delete the rest.

## Run it

```bash
cd frontend
VITE_API_BASE_URL= VITE_PROXY_TARGET=https://api.simonrowe.dev npx vite --port 5199
```

That proxies `/api` and `/uploads` to production, so every page shows real content. Only
same-origin GET requests are made, so production CORS never sees them. Chat (`/ws`) is proxied
too and **talks to the production assistant**.

Open <http://localhost:5199/?variant=A>. Use the pink-outlined bar at the bottom, or the ← / →
keys, to switch between:

| Key | Variant |
| --- | --- |
| `A` | Floating capsule + small dropdowns, floating "Ask Simon anything" pill, full-screen accordion sheet on mobile, numbered 01–04 carousel |
| `B` | Full-width bar, transparent over the hero, with mega-menu panels (latest posts, portfolio silhouettes); bottom tab bar + bottom sheets on mobile; bento portfolio grid |
| `C` | Ask-first: search + chat merged into one centre command bar, every menu behind one "Menu" button that opens a typographic index; editorial numbered list |
| `current` | Today's site, for comparison |

Every variant except `current` also swaps the landing hero (`HeroPrototype.tsx`): the photo at
full strength, a left-aligned two-line statement and a "Try the AI assistant" pill in place of
the multi-line chat box. The headline and lede are placeholder copy.

The choice sticks in sessionStorage while you click around. `/prototype/portfolio` is the stand-in
"All projects" page.

## What is fake

- The portfolio is four in-memory rows in `data.ts`, all `COMING_SOON`; the real build reads them
  from the CMS. Taglines are placeholders, and Clinician's Veil's is deliberately blank.
- Silhouettes are CSS + a Lucide glyph (`Silhouette.tsx`), not uploaded artwork.
- No tests, and no focus trapping in the sheets.
- `vite.config.ts` gained `VITE_PROXY_TARGET`. Without it, the proxy still targets localhost.
