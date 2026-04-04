# Research: Portfolio Website Redesign

**Feature**: 001-website-redesign
**Date**: 2026-04-03

## R-001: Design System Translation (Tailwind → Plain CSS BEM)

**Decision**: Convert all Tailwind CSS classes from the stitch design mockups into plain CSS with BEM naming conventions and CSS custom properties, stored in a single `styles.css` file.

**Rationale**: The constitution (Principle II) mandates plain CSS with BEM naming and a single `styles.css` file. No CSS framework (Tailwind, Bootstrap) may be introduced. The stitch HTML files use Tailwind for prototyping convenience, but production code must use hand-written CSS.

**Alternatives considered**:
- Importing Tailwind as a build dependency — rejected, violates constitution
- CSS Modules — rejected, constitution requires single `styles.css`
- Keeping Tailwind class names as BEM equivalents — rejected, BEM is more semantic

**How to apply**: Extract all Tailwind utility values (colors, spacing, fonts, borders, shadows, blur, gradients) from the stitch HTML and DESIGN.md, then map them to CSS custom properties. Build BEM component classes that apply these properties. The existing `styles.css` color variables will be replaced with the new "Precision Luminescence" palette.

## R-002: Icon Library Translation (Material Symbols → Lucide React)

**Decision**: Replace all Material Symbols Outlined icons from the stitch designs with equivalent Lucide React icons.

**Rationale**: The constitution (Principle II) mandates Lucide React as the sole icon library. The stitch designs use Google Material Symbols for prototyping.

**Alternatives considered**:
- Material Symbols Outlined — rejected, violates constitution
- Mixing both libraries — rejected, constitution prohibits other icon libraries

**Icon mapping**:
| Material Symbol | Lucide React Equivalent |
|----------------|------------------------|
| account_circle | UserCircle |
| download | Download |
| link | Link |
| share | Share2 |
| bolt | Zap |
| arrow_forward | ArrowRight |
| terminal | Terminal |
| cloud | Cloud |
| search | Search |
| menu | Menu |

## R-003: Navigation Architecture Change (Sidebar → Top Navigation)

**Decision**: Replace the current collapsible sidebar navigation with a fixed top navigation bar (glassmorphic style) and a mobile hamburger menu. Transition from single-page scroll-based navigation to multi-page routing.

**Rationale**: The stitch designs show a horizontal top navigation bar with glassmorphism effect on all pages. The current sidebar approach does not match the new design direction.

**Alternatives considered**:
- Keeping sidebar and just restyling — rejected, fundamentally different layout in designs
- Hybrid sidebar + top nav — rejected, designs clearly show top-nav only

**Impact**:
- `Sidebar.tsx` will be replaced with a new `TopNav.tsx` component
- `MobileMenu.tsx` will be restyled to match the new design but can retain its slide-in pattern
- Homepage sections (About, Experience, Skills, Contact) become separate pages/routes
- Blog already has its own route (`/blogs`) — retain and restyle

## R-004: Page Architecture Change (Single-Page → Multi-Page)

**Decision**: Split the current single-page `HomePage.tsx` (which contains Profile, About, Experience, Skills, Blog Preview, Contact sections) into separate page routes matching the top navigation structure.

**Rationale**: The stitch designs show distinct pages for Home, Experience/Skills, Blog, and Profile/Contact, each with their own hero sections and layouts. The current single-page scroll approach doesn't match.

**New routing structure**:
- `/` — Homepage (hero + AI chat + stats bento grid + CTA)
- `/experience` — Experience & Skills page (role timeline + expertise grid)
- `/blogs` — Blog listing (existing route, restyled)
- `/blogs/:id` — Blog detail (existing route, restyled)
- `/profile` — Profile & Contact page (bio + contact form)
- `/admin/*` — Admin routes (unchanged routing, restyled dashboard)

**Impact on existing routes**:
- `/jobs/:jobId` and `/skills-groups/:groupId` drawer routes — these will be adapted to work within the new Experience page context
- `/blogs` and `/blogs/:id` — retained, restyled

## R-005: AI Chat Module Placement

**Decision**: Reposition the AI chat from a slide-in drawer to an embedded module on the homepage hero section (right column), while retaining the existing WebSocket streaming implementation.

**Rationale**: The stitch homepage design shows the AI chat prominently in the hero section as a card-like module, not as a drawer overlay. This increases discoverability and matches the "AI-native" design philosophy.

**Alternatives considered**:
- Keep as drawer, add a small preview widget — rejected, designs clearly show embedded module
- Build a new chat from scratch — rejected, existing WebSocket + STOMP implementation works

**Impact**: The `ChatPanel.tsx` component will be refactored into two variants: a homepage-embedded compact module and a full conversation view (potentially expanding from the module or opening as a page/modal).

## R-006: Admin Dashboard Analytics

**Decision**: Implement the admin dashboard KPI section as a visual redesign of the existing admin layout, with analytics data displayed where available from existing endpoints and placeholder states where real analytics integration doesn't yet exist.

**Rationale**: No dedicated analytics backend exists. The existing `/api/admin/data-operations/status` provides some operational data, and blog/job/skill counts can be derived from existing list endpoints. Full analytics (active visitors, conversion rate, traffic distribution) would require a new analytics service which is out of scope for the visual redesign.

**Alternatives considered**:
- Build a full analytics backend — rejected, scope creep beyond visual redesign
- Omit dashboard metrics entirely — rejected, designs show them prominently

**How to apply**: KPI cards show real data where available (blog count, skill count, job count) and show placeholder/empty states for metrics requiring external analytics integration (active visitors, conversion rate). Document this as a follow-up feature.

## R-007: CSS Custom Properties — New Design Token Mapping

**Decision**: Replace the current CSS custom properties with the full "Precision Luminescence" token set from DESIGN.md.

**Current tokens** → **New tokens**:
| Current | New | Value |
|---------|-----|-------|
| `--color-bg` | `--surface` | #0f131c |
| `--color-panel` | `--surface-container` | #1c2029 |
| `--color-text` | `--on-surface` | #dfe2ef |
| `--color-muted` | `--on-surface-variant` | #bec8cf |
| `--color-border` | `--outline-variant` | #3e484e |
| `--color-accent` | `--primary` | #77d1ff |
| `--color-accent-hover` | `--primary-container` | #299bca |
| (new) | `--secondary` | #ffb690 |
| (new) | `--surface-container-low` | #181c25 |
| (new) | `--surface-container-high` | #262a33 |
| (new) | `--surface-container-lowest` | #0a0e17 |
| (new) | `--surface-bright` | #353943 |

## R-008: Contact Form Field Changes

**Decision**: Adapt the contact form to match the stitch design fields (Name, Email, Subject, Message) while preserving the existing React Hook Form + Zod validation pattern.

**Rationale**: The stitch Profile/Contact design shows a 4-field form (Name, Email, Subject, Message) vs. the current 5-field form (First Name, Last Name, Email, Subject, Message). The name field will be consolidated.

**Impact**: Minor schema update — combine firstName/lastName into a single name field, or keep backend compatibility by splitting a single frontend name field into first/last on submission.
