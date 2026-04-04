# Design System Specification: High-End Portfolio Editorial

## 1. Overview & Creative North Star
This design system is built to reflect the intersection of high-level engineering leadership and the frontier of AI-native development. We are moving away from the "standard dashboard" aesthetic into a realm of **The Digital Architect**.

**Creative North Star: "Precision Luminescence"**
The system is anchored in a deep, nocturnal palette where depth is created through light and atmospheric layering rather than structural lines. We reject the rigid, boxed-in nature of traditional portfolios in favor of an editorial experience that feels fluid, sophisticated, and technologically authoritative. Intentional asymmetry, oversized display typography, and "glass" surfaces create a sense of breathing room and premium quality.

---

## 2. Colors & Surface Philosophy
The palette centers on a sophisticated dark mode that prioritizes optical comfort for technical reading while utilizing high-energy accents.

### The "No-Line" Rule
Standard 1px borders are strictly prohibited for sectioning. Structural separation must be achieved through:
1.  **Background Shifts:** Transitioning from `surface` (#0f131c) to `surface_container_low` (#181c25).
2.  **Negative Space:** Utilizing the larger ends of our spacing scale (16, 20, 24) to denote new thematic blocks.

### Surface Hierarchy & Nesting
Treat the UI as a physical stack of semi-transparent layers. 
*   **Base:** `surface_dim` (#0f131c)
*   **Secondary Sections:** `surface_container` (#1c2029)
*   **Interactive Cards/Modules:** `surface_container_high` (#262a33)
*   **Active/Floating Elements:** `surface_bright` (#353943)

### The "Glass & Gradient" Rule
To achieve the "AI-native" feel, use **Glassmorphism** for floating headers or command palettes. Use a background of `surface` at 60% opacity with a `backdrop-blur` of 20px. 
**Signature Texture:** Primary CTAs should use a linear gradient: `primary` (#77d1ff) to `primary_container` (#299bca) at a 135-degree angle to provide a subtle "glow" effect that flat colors lack.

---

## 3. Typography
The typographic system creates an editorial rhythm between the technical precision of **Inter** and the modern, geometric character of **Space Grotesk**.

*   **Display & Headlines (Space Grotesk):** Used for "The Hook." High-contrast scaling (e.g., `display-lg` at 3.5rem) should be used to break the grid. These headlines convey authority and the "Architect" persona.
*   **Body & Titles (Inter):** Optimized for technical legibility. Technical blog posts must use `body-lg` (1rem) with a generous line height (1.6) to ensure clarity during long-form reading.
*   **Labels (Inter):** Used for metadata, tech stacks, and micro-copy. Always in `label-md` or `label-sm` to maintain a clear hierarchy.

---

## 4. Elevation & Depth
We define hierarchy through **Tonal Layering** and light physics, not drop shadows.

*   **The Layering Principle:** A card should not "float" with a shadow by default. Instead, place a `surface_container_low` card on a `surface` background. The subtle shift in hex value creates a "soft lift."
*   **Ambient Shadows:** For high-level modals or floating navigation, use a "Wide-Atmosphere" shadow: `box-shadow: 0 20px 50px rgba(0, 0, 0, 0.3)`. The shadow must feel like a natural light obstruction, not a line.
*   **The Ghost Border Fallback:** If a border is required for accessibility on cards, use `outline_variant` (#3e484e) at **15% opacity**. This creates a "glint" on the edge of the glass without boxing in the content.
*   **AI Glow:** For active states, use a subtle outer glow using the `primary` token (#77d1ff) with a 20% opacity and 15px blur.

---

## 5. Components

### Buttons
*   **Primary:** Gradient fill (Primary to Primary-Container), `md` (0.375rem) roundedness, and a subtle inner-glow on hover.
*   **Secondary:** Ghost style with a `Ghost Border` (Outline-variant at 20%) and `on_surface` text.
*   **Tertiary:** Text-only with `primary` color and an underline that appears on hover.

### Cards (The "Editorial Module")
Forbid divider lines. Use `spacing-8` (2rem) between content blocks within the card. Headers within cards should use `title-lg`. For portfolio items, use an asymmetrical layout where text overlaps the edge of an image container slightly.

### Input Fields
*   **Base:** `surface_container_lowest` (#0a0e17) background.
*   **State:** On focus, the border transitions from 0% opacity to 40% `primary` color with a 4px "Soft Glow" (shadow).
*   **Typography:** Labels use `label-md` in `on_surface_variant`.

### Chips (Skill Tags)
Chips should feel like "pills of light." Use `surface_container_high` with a 1px `Ghost Border`. For "Core Skills," use a subtle `secondary` (#ffb690) text color to differentiate from general tags.

### Technical Blog Layout (Custom Component)
*   **Reading Rail:** Max width of 720px, centered.
*   **Code Blocks:** Background of `surface_container_lowest` with `primary` accents for syntax highlighting. No borders; use `xl` (0.75rem) roundedness.

---

## 6. Do's and Don'ts

### Do
*   **Do** use asymmetrical margins. If a section is centered, let the headline bleed into the left margin to create an editorial look.
*   **Do** use `primary_fixed_dim` for icons to ensure they feel integrated into the dark UI without being "neon."
*   **Do** prioritize vertical whitespace. If you think there is enough space, add `spacing-4` more.

### Don't
*   **Don't** use pure black (#000000). Always use `surface` (#0f131c) to maintain the "blue-ink" depth of the sophisticated palette.
*   **Don't** use standard dividers or HR tags. Use background color steps (`surface` to `surface_container`) to define boundaries.
*   **Don't** use high-contrast white text (#FFFFFF) for long body copy. Use `on_surface_variant` (#bec8cf) to reduce eye strain in dark mode. Reserved #FFFFFF for `display` headings only.