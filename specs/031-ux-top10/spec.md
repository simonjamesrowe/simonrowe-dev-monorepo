# Feature Specification: UX Top-10 Improvements

**Feature Branch**: `simonrowe/ux-review-simonrowe-dev` (feature dir `031-ux-top10`)

**Created**: 2026-07-30

**Status**: Draft

**Input**: User description: "Implement the UX Top-10 design for simonrowe.dev as described in docs/superpowers/specs/2026-07-30-ux-top10-design.md — routing/404 redirects, page-title hook, home page sections below the hero, shared API retry + error handling, blog contentType (Engineering vs Weekly Digest) with Mongock backfill, mobile hero content, skill level words + consistent skill/employer icon assets, button/admin-link/copy consistency, a site-wide footer, and News & Events backend-driven pagination with source chips. Delivered as one PR spanning frontend, backend, and Mongock change units."

**Source design**: `docs/superpowers/specs/2026-07-30-ux-top10-design.md`

## Overview

A UX review of the live site (desktop 1440px and mobile 390px, in both light and
dark themes) found ten priority problems: the home page ends at the hero, legacy
and unknown URLs dead-end, the blog mixes hand-written engineering posts with
auto-generated weekly digests, API failures surface raw fetch errors under a
wrong heading, the mobile hero hides the pitch, skill ratings and icons look
arbitrary, button and copy treatments are inconsistent, an admin link is shown
to everyone, there is no footer, and News & Events loads a hundred articles at
once.

This feature fixes all ten. Each is small on its own; together they change
whether the site reads as a credible professional presence. Everything ships as
one change set — including the data changes, so no manual content editing is
required afterwards.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A recruiter landing on the home page learns who Simon is (Priority: P1)

A hiring manager follows a link to the site, reads the hero, and scrolls. Today
scrolling does nothing — the page is exactly one viewport tall and offers no
summary, no proof, no next step. After this change, scrolling past the hero
reveals a short "currently" summary of the present role, a quiet row of past
employer logos, the three most recent engineering posts, and a way to get in
touch or download a CV — followed by a footer that appears on every page.

**Why this priority**: This is the single highest-traffic page and the largest
gap. A visitor who bounces from the hero learns nothing; every other fix is
worth less if this one is missing.

**Independent Test**: Load the home page, scroll to the bottom, and confirm each
section renders real content sourced from the site's own profile, jobs, and blog
data — no placeholder or hardcoded facts — and that every link resolves.

**Acceptance Scenarios**:

1. **Given** the home page has loaded, **When** the visitor scrolls below the
   hero, **Then** they see a current-role summary, an employer logo row, three
   recent engineering posts, and a contact call-to-action, in that order.
2. **Given** the visitor is on any public page, **When** they scroll to the
   bottom, **Then** a footer shows the name and positioning line, links to every
   public section, and social/contact links plus a CV download.
3. **Given** the employer logo row is displayed, **When** the visitor selects a
   logo, **Then** they arrive on the experience page.
4. **Given** the visitor switches between light and dark themes, **When** they
   view the new sections, **Then** all logos and text remain legible with no
   invisible or clipped assets.
5. **Given** the profile or jobs data is unavailable, **When** the home page
   renders, **Then** the affected section degrades quietly rather than showing
   an error or empty scaffolding.

---

### User Story 2 - A reader finds engineering writing without wading through digests (Priority: P1)

The blog listing currently interleaves hand-written engineering posts with
auto-generated weekly digests, ordered only by date, with the newest item — often
a digest — promoted as the featured card. A reader looking for Simon's own
technical writing cannot tell the two apart until they open a post.

**Why this priority**: The blog is the main evidence of technical depth. Burying
authored posts under machine-generated digests actively misrepresents the site's
value.

**Independent Test**: Open the blog listing and confirm it opens on an
Engineering view showing only authored posts, that the featured card is the
latest authored post, and that the tabs switch the list without a page reload.

**Acceptance Scenarios**:

