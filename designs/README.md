# Simon Rowe Design System

The brand and UI system for **simonrowe.dev** — a personal website + admin console
for Simon Rowe (Head of Engineering, Commercial Trading at Global). The site
combines a public surface (homepage, experience timeline, blogs, news & events)
with an authenticated admin CMS, and a chat-with-AI assistant trained on
Simon's experience.

## Sources

This design system is derived from:

- **Codebase:** GitHub `simonjamesrowe/simonrowe-dev-monorepo` (default branch `main`)
  - `frontend/src/styles.css` — single canonical stylesheet (~160KB, BEM, custom-property tokens)
  - `frontend/src/App.tsx` — public + admin route shell
  - `frontend/src/components/{layout,blog,chat,admin,experience,profile,skills}/*` — React 19 + TS components
  - `frontend/src/components/admin/AdminLayout.tsx` — admin sidebar shell
  - `CLAUDE.md` — official tech stack + design decisions
- **Reference designs (HTML in repo):**
  - `designs/landing-light.html` — light-mode hero + about + timeline
  - `designs/landing-dark.html` — dark-mode hero + about + timeline (default vibe)
  - `designs/experience-timeline.html` — alternating left/right timeline
- **Stack:** React 19, TypeScript, Vite, Lucide-React icons, MDXEditor, react-markdown,
  Auth0 (admin), STOMP (chat), Mermaid (blog diagrams).

## Index

| File | What it is |
|------|------------|
| `README.md` | This file — brand context, content + visual foundations, iconography |
| `SKILL.md` | Skill manifest for downloadable / Claude-Code use |
| `colors_and_type.css` | All CSS custom-property tokens (dark + light) and semantic type classes |
| `assets/` | Logos, favicons, sample profile / hero imagery (or placeholders) |
| `preview/` | Small HTML cards rendered in the Design System tab |
| `ui_kits/website/` | Public site UI kit — hero, timeline, blog, chat, footer |
| `ui_kits/admin/` | Admin console UI kit — sidebar, dashboard, blog editor, media library |

## Brand snapshot

- **Voice:** First-person, confident-but-grounded engineering leader.
  "I am driven to achieve real business value…" "I'm a strong advocate for
  AI-native engineering — but I believe AI amplifies good engineering, it
  doesn't replace it."
- **Themes:** AI-native development, cloud-native architecture, engineering
  leadership, clean code, microservices.
- **Audience:** Hiring managers, peers in engineering leadership, recruiters,
  developers reading the blog.

---

## CONTENT FUNDAMENTALS

