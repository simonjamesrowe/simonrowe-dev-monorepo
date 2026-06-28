# Landing / Profile Cleanup Design

**Date:** 2026-06-28
**Status:** Approved direction, pending spec review
**Builds on:** `2026-06-28-landing-profile-split-design.md` (the original landing/profile split)

## Context

The landing/profile split was implemented (uncommitted working-tree changes on
`feat/frontend/landing-chat-widgets`). On review, the implementation over-styled
the profile/contact pages with invented marketing copy and left the footer and
mobile hero too heavy. This is a corrective round to bring the profile/contact
content back in line with `main` while keeping the new chat-first landing page.

## Goals

1. Mobile landing page leads with the chat and stops feeling text-heavy.
2. `/profile` shows the real "About Simon" content and contact form — no
   fabricated copy — styled like `main`.
3. The footer is removed from every page.
4. The "Take a Tour" flow still resolves its profile/contact steps.

## Non-Goals

- No changes to the desktop landing hero (it stays as-is).
- No changes to the chat widget / streaming behaviour.
- No backend tour-seed changes (existing `.tour-profile` / `.tour-contact`
  selectors are preserved).

## Changes

### 1. Mobile landing hero (`HeroSection` + `styles.css`)

The hero markup is unchanged. A mobile media query (`max-width: 768px`) hides
the elements that make the mobile view text-heavy:

- Hide `.hero__badge` ("Engineering Leadership // AI-Native Systems")
- Hide `.hero__tagline`
- Hide `.hero__prompts` (the suggested-prompt chips)

Mobile retains: **name + role + chat-intro line + chat input**, over the
existing background photo. Desktop is unaffected — badge, tagline, and chips
remain.

### 2. `/profile` page (`ProfilePage`, `AboutSection`, `BioSection`)

- Replace the invented `BioSection` (hardcoded "The Architect of Precision
  Systems" headline + fake "12+ / 450M+" stats) with the real **About** block:
  profile photo + "About {firstName}" heading + real `profile.description`
  markdown. This reuses `main`'s clean `AboutSection`, with its "Get In Touch"
  button scrolling to the on-page contact form (`#contact`).
- Keep the existing **Connect** layout: **Download CV** button + **social
  links** + **contact form** (`ContactSection`).
- **Delete `BioSection.tsx`** (and its now-dead `.bio-section` styles) — it is
  the only source of the fabricated copy and becomes unused.
- Restore `main`'s `.about-section` / profile CSS blocks, leaving all new
  hero/landing CSS untouched.

### 3. Footer removed everywhere (`App.tsx`)

- Remove the `<Footer>` render from the shared layout for all routes (it is
  currently rendered on every route except `/`). Remove the now-unused
  `showFooter` logic and `Footer` import.

### 4. Tour selectors preserved

- Keep `.tour-profile` wrapping the About block and `.tour-contact` on the
  contact section so the seeded tour steps still resolve. No backend changes.

### 5. Tests

- Update home tests: assert badge/tagline/chips are not shown at mobile width;
  desktop hero unchanged.
- Update profile tests: assert real "About" content renders; assert the
  "Architect of Precision Systems" text is gone; keep CV + social link + contact
  form assertions.
- Update/remove footer assertions (footer absent on all pages).
- Revert/realign `AboutSection` test to `main`'s component shape.

## Verification

- `cd frontend && npm test` (targeted: HomePage, ProfilePage, App, AboutSection,
  HeroSection) passes.
- `cd frontend && npm run build` passes.
- `./gradlew :backend:test --tests '*TourSeedDefaults*'` passes (selectors
  unchanged).
- Browser check on `localhost:5173`: mobile home shows no badge/tagline/chips
  and no horizontal overflow; `/profile` shows real About content, CV, social
  links, and contact form; no footer on any page; tour resolves profile/contact
  steps.