1. **Given** a visitor opens the blog listing, **When** the page first renders,
   **Then** the Engineering view is selected and only engineering posts are
   listed.
2. **Given** the Engineering view is selected, **When** the visitor looks at the
   featured card, **Then** it is the most recent engineering post — never a
   digest.
3. **Given** the visitor selects the Weekly Digest tab, **When** the list
   updates, **Then** only digest posts appear; selecting All shows both sets.
4. **Given** the weekly digest generator publishes a new digest, **When** the
   blog listing is reloaded, **Then** that post appears under Weekly Digest and
   not under Engineering, without anyone editing it by hand.
5. **Given** an author creates a post through the admin editor, **When** they do
   not change the content-type field, **Then** the post is classified as
   engineering.
6. **Given** the change ships to an environment with existing posts, **When** the
   data migration runs, **Then** every existing post has a content type — digests
   identified by their existing "Weekly Digest" tag, everything else engineering —
   and re-running the migration changes nothing further.

---

### User Story 3 - No visitor hits a dead end (Priority: P1)

Links to `/blog` and `/blog/<id>` exist in the wild (older sharing, external
references) and currently render nothing useful. Any mistyped or stale URL does
the same.

**Why this priority**: A blank or broken page is the worst possible outcome for
inbound traffic, and it silently loses visitors who arrived from a shared link.

**Independent Test**: Visit `/blog`, `/blog/<known-post>`, and a nonsense URL,
and confirm each ends somewhere useful.

**Acceptance Scenarios**:

1. **Given** a visitor opens `/blog`, **When** the page loads, **Then** they land
   on the blog listing and the address bar shows the canonical listing URL
   without adding a back-button trap.
2. **Given** a visitor opens `/blog/<id>` for an existing post, **When** the page
   loads, **Then** they see that post at its canonical address.
3. **Given** a visitor opens a URL matching no route, **When** the page loads,
   **Then** they see a "page not found" page inside the normal site chrome, with
   a short friendly explanation and links to the home page and the blog.

---

### User Story 4 - When something fails, the visitor understands and can retry (Priority: P2)

Every page currently reuses the same error frame headed "Unable to load
homepage", and several pages render the raw failure text (for example "Failed to
fetch") with no way to try again. Transient network blips look like a broken
site.

**Why this priority**: The site is served from a home connection behind a tunnel;
transient failures are normal. Handling them badly makes reliability look worse
than it is.

**Independent Test**: With the API blocked, load each public page and confirm a
correctly-titled error frame with a working retry action; unblock the API and
confirm retry recovers without a page reload.

**Acceptance Scenarios**:

1. **Given** a request fails because of a network error or a server-side (5xx)
   failure, **When** the visitor is waiting, **Then** the request is retried once
   automatically after a short delay before any error is shown.
2. **Given** the automatic retry also fails, **When** the error is shown,
   **Then** it uses a heading appropriate to the page or section — never
   "Unable to load homepage" on a non-home page — and never shows raw failure
   text as the only message.
3. **Given** an error frame is shown on any public page, **When** the visitor
   selects the retry action, **Then** the failed request is reissued and the
   content appears on success without a full page reload.
4. **Given** a request fails because of a client-side (4xx) response, **When**
   the error is handled, **Then** it is not retried automatically.

---

### User Story 5 - A mobile visitor sees the same pitch as a desktop visitor (Priority: P2)

On a 390px-wide viewport the hero hides the eyebrow badge, the tagline, and every
suggested-prompt chip, leaving a name and a chat box floating in empty space.
Mobile visitors get strictly less information and no hint of what the chat is
for.

**Why this priority**: Mobile is a large share of inbound traffic from social
links, and the current mobile hero communicates almost nothing.

**Independent Test**: Load the home page at 390×844 and confirm the badge,
tagline, and two prompt suggestions are visible above the fold with no vertical
dead space.

**Acceptance Scenarios**:

1. **Given** a 390px-wide viewport, **When** the home page renders, **Then** the
   eyebrow badge and the tagline are visible, with the tagline kept to a single
   line.