### Voice & person
- **First person** on the personal-site copy ("I am driven…", "My technical
  toolkit includes…").
- **Conversational** but technically precise. Avoids hype words; reaches for
  concrete patterns ("event-driven and restful microservices", "Spring Boot and
  Kafka patterns", "structured evaluation frameworks").
- The chat assistant is third-person about Simon ("Chat with an AI assistant
  trained on Simon's experience…") and answers in his voice.

### Casing & punctuation
- **Sentence case** for headings ("Career Timeline", "About Simon",
  "Experience & Career"). NOT all-caps, NOT title-case-every-word.
- **Single accent word** in headings is colored primary: "Career **Timeline**",
  "About **Simon**", "Experience & **Career**".
- **Em dash (—)** is the workhorse separator: "Aug 2021 — Present", "Bachelor of
  Computer Science — WAM 81.24".
- **Eyebrows / badges** are UPPERCASE with letter-spacing
  (`text-transform: uppercase; letter-spacing: 0.08em`): "ENGINEERING
  LEADERSHIP // AI-NATIVE SYSTEMS".

### Sample copy (verbatim from the codebase)
- Hero badge: `Engineering Leadership // AI-Native Systems`
- Hero tagline: `Passionate about AI-native development, cloud-native
  architecture, and empowering engineering teams.`
- Chat intro: `Chat with an AI assistant trained on Simon's experience,
  skills, and career history.`
- Prompt chips: `What Spring Boot and Kafka patterns does he use?` /
  `What is he blogging about recently?` /
  `How does he handle event sourcing and CQRS?`
- CTA: `Let's build the impossible together.`
- Career section subhead: `A journey through engineering leadership, platform
  architecture, and software craftsmanship.`

### Emoji & symbols
- **Emoji are used sparingly** as functional accents, not decoration:
  - 💬 next to chat affordances
  - 🎓 graduation cap for the education timeline node
  - ⬇ download arrow on "Download CV"
- **Unicode rather than emoji** for separators: ` — ` (em dash), ` // ` (double
  slash) for taglines.
- **Lucide icons** (line, ~1.5 stroke) are the default everywhere else — never
  emoji as button icons.

### Tone targets
- Confident, not performative.
- Specific (technologies, patterns, scale: "30+ engineers", "three product
  pillars") rather than vague.
- Optimistic about AI but not breathless — "AI amplifies good engineering, it
  doesn't replace it".

---

## VISUAL FOUNDATIONS

### Default theme
- **Dark by default.** `:root` declares dark; `[data-theme="light"]` overrides.
  All hero shots and the showcased homepage live in dark.
- Theme toggle is part of the top-nav (Lucide `Sun`/`Moon`).

### Color
- **Surface (dark):** `#0f131c` base, layered up through
  `#181c25 → #1c2029 → #262a33 → #353943` for raised cards / chrome.
- **Surface (light):** `#f8f9fc` base, white cards, `#f0f2f7` low.
- **Primary (dark):** `#77d1ff` (sky-cyan) — used for accents, the colored word
  in headings, focus rings, current-job dot, gradient buttons.
- **Primary (light):** `#2563eb` (vivid blue) — same role.
- **Secondary (warm):** `#ffb690` dark / `#f97316` light — reserved for
  *education* timeline node and the occasional contrast stat. Never primary CTA.
- **Gradient accent:** `linear-gradient(135deg, primary, #a78bfa)` — only on
  hero send-button and primary CTAs in the design refs. Use sparingly.
- **Imagery vibe:** photos are rendered with **20-40% grayscale**, lifting to
  full color on hover (about-section + timeline logos). Hero background is a
  cool, slightly cinematic AI-generated illustration with a heavy
  bottom-fading overlay so text stays legible.

### Typography
- **Display + headings:** `Space Grotesk` (500/600/700). Tight tracking
  (`-0.02 to -0.03em`).
- **Body / UI:** `Inter` (400/500/600). 1.6 line-height for body.
- **Mono:** system mono for code blocks.
- One color-accent word per heading is the canonical move.

### Spacing
- 8pt grid via `--space-*` tokens. Section padding is generous on desktop
  (5rem / 6rem vertical), tight on mobile (3rem).
- Max content width: `1100px` for prose / timeline, `80rem` for grids.

### Backgrounds
- **No repeating patterns, no hand-drawn illustrations.**
- Hero uses ONE full-bleed cinematic photograph + a layered gradient overlay
  (linear top-to-bottom + radial center-top). Always fades to surface at the
  bottom — never a hard edge.
- Cards live on flat surface tokens. Subtle gradient ONLY on the timeline
  spine (vertical line fades primary → border → transparent).
- The CTA section on light mode uses a gentle `linear-gradient(to bottom,
  surface, surface-overlay)` — that's it.

### Animation & motion
- **Three speeds:** `fast 0.15s`, `normal 0.25s`, `slow 0.4s cubic-bezier(.4,0,.2,1)`.
- Hover effects: `translateY(-2px)`, border-color shift to primary, soft glow
  shadow (`0 4px 24px rgba(primary, .06)`).
- Press: `transform: translateY(0)` or `scale(0.9)` on icon buttons.
- Chat dot pulse (2s ease-in-out infinite), typing dots bounce (1.2s).
- No bounces, no flashy entrance animations. Motion is utility-grade.

### Hover / press states
- **Hover:** border lightens to primary or `border-faint`; subtle lift
  (`translateY(-2px)`); cyan-tinted shadow.
- **Press / active:** removes the lift; on icon buttons, `scale(0.9)`.
- **Focus-visible:** `outline: 2px solid var(--primary); outline-offset: 2px`.
- Photos lift grayscale on hover (`grayscale 30% → 0%` over 0.4s).

### Borders
- Mostly **rgba-on-surface** rather than hard hex — `rgba(255,255,255,0.06–0.15)`
  in dark, `rgba(0,0,0,0.06–0.15)` in light. This lets borders survive theme
  swaps without re-tuning.
- Accent border for the active/current state: `rgba(119,209,255,0.30)` (dark)
  / `rgba(37,99,235,0.25)` (light).

### Shadows
- Four-step elevation: `--shadow-sm | -md | -lg | -ambient`. Light theme uses
  near-pure black at low opacity (0.06–0.10); dark theme uses 0.10–0.30.
- Cyan **glow** (`--glow-primary`) reserved for: focus rings, current timeline
  dot, primary CTA button.
- No drop-shadow inside text; `text-shadow` only on hero copy for legibility
  over imagery.

### Capsules vs protection gradients
- Pills (`border-radius: 999px`) are the default for: nav, chat input,
  buttons, badges, prompt chips, search input, avatar circles.
- Cards: `border-radius: 1rem` (16px) — `--radius-lg`.
- Larger panels / drawers: `1.5rem` (24px) — `--radius-xl`.
- Inputs (rectangular): `0.75rem` (12px) — `--radius-md`.
- Hero copy uses **gradient overlay protection** (linear + radial), NOT
  capsules — the title sits directly on the photograph.

### Transparency & blur
- **Glass nav:** `background: rgba(11,15,24,0.7)` + `backdrop-filter: blur(20px)`
  on the fixed top nav. Light: `rgba(248,249,252,0.85)`.
- **Drawers:** opaque container, but the overlay behind is
  `rgba(0,0,0,0.35) + blur(2px)`.
- Otherwise transparency is for hover states only — NOT a decorative motif.

### Imagery
- **Color vibe:** cool / blue-leaning, slightly cinematic. Cropped tight.
  Grayscale-by-default (20–40%), saturates on hover. No grain, no warm
  filters, no duotones.
- **Aspect:** profile = `3/4` portrait, hero = full-bleed, blog cards =
  square or 16:9 left/right alternating.
- Logos in timeline cards are 44–48px squares with `border-radius: 8px`,
  `object-fit: cover`, sitting on `surface-container-high`.

### Layout rules
- **Fixed top nav** (80px desktop / 56px mobile). Main content gets matching
  `padding-top`.
- **Sticky drawers** slide in from the right (`width: 480px`, max viewport).
- **Mobile:** single column, year labels hidden in timeline, sidebar collapses
  to hamburger. Inputs become 16px font-size to prevent iOS zoom.
- The admin console uses a fixed left sidebar (`240px`) with main content to
  the right.

### Cards (canonical look)
```
background:    var(--surface-container-low)   /* dark */ or white /* light */
border:        1px solid var(--border-subtle)
border-radius: 1rem (--radius-lg)
padding:       1.25rem 1.5rem
hover:         border lifts to var(--border-faint),
               box-shadow: 0 4px 24px rgba(primary, .06),
               transform: translateY(-2px)
```

---

## ICONOGRAPHY

- **Primary system: Lucide React** (`lucide-react@0.575.0`) — used everywhere
  in the codebase (`AdminLayout`, nav, chat, blog, contact). Strokes are
  ~1.5px, rounded line-caps, 18–20px sizes are typical.
- We surface Lucide via the **CDN** in this design system
  (`https://unpkg.com/lucide@latest/dist/umd/lucide.js`) so HTML mocks can
  render real icons without a build step. See `ui_kits/website/index.html`.
- **Brand logos:** company logos in the timeline (Global, Y-Tree, Upp,
  Pivotal) are bitmap uploads served from `/uploads/`. We have NOT imported
  them — they live behind the running backend. The mocks use:
  - First-letter monogram placeholders (`U`, `M`, `S`, `C`) styled in primary
    Space Grotesk for jobs without a logo.
  - `🎓` (graduation cap unicode) for the education node — the ONE deliberate
    emoji in the system.
- **Favicons:** `assets/favicon.ico` is copied from the codebase.
- **No SVG illustrations** are drawn or generated here. None in source.
- **No icon font.** Lucide-as-component or Lucide-CDN only.

If you need an icon not in Lucide, prefer adding a Lucide alternative or a
real PNG from `/uploads/` — never hand-roll an SVG decoration.

---

## Caveats

- **Photographic assets** (`Chat_GPT_Image_Nov_16_2025_*.jpg`,
  `simon_profile_*.jpg`, company logos) are stored on the running backend at
  `http://localhost:8080/uploads/` and are not in the repo. Mocks reference
  Unsplash placeholders + monogram fallbacks. Replace with the real assets
  when productionizing.
- The MDX editor (`@mdxeditor/editor`) and Mermaid have rich custom styles in
  `styles.css` (toolbars, code blocks). The blog UI kit ships a simplified
  visual recreation, not a working editor.
