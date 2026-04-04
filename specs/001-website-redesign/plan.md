# Implementation Plan: Portfolio Website Redesign

**Branch**: `001-website-redesign` | **Date**: 2026-04-03 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-website-redesign/spec.md`

## Summary

Redesign the simonrowe.dev portfolio from a sidebar-navigated single-page app into a multi-page editorial experience branded "The Digital Architect." The implementation is primarily frontend — replacing the CSS design tokens with the "Precision Luminescence" dark palette, converting the sidebar navigation to a glassmorphic top nav bar, splitting the monolithic homepage into dedicated pages (Home, Experience/Skills, Blog, Profile/Contact), restyling the admin dashboard, and embedding the AI chat module on the homepage hero. No backend changes required. All design mockups in `stitch/` are translated from Tailwind prototypes to plain CSS with BEM naming per the constitution.

## Technical Context

**Language/Version**: TypeScript (frontend), Java 21 (backend — no changes)
**Primary Dependencies**: React (latest stable), React Router, Lucide React, React Hook Form, Zod, @stomp/stompjs, React Markdown
**Storage**: MongoDB (unchanged), Elasticsearch (unchanged)
**Testing**: Vitest (frontend), Testcontainers (backend — no changes expected)
**Target Platform**: Web (desktop + mobile responsive, 320px–1920px)
**Project Type**: Web application (frontend redesign only)
**Performance Goals**: Homepage loads hero + AI chat + stats grid within 3 seconds on broadband
**Constraints**: Single `styles.css` file, BEM naming, no CSS frameworks, Lucide React icons only
**Scale/Scope**: 5 public pages + admin dashboard restyled, ~3800 lines of CSS to rewrite

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | No container changes — frontend-only redesign |
| II. Modern Java & React Stack | PASS | Plain CSS + BEM (no Tailwind), Lucide React icons (no Material Symbols), Inter + Space Grotesk fonts, React Hook Form + Zod, reCAPTCHA on contact form |
| III. Quality Gates | PASS | Frontend tests updated for new components, existing backend tests unaffected |
| IV. Observability & Operability | PASS | No backend changes |
| V. Simplicity & Incremental Delivery | PASS | Delivered as independently testable page increments (P1 → P2 → P3) |
| VI. Admin CMS UX Standards | PASS | Admin dashboard restyled but existing CMS patterns preserved |
| VII. Backup & Restore | PASS | No changes to backup/restore |
| VIII. Shell Scripting Standards | PASS | No script changes |

**Post-Phase 1 Re-check**: PASS — no constitution violations in design artifacts.

## Project Structure

### Documentation (this feature)

```text
specs/001-website-redesign/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 research findings
├── data-model.md        # Entity mapping (no schema changes)
├── quickstart.md        # Developer setup guide
├── contracts/           # API contract documentation (no new endpoints)
│   └── README.md
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
frontend/
├── src/
│   ├── styles.css                          # REWRITE: Full design token + component style overhaul
│   ├── App.tsx                             # MODIFY: New routes (/experience, /profile), layout restructure
│   ├── pages/
│   │   ├── HomePage.tsx                    # REWRITE: Hero + AI chat module + bento grid + CTA
│   │   ├── ExperiencePage.tsx              # NEW: Experience timeline + skills expertise grid
│   │   ├── ProfilePage.tsx                 # NEW: Bio + stats + contact form
│   │   ├── BlogListingPage.tsx             # RESTYLE: Featured article, category filters, article cards
│   │   ├── BlogDetailPage.tsx              # RESTYLE: Match new design system
│   │   └── admin/
│   │       └── [existing admin pages]      # RESTYLE: Dashboard metrics, new layout
│   ├── components/
│   │   ├── layout/
│   │   │   ├── TopNav.tsx                  # NEW: Glassmorphic fixed top navigation bar
│   │   │   ├── MobileMenu.tsx              # RESTYLE: Match new design
│   │   │   ├── Footer.tsx                  # NEW: Site footer with branding + links
│   │   │   └── Sidebar.tsx                 # REMOVE: Replaced by TopNav
│   │   ├── home/
│   │   │   ├── HeroSection.tsx             # NEW: Hero with name, tagline, CTAs
│   │   │   ├── AIChatModule.tsx            # NEW: Compact embedded chat for homepage
│   │   │   ├── StatsGrid.tsx              # NEW: Bento grid with stat + capability cards
│   │   │   └── CTASection.tsx              # NEW: "Build the impossible" CTA band
│   │   ├── experience/
│   │   │   ├── RoleTimeline.tsx            # NEW: Editorial role timeline
│   │   │   └── ExpertiseGrid.tsx           # NEW: Categorized skill chip grid
│   │   ├── blog/
│   │   │   ├── FeaturedArticle.tsx         # NEW: Large featured article card
│   │   │   ├── ArticleCard.tsx             # RESTYLE: Blog card with new design
│   │   │   └── CategoryFilters.tsx         # NEW: Filter chip bar
│   │   ├── profile/
│   │   │   ├── BioSection.tsx              # NEW: Photo + bio + stats
│   │   │   └── [existing contact components] # RESTYLE
│   │   ├── chat/
│   │   │   └── ChatPanel.tsx               # MODIFY: Support embedded mode + restyled
│   │   └── admin/
│   │       ├── AdminLayout.tsx             # RESTYLE: New sidebar + top bar design
│   │       ├── DashboardMetrics.tsx         # NEW: KPI cards + traffic chart
│   │       └── InquiryList.tsx             # NEW: Contact inquiry table
│   └── services/                           # NO CHANGES: API + chat services unchanged
└── tests/
    └── [updated tests for new components]
