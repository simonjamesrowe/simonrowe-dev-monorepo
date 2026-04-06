# Quickstart: Landing Page AI Redesign

**Branch**: `015-landing-ai-redesign`

## Prerequisites

- Node.js (latest LTS)
- Frontend dependencies installed: `cd frontend && npm install`
- Backend running (for chat WebSocket and profile API): `./scripts/start-backend.sh`
- Environment variables configured in `frontend/.env` (VITE_RECAPTCHA_SITE_KEY for reCAPTCHA)

## Development

```bash
# Start frontend dev server
./scripts/start-frontend.sh
# Or directly:
cd frontend && npm run dev
```

Visit `http://localhost:5173` to see the landing page.

## Files to Modify

1. **`frontend/src/contexts/ChatContext.tsx`** (NEW) — Create shared chat state context
2. **`frontend/src/App.tsx`** — Wrap PublicLayout with ChatProvider; render ChatPanel and RecaptchaGate at layout level
3. **`frontend/src/pages/HomePage.tsx`** — Remove local chat state; consume ChatContext
4. **`frontend/src/components/home/HeroSection.tsx`** — Restructure to centered single-column layout
5. **`frontend/src/components/layout/TopNav.tsx`** — Add "Ask AI" button
6. **`frontend/src/styles.css`** — Update hero styles (centered layout), add Ask AI button styles

## Testing

```bash
cd frontend && npm test
```

Key test files to update:
- `frontend/tests/components/home/HeroSection.test.tsx` (if exists)
- Any tests that reference the two-column hero layout or chat state in HomePage

## Verification Checklist

- [ ] Homepage hero displays centered single-column layout with chat input
- [ ] Suggested prompt chips appear below chat input
- [ ] Social links and CV download visible in hero
- [ ] "Ask AI" button visible in top nav on all pages
- [ ] "Ask AI" button triggers reCAPTCHA gate (if not verified)
- [ ] Chat panel opens after verification from any page
- [ ] Layout renders correctly on mobile (320px–768px)
- [ ] "Ask AI" button shows icon-only on very narrow viewports
- [ ] About and CTA sections still render below hero
- [ ] Existing tests pass (with updates for new structure)
