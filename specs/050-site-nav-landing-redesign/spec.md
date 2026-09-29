# Feature Specification: Site Navigation, Landing Hero and Portfolio

**Feature Branch**: `simonrowe/nukualofa`

**Created**: 2026-09-29

**Status**: Approved

**Input**: Redesign the public site's navigation and landing page, taking cues from adpower.com without
copying it: a floating header with grouped dropdown menus, a full-strength photographic hero with a short
statement and an "Ask Simon anything" pill instead of the multi-line chat box, and a new Portfolio section
whose projects start as "Coming soon" silhouettes. The hero copy and the portfolio must both be editable in
the admin CMS. The approved direction is variant A of the throwaway prototype on this branch
(`frontend/src/prototype/nav-redesign/`).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Find any part of the site from a grouped header (Priority: P1)

A visitor (a recruiter, a hiring manager, an engineer) lands anywhere on the site and uses a compact header
to reach a section. Related pages are grouped under four menus, not seven flat links: **About** (Profile,
Experience, Skills, Contact), **Portfolio** (each project, plus All projects), **Insights** (Blog, News &
Events) and **Under the hood** (MCP server, Platform status). The header stays in place while the page
scrolls.

**Why this priority**: The header is on every page and is the first thing to change. The other stories are
reached through it.

**Independent Test**: From the home page, reach every destination in the four menus with a mouse, with the
keyboard alone, and on a 390px-wide phone, and confirm that each lands on the right page or section.

**Acceptance Scenarios**:

1. **Given** a desktop visitor, **When** they hover over or click a menu, **Then** a panel opens listing that
   group's destinations, each with a one-line description, and closes on Escape, on an outside click, or on
   navigation.
2. **Given** a keyboard user, **When** they tab to a menu and press Enter or Space, **Then** the panel opens,
   its links can be tabbed through in order, and the menu's expanded state is announced to assistive
   technology.
3. **Given** a visitor on any page within a group (for example `/blogs/some-post`), **When** they look at the
   header, **Then** that group's menu is shown as the current section.
4. **Given** a phone-width visitor, **When** they tap the menu button, **Then** a full-screen menu opens with
   search at the top, the four groups as expandable sections, a theme toggle, and a full-width
   "Ask Simon anything" button pinned to the bottom. The page behind it does not scroll.
5. **Given** an administrator, **When** they are signed in, **Then** the header also offers the admin area,
   as it does today.

---

### User Story 2 - Search the site without hunting for it (Priority: P1)

A visitor wants to search blogs, news, jobs and skills with the existing site search. Search stays a visible,
labelled control in the header rather than an unlabelled icon, and it keeps today's behaviour, including
handing a question over to the chat assistant.

**Why this priority**: The prototype reduced search to a magnifying-glass icon, and it read as though search
had been removed. Search is one of the site's main ways in and must not regress.

**Independent Test**: On desktop and on mobile, open search from the header, type a term, see grouped results,
open a result, and hand a question to the chat assistant.

**Acceptance Scenarios**:

1. **Given** a desktop visitor, **When** they look at the header, **Then** they see a search field (not an
   icon alone) that shows its keyboard shortcut.
2. **Given** a visitor on any public page, **When** they press the shortcut (`/`, or Ctrl/⌘+K) outside a text
   field, **Then** the search field takes focus.
3. **Given** an open search, **When** they type, **Then** they get the same grouped results as today's header
   search, and the option to ask the assistant still works.
4. **Given** a phone-width visitor, **When** they open the menu, **Then** the search field is the first
   control in it.

---

### User Story 3 - A landing page that says who Simon is at a glance (Priority: P1)

A first-time visitor on the home page sees the background photograph at full strength, with a short
two-line statement in large type, a sentence under it, a primary and a secondary call to action, a
"Take the tour" link, and an "Ask Simon anything" pill that opens the chat. On a phone, the portrait
photograph is used with the statement at the top and the actions at the bottom, so the image shows between
them.

**Why this priority**: This is the page most visitors arrive on, and the redesign exists for it.

**Independent Test**: Load the home page at 1440, 1024 and 390px wide, in light and dark mode, and confirm the
statement, both calls to action, the tour link and the pill are all present, legible and working.