2. **Given** a 390px-wide viewport, **When** the home page renders, **Then**
   exactly two suggested-prompt chips are shown and selecting one starts the
   chat as it does on desktop.
3. **Given** a 390×844 viewport, **When** the visitor looks at the hero, **Then**
   the chat input sits within the first screen with no large empty gap above or
   below it.
4. **Given** a desktop viewport, **When** the home page renders, **Then** the
   hero is unchanged from today.

---

### User Story 6 - News & Events loads quickly and filters correctly (Priority: P2)

The news list fetches one hundred articles in a single request and filters by
source in memory, so the first paint waits on the whole payload and the source
chips only reflect whatever happened to be in that batch.

**Why this priority**: It is the slowest public page and the filter is quietly
wrong (a source with no articles in the first hundred is unreachable), but it
affects fewer visitors than the home or blog pages.

**Independent Test**: Load News & Events and confirm the first screen fills from
a small first page, that "Load more" appends further articles until exhausted,
and that a source chip re-queries rather than filtering the loaded set.

**Acceptance Scenarios**:

1. **Given** the visitor opens News & Events, **When** the page first renders,
   **Then** it shows the first page of 24 articles.
2. **Given** more articles exist, **When** the visitor selects "Load more",
   **Then** the next page is appended below the existing articles without losing
   scroll position.
3. **Given** the last page has been loaded, **When** the visitor looks at the end
   of the list, **Then** no "Load more" action is offered.
4. **Given** the visitor selects a source chip, **When** the list updates,
   **Then** the articles are re-fetched for that source from the start, and
   paging continues within that source.
5. **Given** the site has articles from a set of sources, **When** the chips
   render, **Then** they list every distinct source the site holds — not only
   those present in the first page.
6. **Given** the visitor switches to the favourites view, **When** they browse,
   **Then** favourites behave exactly as they do today and are unaffected by news
   paging.

---

### User Story 7 - Skill ratings and icons look deliberate (Priority: P3)

Skills show an unlabelled bar filled to some fraction, and skill-group icons are
a visually inconsistent mix of styles and resolutions. Employer logos on the
experience timeline have the same problem — the current-employer logo is cropped
and low resolution.

**Why this priority**: This is credibility polish. It matters for the impression
the profile leaves, but nothing is broken or unreachable without it.

**Independent Test**: Open the profile/skills view and the experience timeline
and confirm each rating carries a level word matching its bar, and that icons and
logos are a single consistent, sharp set in both themes.

**Acceptance Scenarios**:

1. **Given** a skill has a rating, **When** it is displayed, **Then** a level
   word appears beside the bar: Expert (9–10), Advanced (7–8), Proficient (5–6),
   Familiar (below 5).
2. **Given** a skill rating is displayed, **When** it is read by assistive
   technology, **Then** the announced value matches the visible level word and
   bar.
3. **Given** the skills view is displayed, **When** the visitor scans the group
   icons, **Then** every group has an icon from one visually consistent set,
   including groups with no natural brand mark.
4. **Given** the experience timeline is displayed, **When** the visitor scans it,
   **Then** each employer logo is sharp, uncropped, and normalised in height —
   and the same assets feed the home page logo row.
5. **Given** the asset migration runs more than once, **When** it completes,
   **Then** no duplicate assets are created and the icon references are
   unchanged from the first run.

---

### User Story 8 - The site's chrome and copy read as one product (Priority: P3)

Primary buttons fade to near-white, so "Get In Touch" and "Download CV" read as
disabled. The contact form's submit says "Initiate Connection →" while the same
action is called several other things elsewhere. Every visitor sees an admin
icon in the navigation. Both GitHub social links are labelled identically. Page
titles are inconsistent, so browser tabs and bookmarks are unhelpful.

**Why this priority**: Individually cosmetic, collectively the difference between
"considered" and "assembled". Low risk and independent of the other stories.

**Independent Test**: Walk every public page in both themes checking button
contrast, the single agreed name for the contact action, absence of the admin
link when signed out, distinct social-link labels, and a correct browser tab
title.

