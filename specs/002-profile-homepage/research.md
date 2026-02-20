# Research: Profile & Homepage

**Feature**: 002-profile-homepage
**Date**: 2026-02-21
**Status**: Complete

## Decision Log

### RD-001: React Smooth Scroll Implementation

**Options considered**:

1. **react-scroll** - Dedicated smooth scrolling library with `Link` and `Element` components, scroll events, and spy functionality.
2. **Native `scrollIntoView` with `behavior: 'smooth'`** - Built-in browser API, no additional dependency.
3. **react-router-hash-link** - Extends React Router `Link` to support hash-based scrolling.

**Decision**: **Native `scrollIntoView`** with `behavior: 'smooth'`

**Rationale**: The native API is supported in all modern browsers and requires zero additional dependencies. The existing react-ui uses `react-router-hash-link`, but this adds an unnecessary dependency when native `scrollIntoView` provides the same behavior. Constitution Principle V (Simplicity) favours fewer dependencies. If the homepage sections are identified by `id` attributes, a simple `document.getElementById(sectionId)?.scrollIntoView({ behavior: 'smooth' })` call is sufficient. For the sidebar nav, we wrap this in an `onClick` handler rather than introducing a library abstraction.

**Trade-offs**: No scroll-spy (active section highlighting) out of the box, but this can be achieved with a lightweight `IntersectionObserver` if needed later.

---

### RD-002: Responsive Sidebar / Mobile Menu Pattern

**Options considered**:

1. **CSS-only toggle** - Use a checkbox hack or CSS `:target` to toggle sidebar visibility.
2. **React state with CSS transitions** - Toggle a boolean state that applies CSS classes for slide-in/slide-out animation.
3. **Material UI Drawer** - Pre-built responsive drawer component.

**Decision**: **React state with CSS transitions**

**Rationale**: The existing react-ui uses jQuery to toggle a `port_menu_open` class on `<body>`. The new implementation replaces jQuery with React state management (`useState`) and applies CSS `transform` / `transition` properties for the slide animation. This keeps the bundle small, avoids a heavy UI framework dependency for a single component, and meets the 300ms animation requirement (SC-006). The sidebar is always rendered in the DOM (for accessibility and SEO) but visually hidden on mobile via CSS.

**Implementation sketch**:
- `Sidebar.tsx` renders the full sidebar at all viewport sizes.
- On mobile (detected via CSS media query or `useMediaQuery` hook), the sidebar is positioned off-screen with `transform: translateX(-100%)`.
- `MobileMenu.tsx` renders a hamburger toggle button visible only on mobile.
- Clicking the toggle sets `isMenuOpen` state, which applies `transform: translateX(0)` with `transition: transform 0.3s ease`.

---

### RD-003: Profile Image Optimization

**Options considered**:

1. **`srcSet` with responsive image formats** - Serve multiple resolutions, let browser pick.
2. **Lazy loading with `loading="lazy"`** - Defer off-screen image loading.
3. **Next.js-style Image component** - Automatic optimization (not applicable; using Vite/React).
4. **CSS `background-image` with media queries** - Different background images for mobile vs desktop.

**Decision**: **Combination of `loading="lazy"`, `srcSet` where image formats are available, and CSS media queries for background images**

**Rationale**: The existing data model stores images with multiple format variants (`thumbnail`, `small`, `medium`, `large`). The new backend will serve image URLs that the frontend can use in `srcSet` attributes. The hero background image uses CSS `background-image` with a media query to swap `mobileBackgroundImage` on small viewports (matching FR-009). Profile and sidebar images use `<img>` tags with `loading="lazy"` for below-the-fold content and `srcSet` for resolution selection. This approach requires no additional libraries.

**Image fields from data model**:
- `profileImage` - About section, use `small` format for card, `medium` for expanded view
- `sidebarImage` - Sidebar navigation avatar
- `backgroundImage` - Hero banner desktop, use `large` format
- `mobileBackgroundImage` - Hero banner mobile

---

### RD-004: Markdown Rendering for Description

**Options considered**:

1. **react-markdown** - Popular, well-maintained, renders Markdown to React components. Supports remark/rehype plugins.
2. **marked + DOMPurify** - Parse markdown to HTML string, sanitize, then inject via `dangerouslySetInnerHTML`.
3. **MDX** - Markdown with JSX support (overkill for rendering stored content).

**Decision**: **react-markdown**

**Rationale**: The existing react-ui already uses `react-markdown` with custom component overrides (e.g., `ExternalLink` for `<a>` tags). It renders Markdown as React components directly, avoiding `dangerouslySetInnerHTML` entirely, which satisfies FR-020 (sanitize and safely render user-generated content). It supports standard Markdown features required by FR-003 (bold, italic, links, lists, paragraphs) out of the box. The `rehype-sanitize` plugin can be added for an extra layer of protection against malformed input.

**Custom component overrides**:
- `a` -> opens in new tab with `target="_blank"` and `rel="noopener noreferrer"`
- Potential future: `code` -> syntax highlighting (for blog content, not profile)

