# Quickstart: Light & Dark Mode Theme Support

**Feature**: 017-light-dark-mode
**Date**: 2026-04-10

## Overview

Add light and dark theme support to the public-facing frontend. The site currently has a dark-only theme using CSS custom properties. This feature adds a light theme variant, automatic OS preference detection, a manual toggle, and preference persistence.

## Architecture

```
index.html        ← Blocking <script> reads localStorage, sets data-theme attribute
    ↓
styles.css        ← :root (dark default) + [data-theme="light"] overrides
    ↓
ThemeContext.tsx   ← React context: provides theme state + toggle function
    ↓
TopNav.tsx        ← Sun/Moon toggle button consumes ThemeContext
```

## Key Files to Create

| File | Purpose |
|------|---------|
| `frontend/src/contexts/ThemeContext.tsx` | React context provider with theme state, toggle, and localStorage sync |

## Key Files to Modify

| File | Change |
|------|--------|
| `frontend/index.html` | Add blocking script in `<head>` to prevent flash |
| `frontend/src/styles.css` | Add `[data-theme="light"]` variable overrides; convert hardcoded colors to variables |
| `frontend/src/components/layout/TopNav.tsx` | Add theme toggle button (Sun/Moon icon) |
| `frontend/src/App.tsx` | Wrap app in ThemeProvider |

## Implementation Order

1. **Theme infrastructure**: ThemeContext + blocking script + data-theme attribute
2. **Light theme CSS variables**: Define `[data-theme="light"]` overrides in styles.css
3. **Hardcoded color migration**: Convert `rgba(15,19,28,...)`, `color: white`, border opacities to CSS variables
4. **Theme toggle UI**: Add Sun/Moon button to TopNav
5. **Hero section**: Add theme-aware overlay gradient variables
6. **Component sweep**: Verify all drawers, modals, chat, search render correctly in both themes
7. **Testing**: Visual verification of all pages in both themes

## No Backend Changes

This feature is entirely frontend. No API changes, no database changes, no backend configuration.

## Constraints (from Constitution)

- CSS must remain in a single `styles.css` file (Principle II)
- Icons must use Lucide React (Principle II)
- Admin pages are out of scope (Spec assumption)
- No CSS framework or CSS-in-JS may be introduced (Principle II)
