# Research: Site Navigation, Landing Hero and Portfolio

## R1. Which prototype variant

**Decision**: Variant A: floating capsule header, small dropdown panels, numbered portfolio carousel, full-screen
accordion menu on phones.
**Rationale**: The owner approved it ("I like the design and principle"). B's bottom tab bar collides with the
narration player bar, which is also pinned to the bottom. C hides every destination behind one button.
**Alternatives considered**: B (mega menu + tab bar) and C (command bar + index), both prototyped on this branch
(commits `a471c572`, `e8d36b1b`).

## R2. Keeping search visible

**Decision**: The existing `SiteSearch` field stays visible in the header as a compact input. It is about
16rem wide on large screens and 10rem between 900 and 1100px, and it shows a `/` hint. Pressing `/` (or
Ctrl/⌘+K) outside a text field focuses it. On phones, `SiteSearch` is the first control in the menu sheet.
**Rationale**: The owner read the prototype's icon-only control as "search has disappeared". It also broke the
tour: the seeded `default-site-search` step targets `.tour-search`, and `SearchSimulation` types into that
input. A field hidden in a closed popover has nothing to spotlight. Keeping the real component unchanged keeps
the Elasticsearch results, keyboard navigation, the ask-the-assistant hand-off and the tour all working.
**Alternatives considered**: A labelled button opening a popover (rejected: the tour target is hidden until
opened), and a search field in the hero (rejected: it competes with the Ask pill).

## R3. Where the hero copy lives

**Decision**: A new singleton document (`home_page`, one row with a fixed id), not new fields on `Profile`.
**Rationale**: Profile is identity data that is also served to chat tools, MCP `getProfile` and the CV. Hero
copy is presentation, and it changes on a different cadence. A separate record also keeps Profile's DTO
round-trip surface unchanged. That surface is the one CLAUDE.md warns silently drops data when a field is
omitted. Background images stay on Profile, where they are already edited, so nothing is duplicated.
**Alternatives considered**: Adding fields to Profile (rejected above), and a generic key/value "site content"
store (rejected under YAGNI, since there is only one consumer).

## R4. Destination validation

**Decision**: A destination must either start with a single `/` (and not `//`), or parse as an absolute URL
whose protocol is `https:`. The check parses the value; it does not compare string prefixes.
**Rationale**: CLAUDE.md requires validating by parsed URL, not prefix. `//evil.example` is protocol-relative
and would leave the site.

## R5. Portfolio visibility of Coming soon projects

**Decision**: The public API returns a reduced shape for `COMING_SOON` (slug, name, tagline, status, hue,
order). `GET /api/portfolio/{slug}` returns 404 for Coming soon, unpublished and unknown slugs alike.
**Rationale**: FR-023 and FR-025. An unfinished product's description and links should not leak, and a detail
page for a placeholder has nothing to show.

## R6. Indexes and seed data

**Decision**: One Mongock change unit creates the `portfolio_projects` unique slug index and the
`(published, displayOrder)` index, and seeds the four projects and the hero singleton, idempotently. Restore
calls the unit's `createIndexes` directly, following the `V029` short-links precedent.
**Rationale**: `auto-index-creation` is off, so `@Indexed` alone does nothing (see
[[mongo-indexes-need-mongock]]). Mongock does not re-run a recorded unit after a restore drops the collection.
The seed is data that nothing else re-derives, so it belongs in the same auditable unit.

## R7. Tour compatibility

**Decision**: Keep the class names that tour steps target on the new elements: the header Ask button keeps
`top-nav__ask-ai` (referenced by `tourActions.ts` and `TourOverlay.tsx`), the search keeps `tour-search` (this
comes from `SiteSearch` itself), and the hero's Ask pill carries `tour-home-chat`, the seeded
`default-home-chat` step pinned by `TourSeedDefaultsTest`. Tour steps are only migrated by a change unit if a stored
selector can no longer resolve.
**Rationale**: A tour step whose target is missing fails silently (see the `V047` note in CLAUDE.md).

## R8. Removing the floating tour button

**Decision**: Remove the fixed-position `TourButton`. The tour starts from a "Take a tour" button in the hero
and in the About menu.
**Rationale**: Its corner is now taken by the Ask pill. Constitution VII still holds, because the tour remains
reachable from a "Take a Tour" button, and more prominently.
