---
name: simonrowe-design
description: Use this skill to generate well-branded interfaces and assets for simonrowe.dev (Simon Rowe's personal site + admin console), either for production or throwaway prototypes/mocks/etc. Contains essential design guidelines, colors, type, fonts, assets, and UI kit components for prototyping.
user-invocable: true
---

Read the README.md file within this skill, and explore the other available files.

If creating visual artifacts (slides, mocks, throwaway prototypes, etc), copy assets out and create static HTML files for the user to view. If working on production code, you can copy assets and read the rules here to become an expert in designing with this brand.

If the user invokes this skill without any other guidance, ask them what they want to build or design, ask some questions, and act as an expert designer who outputs HTML artifacts _or_ production code, depending on the need.

## Quick map of this skill

- `README.md` — brand context, content + visual foundations, iconography
- `colors_and_type.css` — drop-in CSS variables (dark + light themes) + semantic type classes
- `assets/` — favicon and any logos pulled from the codebase
- `preview/` — small reference cards (one per concept)
- `ui_kits/website/` — public site recreation (hero, timeline, blog, chat drawer, footer)
- `ui_kits/admin/` — admin console recreation (sidebar, dashboard, blog editor, events, media)

## Defaults
- **Dark theme** unless asked otherwise.
- **Space Grotesk** for headings, **Inter** for body, monospace for code.
- One **colored-accent word** per heading (`Career <span class="sr-accent">Timeline</span>`).
- **Lucide icons**, ~1.5 stroke. No hand-drawn SVGs, no emoji except `🎓` (education).
- Pills (`border-radius: 999px`) for nav, chips, badges; `1rem` radius for cards.
- **Glass-blur top nav** + cool, slightly cinematic imagery (grayscale 0.3 default).