**Acceptance Scenarios**:

1. **Given** a primary button is displayed, **When** the visitor sees it,
   **Then** it has a solid fill with text meeting accessible contrast, and still
   responds visibly on hover.
2. **Given** the contact form is displayed, **When** the visitor reads the submit
   button, **Then** it says "Send message", and the success and failure messages
   use the same verb.
3. **Given** the contact action is referenced anywhere on the site, **When** the
   visitor reads it, **Then** it is called "Get in touch" in sentence case.
4. **Given** a visitor who is not an administrator, **When** they view the
   desktop navigation or the mobile menu, **Then** no admin link is shown; the
   existing route-based sign-in flow is unchanged.
5. **Given** an administrator is signed in, **When** they view the navigation,
   **Then** the admin link is shown.
6. **Given** two GitHub social links exist, **When** they are displayed, **Then**
   each carries its own descriptive label rather than a shared generic one.
7. **Given** the visitor opens any page, **When** they look at the browser tab or
   save a bookmark, **Then** the title identifies both the page and the site
   (the home page and the site default identify the site and its positioning).

---

### Edge Cases

- Profile, jobs, blog, or news data unavailable while the home page renders: each
  new section must degrade independently rather than failing the whole page.
- Fewer than three engineering posts exist: the featured-writing section shows
  what exists without empty placeholders.
- No engineering posts exist at all: the blog listing's featured card and the
  home featured-writing section must not break or fall back to a digest.
- A post carries a "Weekly Digest" tag in different casing or with surrounding
  whitespace: the migration must still classify it as a digest.
- A post is both hand-written and tagged "Weekly Digest": tag-based
  classification wins during the one-off migration; after that, the explicit
  field is authoritative.
- A job or skill group has no logo/icon asset available: it must render an
  acceptable neutral state rather than a broken image.
- The asset migration runs where the uploads volume already holds a file of the
  same name: it must not corrupt or duplicate the existing asset.
- A source chip is selected and then "Load more" is pressed while a previous
  request is still in flight: results must not interleave or duplicate.
- The visitor selects retry repeatedly on a persistently failing request: it must
  not spawn unbounded parallel requests.
- Unknown URL under a known prefix (for example `/blogs/<nonexistent-id>`): must
  not render an empty shell.

## Revisions after local review (2026-07-31)

The feature was reviewed against restored production data in a local environment. Six
requirements were changed as a direct result. They are recorded here rather than
edited away silently, because each is a deliberate narrowing or widening of what was
originally agreed.

| # | Requirement | Change | Why |
|---|---|---|---|
| R1 | **FR-010** | The footer no longer links to every public section. It is one bar: copyright, connect icons, and a single "Get in touch". | Rendered, the brand block + positioning line + six-link nav column made the footer taller than some of the pages above it, and the nav duplicated the top navigation that is present on every page anyway. |
| R2 | **FR-007** | Featured writing shows up to **10** posts in a horizontally scrollable carousel, not 3 in a static grid. | Three cards under-used the width and under-sold the volume of writing. 10 is the cap `GET /api/blogs/latest` already enforces on `limit`. |
| R3 | **FR-006** | Employer logos open that role's **detail drawer in place**, instead of linking to `/experience`. The row is a continuously scrolling carousel. | A logo is a specific role; sending the visitor to a whole page to find it again was a worse answer than showing it immediately. |
| R4 | **New** | The profile page's full-width **Connect section became a drawer** (`ContactDrawer`, previously an unused component). The CV download and social links moved into it. | Keeps the profile page about the profile, and means every "Get in touch" on the site resolves to the same one place. `/profile#contact` now opens that drawer rather than scrolling to an anchor. |
| R5 | **FR-036** | Social links show the label only; the raw URL beneath each one is gone. | It wrapped to a second line per entry and said nothing the label did not. |
| R6 | **FR-027** | Level-word bands are continuous lower bounds (`>=9`, `>=7`, `>=5`, else), not the integer sets "9–10 / 7–8 / 5–6". | Live ratings are decimals — `8.6`, `7.3`, `7.2`, `6.9` — which the integer phrasing left unclassified. |

