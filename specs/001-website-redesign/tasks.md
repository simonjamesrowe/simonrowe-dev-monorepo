# Tasks: Portfolio Website Redesign — "Precision Luminescence"

**Input**: Design documents from `/specs/001-website-redesign/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Not explicitly requested — test tasks omitted. Test updates included in Polish phase.

**Organization**: Tasks grouped by user story. US6 (Design System) is foundational and handled in Phase 2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: Prepare the project for the redesign — no visual changes yet

- [x] T001 Create new component directories: `frontend/src/components/home/`, `frontend/src/components/experience/`, `frontend/src/components/blog/`, `frontend/src/components/profile/`
- [x] T002 Read and document the complete design token set from `stitch/cyber_sentinel/DESIGN.md` and all `stitch/*/code.html` files to create a reference mapping of Tailwind classes to CSS custom properties

**Checkpoint**: Project structure ready for implementation

---

## Phase 2: Foundational — Design System & Navigation (US6 + Layout Shell)

**Purpose**: Implement the "Precision Luminescence" design system and navigation shell that ALL user stories depend on. Maps to User Story 6 (Design System & Visual Consistency, P1) and the layout prerequisites from the plan.

**CRITICAL**: No page-level user story work can begin until this phase is complete.

### Design System (US6)

- [x] T003 [US6] Replace all CSS custom properties in `frontend/src/styles.css` with the Precision Luminescence design tokens: surface palette (`--surface`: #0f131c, `--surface-container`: #1c2029, `--surface-container-low`: #181c25, `--surface-container-high`: #262a33, `--surface-container-lowest`: #0a0e17, `--surface-bright`: #353943), text tokens (`--on-surface`: #dfe2ef, `--on-surface-variant`: #bec8cf), accent tokens (`--primary`: #77d1ff, `--primary-container`: #299bca, `--secondary`: #ffb690), and outline (`--outline-variant`: #3e484e). Update `--font-heading` to Space Grotesk and `--font-main` to Inter with correct weight imports (Inter: 300-600, Space Grotesk: 300, 500, 700)
- [x] T004 [US6] Add new BEM base component classes in `frontend/src/styles.css`: `.glass-panel` (background rgba(15,19,28,0.6) with backdrop-filter blur 20px), `.button--primary` (gradient from --primary to --primary-container at 135deg, rounded-xl), `.button--secondary` (ghost border with outline-variant at 20% opacity), `.button--tertiary` (text-only with primary color and hover underline), `.card` (surface-container background with ghost border at 15% opacity, rounded-3xl), `.chip` (surface-container-high background with ghost border, pill shape), `.input-field` (surface-container-lowest background, focus ring with primary at 40% and 4px glow)
- [x] T005 [US6] Add typography scale classes in `frontend/src/styles.css`: `.display-lg` (3.5rem Space Grotesk bold), `.headline-lg` (2.5rem Space Grotesk bold), `.headline-md` (2rem Space Grotesk bold), `.title-lg` (1.5rem Inter semibold), `.body-lg` (1rem Inter, line-height 1.6), `.label-md` (0.875rem Inter medium), `.label-sm` (0.75rem Inter). Add elevation classes: ambient shadow for modals (`box-shadow: 0 20px 50px rgba(0,0,0,0.3)`), AI glow for active states (`box-shadow: 0 0 15px rgba(119,209,255,0.2)`)
- [x] T006 [US6] Add responsive breakpoint media queries in `frontend/src/styles.css` for the new layout: 1440px (max content width), 1024px (tablet/two-column to single-column transition), 768px (mobile menu trigger), 375px (small mobile adjustments). Remove old sidebar-related CSS variables (`--sidebar-width`, `--sidebar-width-collapsed`) and all `.sidebar` BEM classes

### Navigation & Layout Shell

- [x] T007 Create `frontend/src/components/layout/TopNav.tsx`: fixed position glassmorphic top bar (height 80px) with "The Digital Architect" branding (Space Grotesk bold), navigation links (Experience, Skills, Blog, Admin) using React Router `NavLink`, and a `UserCircle` Lucide icon button. Apply `.glass-panel` class. Hide nav links on mobile (below 768px). Reference design: `stitch/portfolio_home/screen.png` top section
- [x] T008 Create `frontend/src/components/layout/Footer.tsx`: site footer with "The Digital Architect" branding, copyright text "© 2024 The Digital Architect. Precision Luminescence.", and social links (GitHub, LinkedIn, Documentation). Use subtle top border via outline-variant at 15% opacity. Reference design: `stitch/portfolio_home/code.html` footer section
- [x] T009 Restyle `frontend/src/components/layout/MobileMenu.tsx`: update to use new design tokens, hamburger icon trigger using Lucide `Menu` icon, slide-in panel with dark surface-container-low background, nav items matching TopNav links with proper spacing and typography
- [x] T010 Add BEM styles for `.top-nav`, `.top-nav__brand`, `.top-nav__links`, `.top-nav__link`, `.top-nav__link--active`, `.footer`, `.footer__brand`, `.footer__links`, and updated `.mobile-menu` classes in `frontend/src/styles.css`
- [x] T011 Update `frontend/src/App.tsx`: remove Sidebar import and rendering, add TopNav and Footer as persistent layout wrapper around all public routes, add new routes (`/experience` for ExperiencePage, `/profile` for ProfilePage), keep existing routes (`/blogs`, `/blogs/:id`, `/admin/*`), remove `/jobs/:jobId` and `/skills-groups/:groupId` as top-level routes (will be handled within ExperiencePage)

**Checkpoint**: Design system tokens applied, navigation shell renders on all pages. All subsequent pages will inherit the correct design language.

---

## Phase 3: User Story 1 — Visitor Browses the Portfolio Homepage (Priority: P1) MVP

**Goal**: Deliver a fully redesigned homepage with hero section, AI chat module, stats bento grid, and CTA section matching the `stitch/portfolio_home/` designs.

**Independent Test**: Load `/` and verify hero, AI chat module, stats grid, and CTA render correctly on desktop (1440px) and mobile (375px).

### Implementation for User Story 1

- [x] T012 [P] [US1] Create `frontend/src/components/home/HeroSection.tsx`: full-height hero with decorative background blur circles (primary/10 and secondary/5), two-column grid (7/5 split on desktop). Left column: chip label "Engineering Leadership // AI-Native Systems", `<h1>` with "Simon Rowe" (Rowe in primary color), tagline paragraph, Download CV gradient button (links to `/api/resume`), View GitHub ghost button, social icon links (Link, Share2 from Lucide). Reference design: `stitch/portfolio_home/code.html` hero section. Add `.hero`, `.hero__content`, `.hero__tagline`, `.hero__actions`, `.hero__chip` BEM classes in `frontend/src/styles.css`
- [x] T013 [P] [US1] Create `frontend/src/components/home/AIChatModule.tsx`: compact embedded chat card for the hero right column. Outer card with surface-container-low rounded-3xl, inner card with surface-container-lowest. Header with pulsing green dot, "SimonAI Interface" label, Zap icon. Chat area with greeting message bubble, two suggested prompt chips ("Tell me about his AI stack", "Leadership philosophy?"). Text input with send button (ArrowRight icon, primary background). Reuse existing `chatService` WebSocket connection for message sending/receiving. Add `.chat-module`, `.chat-module__header`, `.chat-module__message`, `.chat-module__prompts`, `.chat-module__input` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/portfolio_home/code.html` AI chat section
- [x] T014 [P] [US1] Create `frontend/src/components/home/StatsGrid.tsx`: bento grid section with 4-column responsive grid. Cards: (1) 2-col professional summary card with "The Vision" heading, description text, author avatar + name + title; (2) years experience stat card "12+" with "Years Experience" label; (3) engineers led stat card "45" with "Engineers Led" label in secondary color; (4) 2x2 sub-grid with Core Stack card (Terminal icon, tech list) and Cloud Native card (Cloud icon, tech list); (5) 2-col background image overlay card "Architecting Scale". Use data from `useProfile()` hook where available. Add `.stats-grid`, `.stats-grid__card`, `.stats-grid__stat`, `.stats-grid__summary` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/portfolio_home/code.html` bento grid section
- [x] T015 [P] [US1] Create `frontend/src/components/home/CTASection.tsx`: centered section with "Let's build the impossible together." heading (italic primary "impossible"), two buttons: "Schedule a Strategy Call" (white/primary solid, links to mailto) and "Explore Work" (ghost border). Add `.cta-section`, `.cta-section__heading`, `.cta-section__actions` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/portfolio_home/code.html` newsletter section
- [x] T016 [US1] Rewrite `frontend/src/pages/HomePage.tsx`: remove all existing section components (ProfileBanner, AboutSection, ExperienceSection, SkillsSection, ResumeDownloadButton, HomepageBlogPreview, ContactSection, TourButton, TourOverlay, ScrollToTop). Compose new page from: HeroSection (with AIChatModule in hero right column), StatsGrid, CTASection. Load profile data via `useProfile()`. Remove old section-scroll navigation logic and drawer route handling
- [x] T017 [US1] Add responsive styles for homepage in `frontend/src/styles.css`: at 1024px hero grid switches to single column (chat module below hero text), stats grid to 2 columns; at 768px stats grid to single column; at 375px full-width padding adjustments. Reference design: `stitch/portfolio_home_mobile/screen.png`

**Checkpoint**: Homepage fully functional with hero, AI chat, stats grid, and CTA. Matches `stitch/portfolio_home/screen.png` on desktop and `stitch/portfolio_home_mobile/screen.png` on mobile.

---

## Phase 4: User Story 2 — Visitor Explores Experience & Skills (Priority: P1)

**Goal**: Deliver a dedicated Experience & Skills page with role timeline and expertise grid matching `stitch/experience_skills/` designs.

**Independent Test**: Navigate to `/experience` and verify the editorial headline, role timeline cards, and expertise chip grid render correctly on desktop and mobile.

### Implementation for User Story 2

- [x] T018 [P] [US2] Create `frontend/src/components/experience/RoleTimeline.tsx`: two-column layout of role cards. Each card shows company logo image, job title (headline-md), company name, date range, and description text. Cards use surface-container background with ghost border. Fetch data from `/api/jobs` endpoint. Add `.role-timeline`, `.role-timeline__card`, `.role-timeline__logo`, `.role-timeline__title`, `.role-timeline__dates`, `.role-timeline__description` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/experience_skills/code.html` experience section
- [x] T019 [P] [US2] Create `frontend/src/components/experience/ExpertiseGrid.tsx`: "The Arsenal of an Architect" section with categorized skill groups. Each group renders as a labeled category with skills displayed as `.chip` pill elements. Core skills use secondary color text (#ffb690) to differentiate. Fetch data from `/api/skills` endpoint. Add `.expertise-grid`, `.expertise-grid__category`, `.expertise-grid__category-title`, `.expertise-grid__chips` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/experience_skills/code.html` expertise section
- [x] T020 [US2] Create `frontend/src/pages/ExperiencePage.tsx`: page shell with editorial headline "Architecting Digital Fortresses." (display-lg, "Digital Fortresses" in primary color), subtitle with name and title. Compose from RoleTimeline and ExpertiseGrid components. Handle loading/error states. Adapt existing job/skill-group detail drawer navigation to work within this page context (keep `/jobs/:jobId` and `/skills-groups/:groupId` as nested routes or inline expansions)
- [x] T021 [US2] Add responsive styles for experience page in `frontend/src/styles.css`: at 1024px role timeline switches to single column, role cards stack vertically; skill chips wrap naturally. Reference design: `stitch/experience_skills_mobile/screen.png`

**Checkpoint**: Experience page fully functional. Matches `stitch/experience_skills/screen.png` on desktop and `stitch/experience_skills_mobile/screen.png` on mobile.

---

## Phase 5: User Story 3 — Visitor Reads the Technical Blog (Priority: P2)

**Goal**: Restyle the blog listing and detail pages with featured article hero, category filters, search, and article card grid matching `stitch/technical_blog/` designs.

**Independent Test**: Navigate to `/blogs` and verify featured article, category filters, search input, article cards, and "Load Archive" pagination render correctly.

### Implementation for User Story 3

- [x] T022 [P] [US3] Create `frontend/src/components/blog/FeaturedArticle.tsx`: large two-column card (image left, content right) for the latest/featured blog post. Shows "FEATURED ARTICLE" label, category chip, title (headline-lg), excerpt text, "Read Detailed Analysis" link with arrow. Surface-container background, rounded-3xl. Add `.featured-article`, `.featured-article__image`, `.featured-article__content`, `.featured-article__label`, `.featured-article__title` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/technical_blog/code.html` featured section
- [x] T023 [P] [US3] Create `frontend/src/components/blog/CategoryFilters.tsx`: horizontal bar of filter chip buttons (All, Architecture, AI-Native, Engineering Management, etc.) derived from existing tags via `/api/search` or tag data. Active chip uses primary background, inactive use surface-container-high with ghost border. Include a search input (Search icon from Lucide) on the right. Add `.category-filters`, `.category-filters__chip`, `.category-filters__chip--active`, `.category-filters__search` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/technical_blog/code.html` filter bar
- [x] T024 [P] [US3] Create `frontend/src/components/blog/ArticleCard.tsx`: blog card with thumbnail image (top), category label, date, title (title-lg), excerpt text, and "View Post" link. Surface-container background, rounded-2xl. Add `.article-card`, `.article-card__image`, `.article-card__meta`, `.article-card__title`, `.article-card__excerpt`, `.article-card__link` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/technical_blog/code.html` article cards
- [x] T025 [US3] Rewrite `frontend/src/pages/BlogListingPage.tsx`: "Technical Luminescence." headline (display-lg, "Luminescence" in primary color), subtitle text. Compose from CategoryFilters, FeaturedArticle (first/latest post), and a 3-column grid of ArticleCards. Implement category filtering (client-side filter by tag), search input integration (use existing `/api/search/blogs` endpoint), and "Load Archive" button for pagination (load next batch of posts). Retain existing data fetching logic, adapt to new component structure
- [x] T026 [US3] Restyle `frontend/src/pages/BlogDetailPage.tsx`: apply new design tokens, set max-width 720px reading rail centered, code blocks with surface-container-lowest background and primary syntax accents, rounded-xl (0.75rem). Update heading and body typography to match design system
- [x] T027 [US3] Add responsive styles for blog pages in `frontend/src/styles.css`: at 1024px article grid to 2 columns, featured article to single column (image above content); at 768px article grid to single column, category chips scroll horizontally with overflow-x auto. Reference design: `stitch/technical_blog_mobile/screen.png`

**Checkpoint**: Blog listing and detail pages fully functional. Matches `stitch/technical_blog/screen.png` on desktop and `stitch/technical_blog_mobile/screen.png` on mobile.

---

## Phase 6: User Story 4 — Visitor Views Profile & Sends Contact Inquiry (Priority: P2)

**Goal**: Deliver a dedicated Profile & Contact page with bio section, career stats, social links, and contact form matching `stitch/profile_contact/` designs.

**Independent Test**: Navigate to `/profile`, verify bio and stats render, submit the contact form with valid data and verify success confirmation.

### Implementation for User Story 4

- [x] T028 [P] [US4] Create `frontend/src/components/profile/BioSection.tsx`: two-column layout — left column: professional photo (grayscale with hover color transition, rounded), skill tags as chips ("Principal Architect", "VP", "AI Native Pioneer"). Right column: "The Architect of Precision Systems" headline (display-lg, "Precision Systems" in primary), bio paragraphs, stats row ("12+" years leadership, "450M+" scale managed). Fetch data from `/api/profile`. Add `.bio-section`, `.bio-section__photo`, `.bio-section__tags`, `.bio-section__headline`, `.bio-section__stats`, `.bio-section__stat-value`, `.bio-section__stat-label` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/profile_contact/code.html` bio section
- [x] T029 [P] [US4] Create `frontend/src/components/profile/SocialLinks.tsx`: vertical list of social links (LinkedIn, GitHub, X/Twitter) with platform icons (Lucide), platform label, and URL/handle text. Add `.social-links`, `.social-links__item`, `.social-links__icon`, `.social-links__label` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/profile_contact/code.html` social section
- [x] T030 [US4] Restyle `frontend/src/components/contact/ContactForm.tsx`: apply new design tokens. Update form layout to two-column grid for Name + Email row, full-width Subject and Message fields. Update submit button text to "Initiate Connection" with ArrowRight icon, gradient primary style. Keep React Hook Form + Zod validation. If consolidating name field: create a single "Name" field in the UI that maps to firstName on submission (set lastName to empty string for backend compatibility). Update `.contact-form` BEM classes in `frontend/src/styles.css` to match new design. Reference design: `stitch/profile_contact/code.html` form section
- [x] T031 [US4] Create `frontend/src/pages/ProfilePage.tsx`: page shell composing BioSection, SocialLinks, and ContactForm in a "Connect" section. Two main areas: bio section (top) and contact section (bottom) with heading "Connect", descriptive text, and social links alongside the form. Handle loading/error states for profile data
- [x] T032 [US4] Add responsive styles for profile page in `frontend/src/styles.css`: at 1024px bio section switches to single column (photo above text), contact form name/email row stays two-column; at 768px all form fields full width, stats stack vertically. Reference design: `stitch/profile_contact_mobile/screen.png`

**Checkpoint**: Profile page fully functional with bio, stats, social links, and working contact form. Matches `stitch/profile_contact/screen.png` on desktop and `stitch/profile_contact_mobile/screen.png` on mobile.

---

## Phase 7: User Story 5 — Admin Dashboard Redesign (Priority: P3)

**Goal**: Restyle the admin console with a new dashboard showing KPI cards, traffic chart, profile management, inquiry list, and top-performing content matching `stitch/admin_dashboard/` designs.

**Independent Test**: Log in as admin, navigate to the dashboard, verify KPI cards, profile management section, inquiry list, and content cards render correctly.

### Implementation for User Story 5

- [x] T033 [P] [US5] Create `frontend/src/components/admin/DashboardMetrics.tsx`: three KPI cards in a row — "Active Visitors" (large number with percentage change badge), "Blog Reads" (count with average time), "Conversion" (percentage, contact form leads). Use placeholder data with empty state fallbacks. Add a "Traffic Distribution" bar chart section with 7 Days / 30 Days toggle (visual placeholder bars using div heights). Add `.dashboard-metrics`, `.dashboard-metrics__kpi`, `.dashboard-metrics__kpi-value`, `.dashboard-metrics__kpi-label`, `.dashboard-metrics__chart`, `.dashboard-metrics__bar` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/admin_dashboard/code.html` metrics section
- [x] T034 [P] [US5] Create `frontend/src/components/admin/InquiryList.tsx`: table component showing contact inquiries with columns: Sender, Subject, Type/Category, Received date, Status badge, Actions. Use placeholder data or fetch from an available endpoint. Add `.inquiry-list`, `.inquiry-list__table`, `.inquiry-list__row`, `.inquiry-list__status`, `.inquiry-list__actions` BEM classes in `frontend/src/styles.css`. Include a "View All" link. Reference design: `stitch/admin_dashboard_updated/code.html` inquiries section
- [x] T035 [P] [US5] Create `frontend/src/components/admin/ProfileManagement.tsx`: profile management card with short biography textarea, two file upload fields (Primary CV Link, Technical Portfolio PDF), profile image preview with "Change Image" overlay and recommendation text. Save button "Save Profile Changes". Wire to existing `/api/admin/profile` PUT endpoint. Add `.profile-mgmt`, `.profile-mgmt__bio`, `.profile-mgmt__uploads`, `.profile-mgmt__image` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/admin_dashboard_updated/code.html` profile section
- [x] T036 [P] [US5] Create `frontend/src/components/admin/TopContent.tsx`: "Top Performing Content" section with content cards showing thumbnail, category badge, title, view count icon, like count icon, and "Trending" badge for top performers. Include a "Draft New Post" card with plus icon linking to blog editor. Add `.top-content`, `.top-content__card`, `.top-content__stats`, `.top-content__draft` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/admin_dashboard/code.html` top content section
- [x] T037 [US5] Restyle `frontend/src/components/admin/AdminLayout.tsx`: replace current top navigation bar with a dark sidebar layout matching the design. Sidebar: user avatar + name at top, navigation items (Dashboard, Content, Profile, Analytics, Messages, Settings) with Lucide icons, "+ New Entry" button at bottom, Logout link. Main content area with "The Digital Architect / Dashboard" header, notification bell icon, and "LIVE NODE" status badge. Add `.admin-layout`, `.admin-sidebar`, `.admin-sidebar__nav`, `.admin-sidebar__item`, `.admin-sidebar__item--active`, `.admin-header` BEM classes in `frontend/src/styles.css`. Reference design: `stitch/admin_dashboard/code.html` layout
- [x] T038 [US5] Create a new admin dashboard page or update the existing admin index route to compose DashboardMetrics, ProfileManagement, InquiryList, TopContent, and a "Recent Insights" activity feed into the dashboard layout. Wire the "+ New Entry" button to navigate to blog creation. Reference design: `stitch/admin_dashboard_updated/screen.png` for the combined layout
- [x] T039 [US5] Add responsive styles for admin dashboard in `frontend/src/styles.css`: at 1024px sidebar collapses to icon-only mode; at 768px sidebar becomes a top hamburger menu, KPI cards stack vertically, inquiry table becomes scrollable horizontally. Reference design: `stitch/admin_dashboard_mobile/screen.png`

**Checkpoint**: Admin dashboard fully functional with KPI cards, profile management, inquiry list, and content overview. Matches `stitch/admin_dashboard_updated/screen.png` on desktop and `stitch/admin_dashboard_mobile/screen.png` on mobile.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Responsive audit, edge case handling, cleanup, and test updates

- [x] T040 [P] Audit all pages at viewport widths 320px, 375px, 768px, 1024px, 1440px, 1920px — fix any layout breaks, horizontal overflow, or content overlap in `frontend/src/styles.css`
- [x] T041 [P] Add graceful fallback for AI chat module connection failure in `frontend/src/components/home/AIChatModule.tsx`: display a static message "Chat is currently unavailable" when WebSocket fails to connect, keep the module visible and non-blocking
- [x] T042 [P] Add missing image placeholder styles in `frontend/src/styles.css`: gradient background fallback for blog cards and role timeline cards when `featuredImage` or `logoUrl` is null
- [x] T043 [P] Add empty/zero state styles for admin dashboard KPI cards in `frontend/src/styles.css`: display "—" or "0" with muted text when no data is available
- [x] T044 Remove unused component files: `frontend/src/components/layout/Sidebar.tsx`, and any old homepage section components that are no longer imported (ProfileBanner, AboutSection, etc.) after verifying no other routes reference them
- [x] T045 Remove old BEM classes from `frontend/src/styles.css` that are no longer referenced: `.sidebar`, `.sidebar--collapsed`, `.sidebar__*`, `.profile-banner`, `.profile-banner__*`, `.about-section`, `.about-section__*`, and other replaced component classes
- [x] T046 [P] Update existing frontend tests in `frontend/tests/` to account for new component structure: update imports, fix broken selectors, ensure routing tests cover `/experience` and `/profile` routes
- [x] T047 Verify all existing functionality works end-to-end: blog browsing with search, AI chat streaming, contact form submission with reCAPTCHA, admin authentication and content management, CV download. Fix any regressions introduced by the redesign

**Checkpoint**: All pages responsive, edge cases handled, old code cleaned up, tests passing, no regressions.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Phase 2 — Homepage
- **User Story 2 (Phase 4)**: Depends on Phase 2 — Experience page (can run in parallel with US1)
- **User Story 3 (Phase 5)**: Depends on Phase 2 — Blog pages (can run in parallel with US1/US2)
- **User Story 4 (Phase 6)**: Depends on Phase 2 — Profile page (can run in parallel with US1/US2/US3)
- **User Story 5 (Phase 7)**: Depends on Phase 2 — Admin dashboard (can run in parallel with others)
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

- **US6 (Design System)**: Foundational — embedded in Phase 2, no story dependencies
- **US1 (Homepage)**: Depends only on Phase 2. No dependencies on other stories.
- **US2 (Experience)**: Depends only on Phase 2. No dependencies on other stories.
- **US3 (Blog)**: Depends only on Phase 2. No dependencies on other stories.
- **US4 (Profile/Contact)**: Depends only on Phase 2. No dependencies on other stories.
- **US5 (Admin Dashboard)**: Depends only on Phase 2. No dependencies on other stories.

### Within Each User Story

- Component files (marked [P]) can be created in parallel within a story
- Page composition task depends on all component tasks for that story
- Responsive styles task depends on the page composition task

### Parallel Opportunities

- T003, T004, T005 (design system CSS) should be done sequentially (same file) but T006 can overlap
- T007, T008, T009 (layout components) can run in parallel (different files)
- T012, T013, T014, T015 (homepage components) can run in parallel
- T018, T019 (experience components) can run in parallel
- T022, T023, T024 (blog components) can run in parallel
- T028, T029 (profile components) can run in parallel
- T033, T034, T035, T036 (admin components) can run in parallel
- All Phase 8 tasks marked [P] can run in parallel

---

## Parallel Example: User Story 1 (Homepage)

```bash
# Launch all homepage components in parallel:
Task: "T012 [P] [US1] Create HeroSection.tsx"
Task: "T013 [P] [US1] Create AIChatModule.tsx"
Task: "T014 [P] [US1] Create StatsGrid.tsx"
Task: "T015 [P] [US1] Create CTASection.tsx"

# Then compose page (depends on all above):
Task: "T016 [US1] Rewrite HomePage.tsx"

# Then responsive (depends on page):
Task: "T017 [US1] Add responsive styles for homepage"
```

---

## Implementation Strategy

### MVP First (Homepage Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (Design System + Navigation)
3. Complete Phase 3: User Story 1 (Homepage)
4. **STOP and VALIDATE**: Homepage renders correctly on desktop and mobile
5. Deploy/demo — site has new navigation, design system, and homepage

### Incremental Delivery

1. Setup + Foundational → Design system and navigation shell ready
2. Add US1 (Homepage) → Test independently → Deploy (MVP!)
3. Add US2 (Experience) → Test independently → Deploy
4. Add US3 (Blog) → Test independently → Deploy
5. Add US4 (Profile/Contact) → Test independently → Deploy
6. Add US5 (Admin Dashboard) → Test independently → Deploy
7. Polish phase → Final audit and cleanup → Deploy

### Parallel Team Strategy

With multiple developers after Phase 2 completes:
- Developer A: US1 (Homepage) then US5 (Admin)
- Developer B: US2 (Experience) then US4 (Profile)
- Developer C: US3 (Blog) then Polish

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [Story] label maps task to specific user story for traceability
- All CSS changes go into the single `frontend/src/styles.css` file — avoid parallel edits to this file
- Each page component references its corresponding `stitch/*/screen.png` and `stitch/*/code.html` for visual fidelity
- Lucide React icons only — use the icon mapping from research.md (R-002)
- No Tailwind — all stitch HTML must be translated to plain CSS BEM
- Commit after each completed task or logical group
- Stop at any checkpoint to validate the story independently
