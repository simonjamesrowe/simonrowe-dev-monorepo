# Research: Light & Dark Mode Theme Support

**Feature**: 017-light-dark-mode
**Date**: 2026-04-10

## R1: Theme Switching Mechanism

**Decision**: Use a `data-theme` attribute on `<html>` to toggle between `:root` (dark, default) and `[data-theme="light"]` variable overrides, managed by a React ThemeContext.

**Rationale**: CSS custom properties are already used for ~69% of color references. Overriding variables via a data attribute is the simplest approach — no class duplication, no separate stylesheet, no CSS-in-JS. The constitution mandates plain CSS + BEM + custom properties in a single `styles.css` file.

**Alternatives considered**:
- Separate `light.css` / `dark.css` files: Rejected — constitution requires a single `styles.css`.
- `prefers-color-scheme` media queries only: Rejected — doesn't allow manual toggle or persistence.
- CSS class on `<body>`: Works but `data-theme` on `<html>` is more semantic and allows flash prevention via inline script in `<head>`.

## R2: Flash of Wrong Theme Prevention

**Decision**: Add a blocking inline `<script>` in `index.html` `<head>` that reads localStorage and sets the `data-theme` attribute before any CSS or React renders.

**Rationale**: React hydration is too late — the page paints with default styles first. A synchronous script in `<head>` ensures the attribute is set before the first paint. This is a standard pattern used by Next.js, Docusaurus, and Tailwind dark mode implementations.

**Alternatives considered**:
- React-only approach (set in useEffect): Causes flash of dark theme before light applies.
- CSS-only with `prefers-color-scheme`: Doesn't handle stored preferences or manual overrides.

## R3: Hardcoded Color Migration Strategy

**Decision**: Introduce new CSS custom properties for hardcoded colors that differ between themes. Convert ~340 hardcoded color values to variables incrementally, prioritising public-facing sections. Admin panel is out of scope.

**Rationale**: The current CSS has 753 variable usages and 340 hardcoded values. Not all hardcoded values need conversion — some (e.g., transparent overlays that work in both themes) can stay. Focus on values that directly affect readability or visual appearance in the opposite theme.

**Priority order**:
1. Hero section gradient overlays (rgba values referencing surface color)
2. `color: white` usages (~30 instances) → `var(--on-surface)`
3. Border/separator `rgba(255,255,255,...)` values → new `--border-*` variables
4. Error/success state colors (already work in both themes, low priority)
5. Skeleton loader grays (#e5e7eb) → new `--skeleton-*` variables

**Out of scope**: Admin panel styles (as per spec assumptions).

## R4: Light Theme Color Palette

**Decision**: Use the palette from `designs/landing-light.html` as the light theme baseline, adapted to the existing Material Design 3 variable structure.

**Key mappings**:
| Variable | Dark Value | Light Value |
|----------|-----------|-------------|
| --surface | #0f131c | #f8f9fc |
| --surface-container | #1c2029 | #ffffff |
| --surface-container-low | #181c25 | #f0f2f7 |
| --surface-container-high | #262a33 | #e8eaf0 |
| --on-surface | #dfe2ef | #1a1d2b |
| --on-surface-variant | #bec8cf | #4b5563 |
| --primary | #77d1ff | #2563eb |
| --primary-container | #299bca | #dbeafe |
| --outline | #889299 | #d1d5db |
| --outline-variant | #3e484e | #e5e7eb |

**Rationale**: The landing-light.html prototype was reviewed and approved by the user. Keeping the same variable names with swapped values is the simplest approach and requires zero component changes.

## R5: Theme Toggle UI

**Decision**: Add a Sun/Moon icon button to the TopNav component, positioned before the search input. Uses Lucide React `Sun` and `Moon` icons (already the mandated icon library).

**Rationale**: Constitution requires Lucide React for all icons. TopNav is present on every page, ensuring the toggle is always accessible. The icon visually communicates the current theme state.

## R6: Persistence Mechanism

**Decision**: Use browser `localStorage` with key `theme-preference`. Values: `"light"`, `"dark"`, or absent (system default).

**Rationale**: Spec assumes browser localStorage is acceptable. No server-side storage needed. The blocking script in `<head>` reads this on every page load.

## R7: Hero Background Image in Light Mode

**Decision**: Swap the hero overlay gradient from dark-based (`rgba(15,19,28,...)`) to light-based (`rgba(248,249,252,...)`) when in light theme, using CSS custom properties for the gradient color stops.

**Rationale**: The background image must remain visible in both themes. The overlay needs to ensure text contrast. The `designs/landing-light.html` prototype already demonstrates this with a white-washed overlay.
