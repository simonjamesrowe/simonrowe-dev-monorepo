# Data Model: Light & Dark Mode Theme Support

**Feature**: 017-light-dark-mode
**Date**: 2026-04-10

## Entities

### Theme Preference (Client-Side Only)

This feature has no server-side data model. All state is client-side.

**Storage**: Browser `localStorage`
**Key**: `theme-preference`
**Values**: `"light"` | `"dark"` | *(absent — means follow system preference)*

**Lifecycle**:
- Created when user manually toggles the theme for the first time.
- Updated on each subsequent toggle.
- Never expires (persists until browser storage is cleared).
- Absence means "use system preference" — there is no explicit "system" value stored.

### Theme Configuration (CSS Custom Properties)

Two sets of CSS custom property values define the visual themes. These are not database entities — they are static design tokens in `styles.css`.

**Dark Theme** (`:root` — default):
- Surface palette: dark navy/charcoal tones
- Text: light gray/white
- Primary accent: cyan (#77d1ff)
- Secondary accent: orange (#ffb690)
- Shadows: dark, pronounced
- Hero overlay: dark gradient wash

**Light Theme** (`[data-theme="light"]`):
- Surface palette: white/light gray tones
- Text: dark navy/charcoal
- Primary accent: blue (#2563eb)
- Secondary accent: orange (#f97316)
- Shadows: subtle, soft
- Hero overlay: light gradient wash

### State Transitions

```
[First Visit] → detect OS preference → apply matching theme
[Manual Toggle] → switch theme → store preference in localStorage
[Return Visit] → read localStorage → apply stored theme (ignores OS)
[Toggle Again] → switch theme → update localStorage
```

No server-side API calls. No database reads or writes.
