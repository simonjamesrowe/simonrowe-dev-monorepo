# Quickstart: Portfolio Website Redesign

**Feature**: 001-website-redesign
**Date**: 2026-04-03

## Prerequisites

- Node.js (latest LTS)
- Java 21
- Docker & Docker Compose (for MongoDB, Elasticsearch, Kafka)
- Auth0 credentials configured in `backend/.env`
- Google Gemini API key configured in `backend/.env` (for AI chat)

## Setup

```bash
# 1. Start infrastructure (MongoDB, Elasticsearch, Kafka)
docker compose up -d

# 2. Restore sample data
./scripts/restore.sh

# 3. Start backend + frontend
./scripts/start.sh
```

- Backend runs at http://localhost:8080
- Frontend runs at http://localhost:5173

## Design References

All design mockups and the design system specification are in the `stitch/` directory:

| Design | Desktop | Mobile |
|--------|---------|--------|
| Homepage | `stitch/portfolio_home/screen.png` | `stitch/portfolio_home_mobile/screen.png` |
| Experience & Skills | `stitch/experience_skills/screen.png` | `stitch/experience_skills_mobile/screen.png` |
| Technical Blog | `stitch/technical_blog/screen.png` | `stitch/technical_blog_mobile/screen.png` |
| Profile & Contact | `stitch/profile_contact/screen.png` | `stitch/profile_contact_mobile/screen.png` |
| Admin Dashboard | `stitch/admin_dashboard/screen.png` | `stitch/admin_dashboard_mobile/screen.png` |
| Admin (Updated) | `stitch/admin_dashboard_updated/screen.png` | — |
| Design System | `stitch/cyber_sentinel/DESIGN.md` | — |

Each design directory also contains a `code.html` file with reference Tailwind HTML that must be translated to plain CSS + BEM.

## Key Files to Modify

### Frontend (primary changes)

| File | Change |
|------|--------|
| `frontend/src/styles.css` | Replace CSS custom properties with new design tokens, rewrite all component styles |
| `frontend/src/App.tsx` | Update routing — add `/experience` and `/profile` routes, restructure layout |
| `frontend/src/pages/HomePage.tsx` | Rewrite — hero section, AI chat module, bento grid, CTA only |
| `frontend/src/pages/BlogListingPage.tsx` | Restyle — featured article, category filters, article cards |
| `frontend/src/components/layout/Sidebar.tsx` | Replace with `TopNav.tsx` — glassmorphic fixed top navigation |
| `frontend/src/components/layout/MobileMenu.tsx` | Restyle to match new design |
| `frontend/src/components/chat/ChatPanel.tsx` | Refactor — embedded homepage module variant |
| `frontend/src/components/contact/ContactForm.tsx` | Restyle — new field layout, updated design tokens |

### New Files

| File | Purpose |
|------|---------|
| `frontend/src/pages/ExperiencePage.tsx` | New dedicated Experience & Skills page |
| `frontend/src/pages/ProfilePage.tsx` | New dedicated Profile & Contact page |
| `frontend/src/components/layout/TopNav.tsx` | New top navigation bar component |

### Backend (minimal changes)

No backend code changes required for the visual redesign. If contact form field consolidation (firstName+lastName → name) is pursued, a minor DTO update would be needed.

## Development Workflow

1. Start with the design system — update `styles.css` custom properties and base styles
2. Build the `TopNav` component and update `App.tsx` routing
3. Implement pages in priority order: Homepage → Experience → Blog → Profile → Admin
4. Test responsive behavior at each step using the mobile mockups as reference
5. Run `cd frontend && npm test` to verify no regressions

## Constitution Compliance Checklist

- [ ] All styles in single `styles.css` with BEM naming
- [ ] CSS custom properties for all design tokens
- [ ] Lucide React icons only (no Material Symbols)
- [ ] Inter + Space Grotesk via Google Fonts
- [ ] React Hook Form + Zod on contact form
- [ ] reCAPTCHA on contact form
- [ ] No CSS frameworks imported