**Acceptance Scenarios**:

1. **Given** the home page, **When** it loads, **Then** the desktop background image fills the hero without
   being washed out, and the text over it stays legible in both themes.
2. **Given** a phone-width visitor, **When** the home page loads, **Then** the mobile background image is shown
   instead of the desktop one.
3. **Given** the hero, **When** the visitor activates the pill, **Then** the chat assistant opens ready for a
   question.
4. **Given** the visitor has scrolled past the hero's pill, **When** they carry on through the site, **Then** a
   compact "Ask Simon anything" pill stays available in a corner (a sparkle button on phones). The two pills
   are never on screen at the same time.
5. **Given** the hero, **When** the visitor activates "Take the tour", **Then** the guided tour starts. This
   replaces the floating "Take a Tour" button, which is removed.
6. **Given** the name, title and location shown above the statement, **When** the profile is edited, **Then**
   those values follow the profile.

---

### User Story 4 - Edit the landing page copy in the CMS (Priority: P1)

Simon changes the hero's statement, sentence, calls to action and pill wording from the admin area without a
deploy, for example to merge in wording from his LinkedIn profile.

**Why this priority**: The copy is expected to change, and hard-coding it would make every wording tweak a
code change.

**Independent Test**: In the admin Home page editor, change every field, save, reload the public home page,
and see each change. Reload the editor and see every field unchanged.

**Acceptance Scenarios**:

1. **Given** an administrator on the Home page editor, **When** they edit headline line 1, headline line 2,
   the sentence, both call-to-action labels and destinations, the tour link's visibility and label, and the
   pill's lead text, label and button label, **Then** saving persists all of them and the public hero shows
   them.
2. **Given** a destination field, **When** the administrator enters something that is neither a site path nor
   an `https` address, **Then** the save is refused with a message naming the field.
3. **Given** a field over its length limit, **When** they save, **Then** the save is refused with a message
   naming the field and its limit.
4. **Given** no hero content has ever been saved, **When** the public home page loads, **Then** the seeded
   default copy is shown, so the page is never blank.
5. **Given** the editor, **When** it loads, **Then** it shows which background images the hero is using and
   links to where they are changed, in the profile.

---

### User Story 5 - A Portfolio section, managed in the CMS (Priority: P2)

