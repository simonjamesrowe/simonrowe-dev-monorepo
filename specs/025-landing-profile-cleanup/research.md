# Research: Landing / Profile Cleanup

No NEEDS CLARIFICATION markers remained after the spec phase. The decisions
below resolve the small implementation choices and are grounded in the existing
codebase and the approved design doc.

## Decision 1 — How to trim the mobile hero

- **Decision**: In `HeroSection.tsx`, use `useMediaQuery('(max-width: 768px)')`
  to conditionally omit the badge, tagline, and prompt chips on phones while
  always rendering name, role, chat-intro, and input. Desktop renders everything.
- **Rationale**: This matches the existing codebase pattern (`AboutSection`
  already uses `useMediaQuery('(max-width: 768px)')`), keeps desktop identical
  (the hook returns `false` at desktop width), avoids shipping hidden DOM on
  mobile, and — crucially — is unit-testable, since jsdom does not evaluate CSS
  media queries (a pure CSS `display:none` rule could not be verified in Vitest).
  The hook reads `matchMedia` synchronously on first render, so there is no
  desktop-content flash on mobile.
- **Alternatives considered**: A pure CSS `@media (max-width: 768px)` block
  hiding `.hero__badge` / `.hero__tagline` / `.hero__prompts` — rejected because
  it cannot be verified by the frontend test suite (constitution requires tests
  for critical journeys) even though it is visually equivalent.

## Decision 2 — How to present real profile content

- **Decision**: Reuse `main`'s clean `AboutSection` (photo + "About {firstName}"
  + real `description` markdown) on `ProfilePage`, wired so its "Get In Touch"
  button scrolls to the on-page contact form. Keep the existing Connect layout
  (CV download + `SocialLinks` + `ContactSection`). Delete `BioSection.tsx`.
- **Rationale**: `BioSection` is the only source of the fabricated "Architect of
  Precision Systems" headline and fake stats; the live site's real About content
  already lives in `AboutSection` on `main`. Reusing it satisfies "exactly as
  main" while dropping invented copy. CV + socials retained per explicit request.
- **Alternatives considered**: Editing `BioSection` to use real data — rejected
  because it still carries bespoke marketing layout/stats the user dislikes;
  building a brand-new bio component — rejected (YAGNI, `AboutSection` exists).

## Decision 3 — Footer removal scope

- **Decision**: Remove the `<Footer>` render from the shared layout in `App.tsx`
  for all routes (currently rendered everywhere except `/`). Delete the unused
  `Footer.tsx` component once nothing references it.
- **Rationale**: Explicit request to remove the footer everywhere; profile page
  already carries social links, so no information is lost.
- **Alternatives considered**: Keeping the footer on non-home pages — rejected,
  contradicts the request.

## Decision 4 — Preserving the tour

- **Decision**: Keep `.tour-profile` wrapping the About block and `.tour-contact`
  on the contact section within `ProfilePage`. No backend seed or selector
  changes.
- **Rationale**: The seeded tour steps (and `TourSeedDefaultsTest`) target these
  selectors; preserving them avoids any tour regression with zero backend work.
- **Alternatives considered**: Re-seeding new selectors — rejected as unnecessary
  churn and out of scope.

## Decision 5 — CSS restore strategy

- **Decision**: Restore the `.about-section` and profile-related CSS blocks from
  `main` (so the reused `AboutSection` renders correctly), remove the now-dead
  `.bio-section` styles, and add only the small mobile-hero media query. Leave
  all new hero/landing CSS in place.
- **Rationale**: The current branch replaced about-section styles with
  bio-section styles; restoring the about-section blocks is required for the
  reused component to look like `main`, while a wholesale `styles.css` revert
  would wrongly undo the wanted landing redesign.
- **Alternatives considered**: Wholesale revert of `styles.css` — rejected, would
  lose the approved landing hero styling.