Two defects were also found and fixed that the original review missed: `ProfilePage`
never called `useScrollToHash`, so `/profile#contact` had never scrolled anywhere
(now moot, since it opens the drawer); and `.cta-section__btn-primary` reimplemented
the primary button with `--on-surface`, so the FR-033 solid-fill fix did not reach the
most prominent call to action on the site until those classes were deleted.

## Requirements *(mandatory)*

> Requirements below are as originally agreed. Where the Revisions table above changes
> one, the table is authoritative.

### Functional Requirements

**Routing and page identity**

- **FR-001**: The site MUST redirect `/blog` to the blog listing and `/blog/<id>`
  to the corresponding post detail, replacing the history entry so the back
  button does not loop.
- **FR-002**: The site MUST render a "page not found" page inside the normal
  public chrome for any URL matching no route, with links to the home page and
  the blog listing.
- **FR-003**: Every public page MUST set a browser title identifying the page and
  the site; the site default and the home page MUST identify the site and its
  positioning statement. Page-title behaviour MUST come from one shared
  mechanism rather than per-page duplication.

**Home page**

- **FR-004**: The home page MUST render, below the hero and in order: a
  current-role summary, an employer logo row, the three most recent engineering
  posts, and a contact call-to-action.
- **FR-005**: All content in those sections MUST be derived from the site's
  existing profile, jobs, and blog data. No facts may be hardcoded in the
  presentation layer.
- **FR-006**: The employer logo row MUST de-duplicate by employer, normalise
  logo height, remain legible in light and dark themes, and link to the
  experience page.
- **FR-007**: The featured-writing section MUST show only engineering posts and
  MUST offer a link to the full blog listing.
- **FR-008**: The contact call-to-action MUST link to the contact section of the
  profile page and to the CV asset.
- **FR-009**: Home-page components that exist but are unreferenced MUST either be
  used by these sections or removed. No unreferenced presentation components may
  remain after this change.

**Footer**

- **FR-010**: A footer MUST render on every public page, containing the name and
  a one-line positioning statement, links to every public section, and social
  links, contact email, and CV download drawn from profile data.
- **FR-011**: The footer MUST reuse and refresh the existing unused footer
  styling rather than introducing a parallel style block.

**Error handling and resilience**

- **FR-012**: All public data requests MUST route through one shared fetch
  mechanism that retries once, after a short backoff, on network errors and
  server-side (5xx) responses, and does not retry client-side (4xx) responses.
- **FR-013**: The shared mechanism MUST replace the duplicated per-service
  response-handling helpers.
- **FR-014**: The error display MUST accept a caller-supplied heading and default
  to a page-neutral heading. No page may display a heading naming a different
  page.
- **FR-015**: Every public page and data-backed section MUST offer a retry action
  on failure, and retry MUST reissue only the failed request.
- **FR-016**: No raw underlying failure text may be presented as the primary
  error message.

**Blog content type**

- **FR-017**: A blog post MUST carry an explicit content type of either
  engineering or digest. New and author-created posts MUST default to
  engineering.
- **FR-018**: The weekly digest generator MUST set the digest content type at
  creation time.
- **FR-019**: Published post data MUST carry the content type wherever posts are
  retrieved, and it MUST be possible to retrieve the most recent posts of a
  single content type without fetching and filtering the whole set.
- **FR-020**: A one-off, idempotent data migration MUST assign a content type to
  every existing post: digest where the post carries a tag named "Weekly Digest"
  (case- and whitespace-insensitive), engineering otherwise.
- **FR-021**: The admin blog editor MUST expose the content type as a selectable
  field defaulting to engineering.
- **FR-022**: The blog listing MUST offer All, Engineering, and Weekly Digest
  views, defaulting to Engineering, and MUST select its featured card as the most
  recent engineering post rather than by list position.
