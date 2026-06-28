# Quickstart: Landing / Profile Cleanup

## Prerequisites

- Local stack running on the original ports (frontend `5173`, backend `8080`).
  See `scripts/start.sh`.

## Build & test

```bash
cd frontend
npm test -- HomePage ProfilePage App AboutSection HeroSection   # targeted
npm run build
```

Prove no tour regression (no backend change expected):

```bash
./gradlew :backend:test --tests '*TourSeedDefaults*'
```

## Manual verification (browser)

1. **Mobile landing** — open `http://localhost:5173/` at a 390×844 viewport:
   - Name, role, short chat-intro line, and chat input are visible.
   - Badge, tagline, and prompt chips are NOT visible.
   - Background photo present; no horizontal scrollbar.
2. **Desktop landing** — open `/` at desktop width:
   - Full hero (badge, name, role, tagline, intro, input, prompt chips) present.
3. **Profile** — open `/profile`:
   - Real "About {firstName}" content and real photo.
   - No "Architect of Precision Systems" text and no fake stats.
   - CV download, social links, and contact form all present and working.
4. **Footer** — visit `/`, `/profile`, `/experience`, `/blogs`, `/news-events`:
   - No footer on any page.
5. **Tour** — start "Take a Tour" and step to the profile and contact steps:
   - Each highlights the correct area, no console errors.

## Done when

- Targeted Vitest suite and `npm run build` pass.
- Tour-seed backend test passes.
- All five manual checks above hold.