```

**Structure Decision**: Web application structure retained. Changes are exclusively in `frontend/src/`. The backend remains untouched. New pages and components are added to the existing `pages/` and `components/` directories following the established pattern. The current `Sidebar.tsx` is replaced by `TopNav.tsx`. Homepage sections are extracted into dedicated page components.

## Implementation Phases

### Phase 1: Design System Foundation (P1 — Design System user story)

1. **Rewrite CSS custom properties** in `styles.css` — replace all color, typography, shadow, and spacing tokens with the Precision Luminescence palette from DESIGN.md
2. **Define new BEM component classes** for: glassmorphic surfaces (`.glass-panel`), gradient buttons (`.button--primary`), ghost border cards (`.card`), skill chips (`.chip`), input fields with focus glow
3. **Update Google Fonts import** — ensure Inter + Space Grotesk are loaded with correct weights (Inter: 300–600, Space Grotesk: 300, 500, 700)
4. **Remove old sidebar-specific CSS** — all `.sidebar` BEM classes replaced by `.top-nav` classes

### Phase 2: Navigation & Layout Shell (P1 — prerequisite for all pages)

1. **Create `TopNav.tsx`** — fixed glassmorphic top bar with "The Digital Architect" branding, nav links (Experience, Skills, Blog, Admin), and user icon button
2. **Create `Footer.tsx`** — site footer with branding, copyright, and social links
3. **Update `MobileMenu.tsx`** — restyle for new design, hamburger icon trigger
4. **Update `App.tsx`** — add `/experience` and `/profile` routes, wrap pages in new layout (TopNav + Footer), remove Sidebar references

### Phase 3: Homepage (P1 — User Story 1)

1. **Create `HeroSection.tsx`** — large heading, tagline, Download CV + View GitHub buttons, social icon links, decorative background blurs
2. **Create `AIChatModule.tsx`** — compact embedded chat card with greeting, prompt chips, text input; reuses existing `chatService` WebSocket connection
3. **Create `StatsGrid.tsx`** — bento grid with professional summary card, stat counters (years, engineers led), core stack card, cloud native card, background image overlay card
4. **Create `CTASection.tsx`** — "Let's build the impossible together" with action buttons
5. **Rewrite `HomePage.tsx`** — compose from HeroSection + AIChatModule + StatsGrid + CTASection

### Phase 4: Experience & Skills Page (P1 — User Story 2)

1. **Create `ExperiencePage.tsx`** — page shell with editorial headline
2. **Create `RoleTimeline.tsx`** — two-column layout of job cards with company logos, using data from `/api/jobs`
3. **Create `ExpertiseGrid.tsx`** — categorized skill groups as chip collections, using data from `/api/skills`
4. **Handle job/skill group detail navigation** — adapt existing drawer patterns or inline expansion

### Phase 5: Blog Listing Redesign (P2 — User Story 3)

1. **Create `FeaturedArticle.tsx`** — large hero card for the latest/featured blog post
2. **Create `CategoryFilters.tsx`** — horizontal chip bar with category options derived from tags
3. **Create `ArticleCard.tsx`** — blog card with thumbnail, title, excerpt, date, "View Post" link
4. **Rewrite `BlogListingPage.tsx`** — compose with featured article, filters, search input, article grid, "Load Archive" pagination
5. **Restyle `BlogDetailPage.tsx`** — apply new design tokens, reading rail layout (720px max-width)

### Phase 6: Profile & Contact Page (P2 — User Story 4)

1. **Create `ProfilePage.tsx`** — page shell
2. **Create `BioSection.tsx`** — two-column layout with professional photo, bio text, career stats, skill tags, social links
3. **Restyle `ContactForm.tsx`** — apply new design tokens, update form field layout to match stitch design (Name, Email, Subject, Message in two-column grid where applicable)
4. **Update contact form schema if needed** — consolidate first/last name or keep backward-compatible

### Phase 7: Admin Dashboard Redesign (P3 — User Story 5)

1. **Restyle `AdminLayout.tsx`** — new sidebar with dark design, "The Digital Architect / Dashboard" header
2. **Create `DashboardMetrics.tsx`** — KPI cards (active visitors, blog reads, conversion rate) with placeholder data, traffic distribution chart placeholder
3. **Create `InquiryList.tsx`** — contact inquiry table with sender, subject, type, date, status columns
4. **Add profile management section** — biography editor, CV upload fields, profile image preview
5. **Restyle top-performing content section** — blog cards with view counts and trending indicators

### Phase 8: Responsive Polish & Testing

1. **Audit all pages** at breakpoints: 320px, 375px, 768px, 1024px, 1440px, 1920px
2. **Fix layout issues** — ensure single-column stacking on mobile, proper touch targets, no horizontal overflow
3. **Update frontend tests** — adapt existing tests for new component structure, add tests for new page components
4. **Visual regression check** against stitch mockups for each page

## Complexity Tracking

No constitution violations. No complexity tracking needed.