- **FR-023**: Blog card and featured-card call-to-action copy MUST read "Read
  post".

**Mobile hero**

- **FR-024**: At mobile widths the hero MUST render the eyebrow badge, the
  tagline (clamped to one line), and the first two suggested-prompt chips.
- **FR-025**: Hero vertical spacing at mobile widths MUST place the chat input
  within the first screen of a 390×844 viewport without large empty gaps.
- **FR-026**: Desktop hero rendering MUST be unchanged.

**Skills and assets**

- **FR-027**: Each skill rating MUST display a level word beside its bar:
  Expert for 9–10, Advanced for 7–8, Proficient for 5–6, Familiar below 5.
- **FR-028**: The accessible description of a skill rating MUST match the
  displayed level word and bar value.
- **FR-029**: Every skill group MUST reference an icon from a single, visually
  consistent set, including groups without a natural brand mark.
- **FR-030**: Every employer MUST reference a sharp, uncropped logo asset, and
  the same assets MUST feed both the experience timeline and the home logo row.
- **FR-031**: Icon and logo assets MUST be delivered by the change set itself —
  bundled with the application and installed into the media store by an
  idempotent data migration that creates the media records and repoints the skill
  group and employer references. No manual content-management step may be
  required.
- **FR-032**: Before the asset migration is finalised, the proposed icon and logo
  set MUST be presented for human approval.

**Chrome and copy**

- **FR-033**: Primary buttons MUST use a solid fill with accessible text
  contrast, retaining a visible hover response.
- **FR-034**: The contact submit action MUST read "Send message", with success
  and failure messages using the same verb; the contact action MUST be named
  "Get in touch" in sentence case wherever it appears.
- **FR-035**: Administrative navigation entries in both the desktop navigation
  and the mobile menu MUST render only for signed-in administrators. The
  route-based sign-in flow MUST be unchanged.
- **FR-036**: Social links MUST prefer their own configured name when present,
  falling back to a type-derived label, and the two GitHub entries MUST be
  renamed by data migration to distinguish the personal account from this site's
  repository.

**News and events**

- **FR-037**: The complete set of distinct article source names MUST be
  retrievable independently of any single page of articles.
- **FR-038**: The news list MUST load 24 articles initially and append further
  pages on an explicit "Load more" action, hiding the action on the last page.
- **FR-039**: Source chips MUST be populated from the distinct-sources data and
  MUST re-query the backend from the first page when selected, rather than
  filtering already-loaded articles.
- **FR-040**: Event loading and the favourites view MUST behave as they do today
  and MUST be unaffected by news paging.

**Delivery**

- **FR-041**: All data changes (post content types, icon and logo assets, social
  link names) MUST ship as versioned, idempotent data migrations within the
  application, not as ad-hoc scripts or manual edits.
- **FR-042**: The whole change set MUST ship as a single reviewable change with
  automated tests covering each story, and MUST be verified against a local
  environment carrying production-like data before merge.

### Key Entities

- **Blog post**: An article with a title, body, publication date, tags, and — new
  in this feature — an explicit content type distinguishing hand-written
  engineering posts from generated weekly digests.
- **Tag**: A named label attached to posts; the existing "Weekly Digest" tag is
  the input to the one-off content-type migration and remains for display.
- **Skill group**: A named grouping of skills carrying a display icon reference;
  the icon reference is repointed by this feature.
- **Skill**: A named capability with a 0–10 rating, now also presented as a level
  word.
- **Employer / job**: A role at a company, carrying a company logo reference,
  repointed by this feature and reused on the home page logo row.
- **Media asset**: A stored image with a retrievable path; new records are created
  for the bundled icon and logo set.
- **Social link**: A profile link with a type, an optional display name, and a
  URL; the two GitHub entries gain distinguishing names.
- **News article**: An aggregated article carrying a source name; the distinct set
  of source names becomes independently retrievable for filter chips.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The home page presents at least four distinct content sections
  below the hero, all populated from live site data, with zero hardcoded facts in
  the presentation layer.
