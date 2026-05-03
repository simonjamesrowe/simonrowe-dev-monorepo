# Mobile Landing Page Cleanup — Design

**Date:** 2026-05-02
**Branch:** `simonrowe/mobile-layout-fix`
**Scope:** Landing/profile page (`/`) on mobile viewports only (`max-width: 768px`)

## Problem

The landing page on mobile (verified at 390×844 against the live `https://simonrowe.dev`) has two concrete issues:

1. **Top bar misalignment.** The site search input is 47px tall (top y=5, padding 11.2px) while the hamburger button is 40px tall (top y=14, padding 8px). Their vertical centers differ by ~5.5px so they read as misaligned.
2. **Hero density.** The hero is 679px tall (~80% of viewport) and contains 8 competing elements: badge, name, multi-line tagline, "Chat with AI" intro paragraph, chat input, 5 suggested-question chips, "Download CV" button, and 3 social icons. The visitor's eye has nowhere to rest, and the AI chat — the user's stated primary action — is buried.

The bio block below the hero is also a wall of text on a small screen (4 paragraphs of CV-style copy).

## Goals

- Make the AI chat the unambiguous primary action in the mobile hero.
- Fix the search/hamburger alignment so they share a vertical center.
- Reduce mobile hero height by removing demoted content (chips, CV, socials, chat-intro paragraph).
- Keep the demoted content discoverable lower on the page.
- Keep the bio readable without forcing a long scroll.
- **Mobile only.** Desktop and tablet (≥ 769px) layouts unchanged.

## Non-Goals

- No copy changes to bio content (truncation is layout-only — full text remains in the DOM, just collapsed).
- No changes to `/experience`, `/blog`, `/news-events`, or any admin page.
- No changes to the chat drawer, contact drawer, or theme toggle behaviour.
- No A/B tests or analytics changes.

## Design

### 1. Top bar alignment fix

Both interactive controls in `.top-nav` adopt the standard 44×44 touch target on mobile, vertically centered in the existing 56px nav.

| Element                  | Current (mobile)            | Target               |
|--------------------------|-----------------------------|----------------------|
| Site search input        | 47px tall, 11.2/16/11.2/44 padding | **44px tall**, padding adjusted to keep ≥16px text |
| Hamburger menu button    | 40×40, 8px padding          | **44×44**, padding 10px |
| Nav container            | 56px tall                   | unchanged            |

Implementation: scope the height/padding overrides inside the existing `@media (max-width: 768px)` block in `frontend/src/styles.css`. No JS change.

### 2. Hero simplification (mobile only)

The mobile hero renders only:

1. Badge ("Engineering Leadership // AI-Native Systems") — kept, unchanged.
2. Name (`Simon Rowe`) — kept, unchanged.
3. **One-line tagline.** Use `profile.title` (single line) instead of `profile.headline` on mobile, OR clamp the existing tagline to one line via `-webkit-line-clamp: 1`. Decision deferred to implementation: prefer the clamp so no data-shape change.
4. Chat input form — kept, unchanged.

Hidden on mobile (`display: none` at `max-width: 768px`):

- `.hero__chat-intro` (the "Chat with an AI assistant…" paragraph)
- `.hero__prompts` (the 5 suggested-question chips)
- `.hero__actions` (the "Download CV" button + `.hero__social` icon row)

Hero stays a single React component (`HeroSection.tsx`); the simplification is CSS-only. The hidden elements remain in the DOM at desktop widths.

### 3. New "Connect" strip

A new component `ConnectStrip` renders between `AboutSection` and `CTASection` on `HomePage`. It contains:

- "Connect" eyebrow heading (small caps).
- Full-width "Download CV" button (same `href` and behaviour as the current hero button).
- Centered row of social media icons (same data source as current `hero__social`).

`ConnectStrip` is **mobile-only**: it has `display: none` above 768px so the desktop hero remains the canonical home for these affordances. The component takes `socialMediaLinks` as a prop, mirroring `HeroSection`.

### 4. Bio "Read more" toggle (mobile only)

`AboutSection.tsx` gains a collapsed/expanded state controlled by a media-query hook (`useMediaQuery('(max-width: 768px)')`). On mobile:

- Default: render only the **first two paragraphs** of `profile.description` (split on `\n\n`).
- A "Read more" button reveals the remainder; toggles to "Read less" once expanded.
- Desktop: always render the full description, no button.

Paragraph splitting happens once per render (`useMemo` on `profile.description`). The button uses the existing `.button--text` style (or equivalent secondary style) — no new visual primitive needed.

### 5. Out of scope but worth noting

- The `Connect` strip naturally supports later additions (email, calendar link). Out of scope for this change.
- A future iteration could move the `Connect` strip into a sticky bottom bar on mobile. Not in this spec.

## Component / File Map

| File                                                | Change                                                                 |
|-----------------------------------------------------|------------------------------------------------------------------------|
| `frontend/src/styles.css`                           | Mobile media-query updates: nav control sizing, hero element hiding, ConnectStrip styles |
| `frontend/src/components/home/HeroSection.tsx`      | No structural change. Optional one-line tagline clamp class.           |
| `frontend/src/components/home/AboutSection.tsx`     | Add collapsed state + "Read more" toggle gated by media query.         |
| `frontend/src/components/home/ConnectStrip.tsx`     | **New.** CV button + social icons, mobile-only.                        |
| `frontend/src/pages/HomePage.tsx`                   | Render `<ConnectStrip>` between `<AboutSection>` and `<CTASection>`.   |
| `frontend/src/hooks/useMediaQuery.ts` (if missing)  | Small hook for `matchMedia` reactive query. Reuse if one exists.       |

## Testing

- **Automated:** Add a Vitest test for `AboutSection` covering: full content rendered when no media query match; truncated + toggle behaviour when matched. Mock `matchMedia`.
- **Manual:** Verify on iPhone 12 Pro viewport (390×844) and iPhone SE (375×667) via Playwright MCP:
  - Search input and hamburger share the same vertical centerline.
  - Hero contains only badge, name, tagline (1 line), and chat input.
  - Connect strip appears between bio and CTA, hidden on desktop.
  - "Read more" toggle expands/collapses bio, full text accessible.
  - Tablet (768px+) and desktop layouts unchanged.
- **Regression:** Confirm `/experience`, `/blogs`, `/news-events` pages still render the unchanged top nav with both controls aligned.

## Risks

- **Tagline data shape.** If `profile.headline` is the field rendered as the multi-line tagline today, a CSS clamp is safest. Verify the field at implementation time before deciding.
- **Duplicate links accessibility.** `ConnectStrip` and the (desktop-only) `hero__actions` both contain a CV link and the same social URLs. Because `display: none` removes elements from the accessibility tree, only the visible set is exposed at any viewport — no extra `aria-hidden` needed.
- **`useMediaQuery` SSR.** Project is Vite SPA, no SSR — safe.