---

### RD-005: Analytics Tracking Approach

**Options considered**:

1. **Google Analytics 4 (GA4) via react-ga4** - Existing approach in react-ui. Page views and custom events.
2. **OpenTelemetry Web SDK** - Unified observability approach aligned with backend.
3. **Plausible / PostHog** - Privacy-focused alternatives.
4. **Custom event tracking via backend API** - Send events to backend, store in MongoDB.

**Decision**: **Google Analytics 4 (GA4) via react-ga4** for initial implementation

**Rationale**: The existing site uses GA4 and Hotjar. GA4 satisfies FR-017 (track visitor behavior) and SC-010 (95% capture rate). Using `react-ga4` is a well-understood, lightweight integration. Custom events will be fired for navigation clicks, CV download clicks, and social media link clicks. Page view tracking is automatic with GA4. OpenTelemetry on the frontend is complementary but addresses backend observability concerns (Principle IV) rather than visitor analytics.

**Events to track**:
- `page_view` - automatic on route changes
- `navigate_section` - when sidebar nav item is clicked (custom event with section name)
- `download_cv` - when Download CV button is clicked
- `social_media_click` - when a social media link is clicked (custom event with platform)
- `contact_expand` - when contact details panel is expanded
- `scroll_to_top` - when scroll-to-top button is clicked

---

### RD-006: Spring Data MongoDB for Profile Document

**Options considered**:

1. **Spring Data MongoDB with `MongoRepository`** - Declarative repository interface, automatic query derivation.
2. **`MongoTemplate` directly** - Lower-level, more control, but more boilerplate.
3. **Spring Data Reactive MongoDB** - Reactive streams for non-blocking I/O.

**Decision**: **Spring Data MongoDB with `MongoRepository`**

**Rationale**: The profile use case is simple: read a single document from the `profiles` collection and read all documents from the `social_medias` collection. `MongoRepository<Profile, String>` with a default `findAll()` (returning the single profile) or `findFirst()` is sufficient. No complex queries, aggregations, or custom projections are needed. Reactive MongoDB adds unnecessary complexity for a simple read-only endpoint on a personal website (Principle V). If future features require reactive patterns, migration is straightforward.

**Repository interfaces**:
```java
public interface ProfileRepository extends MongoRepository<Profile, String> {
    Optional<Profile> findFirstBy();
}

public interface SocialMediaLinkRepository extends MongoRepository<SocialMediaLink, String> {
}
```

---

### RD-007: REST API Design for Profile Endpoint

**Options considered**:

1. **Single combined endpoint** `GET /api/profile` - Returns profile data with embedded social media links.
2. **Separate endpoints** `GET /api/profile` + `GET /api/social-medias` - Mirrors existing Strapi API structure.
3. **GraphQL** - Flexible querying (overkill for this use case).

**Decision**: **Single combined endpoint** `GET /api/profile`

**Rationale**: The frontend always needs both profile data and social media links together to render the homepage. The existing react-ui makes two separate API calls (`/profiles` and `/social-medias`), resulting in two network round-trips and potential race conditions with loading states. A single `GET /api/profile` endpoint that returns the profile object with an embedded `socialMediaLinks` array reduces latency and simplifies the frontend data fetching. This endpoint serves the one-and-only profile for the site (the `ProfileService` calls `findFirstBy()` and attaches all social media links).

**Response shape**:
```json
{
  "name": "Simon Rowe",
  "firstName": "Simon",
  "lastName": "Rowe",
  "title": "Engineering Leader",
  "headline": "PASSIONATE ABOUT...",
  "description": "I am driven to achieve...",
  "profileImage": { "url": "...", "width": 400, "height": 400 },
  "sidebarImage": { "url": "...", "width": 100, "height": 100 },
  "backgroundImage": { "url": "...", "width": 1920, "height": 1080 },
  "mobileBackgroundImage": { "url": "...", "width": 750, "height": 1334 },
  "location": "London",
  "phoneNumber": "+447909083522",
  "primaryEmail": "simon.rowe@gmail.com",
  "secondaryEmail": "",
  "cvUrl": "/api/resume",
  "socialMediaLinks": [
    {
      "type": "github",
      "name": "Personal Github Account",
      "url": "https://github.com/simonrowe",
      "includeOnResume": true
    }
  ]
}
```

**Trade-off**: If future features need social media links independently (e.g., admin management), a separate endpoint can be added then. YAGNI applies.

---

## Open Questions

None. All decisions are resolved and aligned with the constitution.

## References

- Existing react-ui: `/Users/simonrowe/workspace/simonjamesrowe/react-ui`
- MongoDB backup: `/Users/simonrowe/backups/strapi-backup-20251116_170434`
- Constitution: `/Users/simonrowe/workspace/simonjamesrowe/simonrowe-dev-monorepo/.specify/memory/constitution.md`
- Spec: `/Users/simonrowe/workspace/simonjamesrowe/simonrowe-dev-monorepo/specs/002-profile-homepage/spec.md`