- **SC-002**: A visitor can reach the experience page, the blog, and a contact
  action from the home page without using the top navigation.
- **SC-003**: Every public page ends in a footer offering navigation to all other
  public sections — 100% coverage of public pages.
- **SC-004**: A first-time visitor to the blog listing sees only hand-written
  engineering posts, and the promoted post is an engineering post, in 100% of
  loads where at least one engineering post exists.
- **SC-005**: Every existing blog post carries a content type after migration
  (100% coverage), with digests and engineering posts split as the existing
  "Weekly Digest" tag dictates, and re-running the migration produces no further
  changes.
- **SC-006**: `/blog`, `/blog/<id>`, and unknown URLs all resolve to a useful
  destination — zero blank or unstyled pages across the tested URL set.
- **SC-007**: A transient single failure of any public data request recovers
  automatically without the visitor seeing an error.
- **SC-008**: When an error is shown, it always carries a heading relevant to the
  current page and a working retry action — zero occurrences of a mismatched
  heading or a missing retry across public pages.
- **SC-009**: On a 390×844 viewport the home page shows the badge, tagline, and
  two prompt suggestions with the chat input inside the first screen.
- **SC-010**: The News & Events first screen renders from 24 articles instead of
  100, and every source the site holds is selectable — no source is unreachable.
- **SC-011**: Every skill rating displays a level word consistent with its
  numeric value, and the accessible description matches, for 100% of skills.
- **SC-012**: Every skill group and every employer resolves to a sharp icon or
  logo from the approved set — zero missing, cropped, or stylistically
  inconsistent assets in either theme.
- **SC-013**: Primary button text meets WCAG AA contrast against its fill in both
  themes.
- **SC-014**: The contact action is called "Get in touch" and its submit "Send
  message" everywhere it appears — zero variant labels remain.
- **SC-015**: A signed-out visitor sees no administrative navigation entry on
  desktop or mobile, while sign-in via the admin route still works.
- **SC-016**: Every public page produces a distinct, page-identifying browser
  title.
- **SC-017**: No unreferenced presentation components remain in the home-page
  component set.
- **SC-018**: Automated tests cover each user story, and the full test suite plus
  static checks pass before merge.

## Assumptions

- The site's existing profile, jobs, skills, blog, and news data is rich enough
  to source the new home-page sections and footer; no new content authoring is
  required beyond the migrations described.
- A CV asset and a contact section already exist and are linkable; this feature
  reuses them rather than creating them.
- The existing "Weekly Digest" tag is a reliable marker of generated digests in
  current data — the review counted 15 tagged digests against 28 engineering
  posts, and that split is the expected migration outcome.
- Administrator identification uses the site's existing role check; this feature
  changes only where the admin link is displayed, not how authentication works.
- Icon and logo assets are sourced by the implementer from an openly licensed
  brand-mark set plus visually matching generic marks, with official assets for
  employers. Their selection is subject to the human approval gate in FR-032.
- The existing news pagination already supports page, size, and source
  parameters; only a distinct-sources endpoint is new.
- The existing media store and its uploads volume are the correct destination for
  the bundled assets, and the migration runs with write access to it.
- The one-off migrations are expected to run in local and production environments
  alike; production runs on the next deploy.
- 24 articles per news page is a presentation choice chosen to fill a desktop
  grid without over-fetching; it is not derived from a measured constraint.

## Dependencies

- The site's existing profile, blog, news, skills, and jobs APIs.
- The existing role-based access check used to identify administrators.
- The existing versioned data-migration mechanism and its test isolation pattern.
- The existing media store and uploads volume.
- Human approval of the proposed icon and logo set before that migration is
  finalised (FR-032).

## Out of Scope

Replacing the cartoon avatar with a photograph; demoting News & Events in the
primary navigation; changing the hero background artwork; brotli compression;
WebP/AVIF image conversion; server-side tag filtering for the blog. Bundle
compression and route code-splitting were in the original review list but are
already live in production and require no work.