A visitor sees what Simon is building: a numbered carousel of projects on the home page, a Portfolio menu in
the header, and a Portfolio page listing every published project. At launch all four projects (Software
Factory, Term Time, Co-Parents, Clinician's Veil) are "Coming soon" and show as tinted silhouettes. Simon
adds, edits, reorders, publishes and unpublishes projects, and changes their status, in the admin area.

**Why this priority**: It needs the header (Story 1) to be reachable, and at launch it carries placeholders,
not finished products.

**Independent Test**: In the admin Portfolio editor, create a project, set it to Coming soon, publish it, and
confirm it appears in the header menu, the home carousel and the Portfolio page in the chosen order. Change it
to Live with a link and an image and confirm it becomes a linked card with a detail page.

**Acceptance Scenarios**:

1. **Given** the seeded data, **When** a visitor opens the Portfolio menu, the home page or the Portfolio page,
   **Then** they see Software Factory, Term Time, Co-Parents and Clinician's Veil in that order, each marked
   "Coming soon" and drawn as a silhouette.
2. **Given** a project whose status is Coming soon, **When** a visitor selects it, **Then** it does not
   pretend to be a finished product: it has no live link, and it shows the name, tagline and silhouette only.
3. **Given** a project that is Live or Beta, **When** a visitor selects it, **Then** they reach its detail page
   with its description, image and live link.
4. **Given** an unpublished project, **When** any visitor looks, **Then** it appears nowhere on the public
   site.
5. **Given** an administrator, **When** they reorder projects, **Then** every public listing follows the new
   order.
6. **Given** no project is published, **When** the home page loads, **Then** the portfolio section is not
   rendered, rather than showing an empty scaffold.

---

### Edge Cases

- The hero content cannot be fetched: the home page falls back to the seeded default copy rather than erroring.
- The profile has no mobile background image: phones use the desktop image. It has neither: the hero uses a
  plain dark surface and stays legible.
- The Portfolio request fails: the Portfolio menu still opens with "All projects", and the home section is
  omitted.
- A very long project name or tagline: it wraps without breaking the menu or card layout. Admin length limits
  bound it anyway.
- An old address (`/profile`, `/experience?job=…`) still redirects as it does today.
- A guided-tour step whose target moved with the header still finds its target. Tour steps pointing at the
  header's chat button or search keep working.
- The narration player bar is showing: the floating Ask pill sits above it, not on top of it.
- `prefers-reduced-motion`: the menu and pill animations are disabled.

## Requirements *(mandatory)*

### Functional Requirements

**Header and navigation**

- **FR-001**: Every public page MUST show one header with the site mark, four menus (About, Portfolio,
  Insights, Under the hood), a visible search field, a theme toggle and an "Ask Simon anything" button.
  The admin entry MUST appear for administrators.
- **FR-002**: The About menu MUST link to Profile (`/about`), Experience (`/about#roles`), Skills
  (`/about#skills`) and Contact (`/about#contact`), and offer "Take the guided tour".
- **FR-003**: The Insights menu MUST link to Blog (`/blogs`) and News & Events (`/news-events`). The Under the
  hood menu MUST link to MCP server (`/mcp`) and Platform status (`/status`).
- **FR-004**: The Portfolio menu MUST list published projects in CMS order, marking Coming soon ones, plus an
  "All projects" link to `/portfolio`.
- **FR-005**: Menus MUST open on click or tap and on hover with pointer devices, and close on Escape, an outside
  click, or navigation. Each menu trigger MUST expose its expanded state to assistive technology and be
  operable by keyboard.
- **FR-006**: The menu whose destinations include the current page MUST be shown as current.
- **FR-007**: At phone widths, the header MUST collapse to the site mark, an Ask button and a menu button. The
  menu MUST open full-screen with search first, expandable groups, a theme toggle and a pinned
  "Ask Simon anything" button, and MUST lock page scroll and keep keyboard focus inside while open.
- **FR-008**: Search MUST be a visible search field in the desktop header, MUST take focus on `/` or Ctrl/⌘+K
  outside text fields, and MUST keep the current site search's results, its hand-off to the assistant and the
  guided tour's search step.
- **FR-009**: The existing `/profile` and `/experience` redirects and every current route MUST keep working.

**Landing hero**

- **FR-010**: The home hero MUST show the profile's desktop background image at full strength, or the mobile
  background image at phone widths, with a legibility shade that works in both themes.
- **FR-011**: The hero MUST show an eyebrow built from the profile's name, title and location, a two-line
  headline (the second line accented), one supporting sentence (hidden at phone widths), a primary and a
  secondary call to action, an optional "Take the tour" link, and the Ask pill.
- **FR-012**: The Ask pill MUST show a lead text, a label and a button label, all from the CMS. It MUST NOT use
  the wording "Try the AI assistant". Activating it MUST open the chat assistant.
- **FR-013**: The multi-line chat input MUST be removed from the hero, along with the four suggested-question
  chips it carries. The floating "Take a Tour" button MUST be removed.
- **FR-014**: Once the hero's pill has scrolled out of view, a compact Ask pill MUST appear in a corner on
  every public page (a sparkle button at phone widths), and it MUST NOT overlap the narration player bar.

**Home page CMS**

- **FR-015**: Administrators MUST be able to edit the hero's headline lines, sentence, call-to-action labels
  and destinations, tour-link visibility and label, and the pill's lead text, label and button label, in a
  Home page editor.
- **FR-016**: Destinations MUST be either a site-relative path starting with `/` or an absolute `https`
  address. Anything else MUST be refused with a field-specific message.
- **FR-017**: Every text field MUST have a length limit enforced on save, and the editor MUST show each limit.
- **FR-018**: Saving MUST round-trip every field unchanged, and a field the editor does not change MUST NOT be
  cleared.
- **FR-019**: The public hero content MUST be readable without signing in. Writes MUST require the admin role.
- **FR-020**: Default hero content MUST be seeded, so a fresh or restored environment shows copy immediately.

**Portfolio**

- **FR-021**: A portfolio project MUST have a unique URL slug, a name, a tagline, a markdown description, a
  status (Live, Beta, In development, Coming soon), a display order, a published flag, an optional hero image,
  an optional live link, and an accent hue for its silhouette.
- **FR-022**: Administrators MUST be able to create, edit, delete, reorder, publish and unpublish projects. Slug
  uniqueness MUST be enforced by the data store, not only by the editor.
- **FR-023**: Public listings MUST include only published projects, in display order. A Coming soon project MUST
  be public only as name, tagline, status and silhouette hue. Its description and links MUST NOT be exposed.
- **FR-024**: The home page MUST show published projects as a numbered, horizontally scrollable carousel, with
  previous/next controls on desktop and swipe on touch, directly after the employer logos. It MUST render
  nothing when there are no published projects.
- **FR-025**: `/portfolio` MUST list every published project. `/portfolio/{slug}` MUST show a Live, Beta or
  In development project's detail, and MUST show a not-found page for unpublished, unknown or Coming soon
  slugs.
- **FR-026**: A project with no image, or that is Coming soon, MUST be drawn as a silhouette tinted by its
  accent hue.
- **FR-027**: The four launch projects MUST be seeded as published and Coming soon, in the order Software
  Factory, Term Time, Co-Parents, Clinician's Veil.
- **FR-028**: Portfolio and hero data MUST be included in the site backup and restored by the existing restore
  path, including the slug-uniqueness guarantee.

### Key Entities

- **Home hero content**: a single editable record holding the headline's two lines, the supporting sentence,
  the primary and secondary calls to action (label + destination), tour-link visibility and label, and the Ask
  pill's lead text, label and button label. Images and the name, title and location come from the Profile.
- **Portfolio project**: slug, name, tagline, description, status, display order, published flag, optional
  image, optional live link, accent hue, created and updated times.
- **Profile** (existing): supplies the name, title, location, and the desktop and mobile background images.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every destination reachable from today's header, plus Contact, the tour and each portfolio
  project, is reachable from the new header in at most two interactions on desktop and three on a phone.
- **SC-002**: Site search is visible in the header on every public page at desktop widths, is one tap away on a
  phone, and returns the same results as today for the same query.
- **SC-003**: At 390px wide, the hero's statement, both calls to action, the tour link and the Ask pill all fit
  in the first screen without scrolling.
- **SC-004**: A hero copy change made in the CMS appears on the public home page on the next page load, with no
  deploy.
- **SC-005**: Hero text meets WCAG 2.2 AA contrast against the background image, in both themes.
- **SC-006**: The header, menus and mobile menu are fully operable by keyboard, with no focus lost behind an
  open menu.
- **SC-007**: A full backup and restore brings back every portfolio project and the hero content unchanged,
  and duplicate slugs are still refused afterwards.

## Assumptions

- Visual direction follows variant A of the prototype: floating capsule header, small dropdown panels,
  numbered portfolio carousel. Styling stays in the site's existing plain-CSS/BEM system and tokens, with one
  added AI-accent colour used only for AI affordances.
- Seeded default copy, which Simon will refine in the CMS (LinkedIn cannot be read from here):
  - Headline: "Leading engineering teams." / "Building AI-native systems."
  - Sentence: "I lead a 30-strong engineering function across three product pillars, delivering real
    business value incrementally with AI-native tooling and teams trusted to run what they ship."
  - Primary CTA: "See my experience" → `/about#roles`. Secondary: "Get in touch" → `/about#contact`.
  - Tour link shown, labelled "Take the tour".
  - Pill: lead "Got a question?", label "Ask Simon anything", button "Start chat".
- Background images stay on the Profile, where they are already edited. The Home page editor shows them and
  links there instead of duplicating them.
- Portfolio launch taglines are placeholders. Clinician's Veil's is "Details soon." until Simon provides one.
- The four suggested-question chips are not relocated in this feature.
- Out of scope, as follow-ups: portfolio in site search and chat tools, share short-links for projects,
  editable menu structure, and CMS control of which images the hero uses.
- The throwaway prototype is deleted from the branch as part of this work. Nothing under
  `frontend/src/prototype/` ships.
