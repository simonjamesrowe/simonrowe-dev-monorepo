# Contract: Icon & Logo Asset Manifest

**Status**: 🚦 **AWAITING HUMAN APPROVAL (FR-032 / task T075).**
`V016InstallSkillAndCompanyIcons` must not be written until the set below is
approved.

**Rendered preview**: `.context/icon-preview/icon-logo-approval-preview.png`
(gitignored). Regenerate with `python3 -m http.server` in
`.context/icon-preview/` and open `index.html` — both options, both themes, plus
the employer logos treated and untreated. All candidate assets are already
downloaded into that directory.

The rendered preview confirmed three things the tables below only asserted:

1. Option B (Lucide) is the only set that reads as one system in both themes.
2. In Option A, `junit5` renders as a numeral "5" in a circle for "Testing" and
   `kotlin` as a solid black triangle for "Java / Kotlin" — neither is legible as
   a category mark at 32px, and the `anthropic` mark for "Artificial
   Intelligence" is unmistakably one vendor's logo.
3. Untreated, **Global, Universal Music Publishing and Macquarie are effectively
   invisible on the dark theme** — the monochrome-per-theme treatment is required,
   not a preference.

All live data in this document was read from the production read-only API
(`api.simonrowe.dev`) on 2026-07-30 — it is fact, not assumption. This satisfies
tasks T003 and T004.

---

## Part 0 — Live data preconditions (T003, T004)

### Blog content-type split (T004) — confirmed exactly

| Metric | Value |
|---|---|
| Total published posts | **43** |
| Carrying the `Weekly Digest` tag | **15** → `DIGEST` |
| Remainder | **28** → `ENGINEERING` |

Matches `spec.md`'s assumption with no adjustment needed. There is exactly one
tag whose name matches — `"Weekly Digest"`, with that exact casing — among 50
distinct tag names, so the case/whitespace-insensitive matching in V015 is
defensive rather than load-bearing. Keep it anyway (FR-020 requires it).

### GitHub social links (T003) — the two `link` values V017 must match on

| `link` | Current `name` | Target `name` |
|---|---|---|
| `https://github.com/simonrowe` | `Personal Github Account` | `GitHub — personal` |
| `https://github.com/simonjamesrowe` | `Public org for all repos that make up www.simonjamesrowe.com` | `GitHub — this site` |

> **Finding that changes the diagnosis**: both links *already have* distinct
> names. The reason the UI shows "GitHub" twice is purely
> `SocialLinks.tsx:34,41` preferring the type label over `link.name`. So the
> frontend one-line fix (T091) alone resolves the user-visible defect; V017 only
> shortens the two names to something that fits a label. **V017 is cosmetic, not
> corrective** — worth knowing if the PR needs trimming.

### Skill groups (T003) — 10 groups, exact names

| `name` | rating | current image |
|---|---|---|
| Artificial Intelligence | 8.0 | **none** |
| Java / Kotlin | 8.6 | `..._medium.png` (Kotlin mark) |
| Spring | 9.5 | `..._medium.png` |
| Cloud | 7.3 | `..._medium.png` (Kubernetes) |
| CI/CD | 8.3 | `..._medium.png` (Jenkins) |
| Data Persistence / Search | 7.2 | `..._medium.png` (MongoDB) |
| Testing | 8.0 | `..._medium.png` (Mockk) |
| Web | 6.9 | `..._medium.png` (React) |
| Messaging / Events | 9.0 | `..._medium.png` (Kafka) |
| Identity & Security | 7.6 | `..._medium.png` |

Two things fall out of this table:

1. **Only "Artificial Intelligence" is actually broken** (no icon at all). The
   other nine have an icon; the complaint is that they are stylistically
   inconsistent, not missing.
2. **Ratings are decimals, not integers.** `8.6`, `7.3`, `6.9`, `7.2`. The level
   bands in FR-027 must therefore be defined on continuous values —
   `>= 9` Expert, `>= 7` Advanced, `>= 5` Proficient, else Familiar — not on the
   integer sets "9–10 / 7–8 / 5–6" as the design doc phrases them. `6.9` must
   read **Proficient** and `8.6` **Advanced**. **This affects task T074 and its
   test T073.**

### Employers (T003) — 10 jobs, 9 companies for the logo strip

| `company` | Role | Period | `isEducation` |
|---|---|---|---|
| Global | Head of Engineering | 2021-08 → present | false |
| Y-Tree | Senior Developer | 2020-05 → 2021-07 | false |
| Upp Technologies | Software Engineering Lead | 2019-04 → 2020-05 | false |
| Pivotal | Senior Platform Architect | 2018-08 → 2019-04 | false |
| Universal Music Publishing | Senior Director, Java Development | 2011-07 → 2018-08 | false |
| Workcover Queensland | Senior Applications Developer | 2009-11 → 2011-05 | false |
| Macquarie Group | Analyst / Programmer | 2008-02 → 2009-11 | false |
| SAS | Junior Applications Developer | 2006-09 → 2008-02 | false |
| Civica | Graduate Developer | 2005-01 → 2006-08 | false |
| University of Newcastle | Computer Science | 2002-01 → 2004-12 | **true** |

- The logo strip filters `isEducation !== true`, giving **9 logos**.
- `Global` is the current role (no `endDate`) — this is what the Currently strip
  keys off.
- Every job already has a `companyImage`. Global's and Workcover's are `.jpg`;
  the rest are `.png`.
- Note the exact strings for V016's matching: `SAS` (not "SAS Institute"),
  `Universal Music Publishing` (not "Universal Music Group"),
  `Workcover Queensland` (lower-case `c`).

---

## Part 1 — Skill group icons: the decision to approve

### What the design doc proposed, and why it does not survive contact

The design doc said: *Devicon SVGs for brand marks … and visually matching
generic SVGs for Artificial Intelligence, Testing, Identity & Security.*

Rendering that set side by side shows it does not work. **Devicon marks are not
consistent with each other**, let alone with a generic mark:
`jenkins-original` is a detailed illustrated butler's head, `java-original` a
shaded coffee cup, `kotlin-original` a flat gradient chevron,
`typescript-original` a filled square badge. Dropping a monochrome line icon into
that row for "Artificial Intelligence" reads as an asset that failed to load.

Devicon is also missing a usable monochrome variant for the marks we need
(`spring`, `react`, `apachekafka` have no `plain` variant), so "all-Devicon,
monochrome" is not achievable either.

### Option A — Simple Icons, all ten (recommended)

Monochrome single-path silhouettes on a uniform 24×24 grid. One visual system,
still brand-recognisable. Licence **CC0-1.0** (public domain dedication) — the
cleanest of the three candidate sets.

`https://raw.githubusercontent.com/simple-icons/simple-icons/16.27.1/icons/<slug>.svg`

| Skill group | Slug | Semantic fit | Note |
|---|---|---|---|
| Java / Kotlin | `kotlin` | good | Simple Icons has **no `java`** slug (withdrawn on Oracle trademark request). `kotlin` is also today's icon |
| Spring | `spring` | exact | |
| Cloud | `kubernetes` | loose | Same looseness as today's data |
| CI/CD | `githubactions` | loose | `jenkins` also available and matches today |
| Data Persistence / Search | `mongodb` | loose | Matches today |
| Web | `react` | loose | Matches today |
| Messaging / Events | `apachekafka` | loose | Matches today |
| Testing | `junit5` | loose | Replaces the odd `mockk` |
| Identity & Security | `openid` | reasonable | Prefer `openid` over `okta` — vendor-neutral |
| Artificial Intelligence | `anthropic` | **poor** | ⚠️ Using one vendor's logo for a whole capability is misleading. This is Option A's weak point |

### Option B — Lucide, all ten

The site already uses `lucide-react` everywhere and the constitution names Lucide
as *the* icon library, so this is the most stylistically native choice. Licence
**ISC**. Semantically correct for categories, and no trademark question at all.

`https://raw.githubusercontent.com/lucide-icons/lucide/1.27.0/icons/<name>.svg`

| Skill group | Icon |
|---|---|
| Artificial Intelligence | `brain-circuit` |
| Java / Kotlin | `coffee` |
| Spring | `leaf` |
| Cloud | `cloud` |
| CI/CD | `git-branch` (or `infinity`) |
| Data Persistence / Search | `database` |
| Testing | `flask-conical` |
| Web | `globe` |
| Messaging / Events | `radio-tower` (or `send`) |
| Identity & Security | `shield-check` |

**Trade-off**: loses brand recognition. Mitigated by the fact that each card
already shows the group name in text and lists its individual skills.

### Recommendation

**Option B (all-Lucide).** Reasons, in order of weight:

1. It is the only option with **no weak member** — Option A's
   `anthropic`-for-"Artificial Intelligence" is actively misleading, and that is
   the one group whose icon is currently missing, i.e. the one we most need to get
   right.
2. Seven of the ten groups are **capability categories, not products** (Cloud,
   CI/CD, Data Persistence / Search, Testing, Web, Messaging / Events, Identity &
   Security). Category icons are the honest representation; the current
   product-logo-per-category mapping is itself part of what makes the grid look
   arbitrary.
3. It matches the rest of the site's iconography and the constitution's
   Lucide-only rule, so nothing else on the page looks like a different system.
4. It sidesteps the dark-theme brand-colour problem entirely (see below).

Option A is a defensible alternative if brand recognition on the skills grid is
judged more valuable than category accuracy — in which case swap
`anthropic` → Lucide `brain-circuit` and accept one mixed mark.

### ⚠️ Theming: a real constraint, not a detail

`SkillGroupCard.tsx:18-30` renders the icon as `<img src={url}>` pointing at
`/uploads/…`. An `<img>` loads the SVG as a **separate document**, so
`currentColor` resolves to black and no CSS from the page cascades into it.

Both Simple Icons and Lucide ship without a usable colour: Simple Icons paths
have no `fill`, Lucide strokes use `currentColor`. Left alone, **every icon
renders black and effectively disappears on the dark theme** (verified by
rendering).

Fix, in order of preference:

1. **Bake one uniform mid-tone `stroke`/`fill` into every SVG at bundle time** —
   a single slate tone with ≥3:1 contrast against both the light and the dark
   surface. No component change, no theme override, and a uniform tone is what
   makes a mixed-provenance set read as one system anyway. **Recommended.**
2CSS `filter` inversion under `[data-theme="dark"] .skill-group-card__image` —
   works, but adds a theme-coupled rule for every consumer of the asset.
3. Switch the component to `mask-image` so the mark inherits `currentColor` —
   cleanest in theory, largest diff, and changes a component US7 otherwise
   does not touch.

Whichever is approved must be recorded here before T076.

### Pipeline: verified to work end-to-end already

- `MediaService.java:25-27` allows `image/svg+xml`
- `ImageVariantGenerator.java:36-38` short-circuits on SVG (no variants generated)
- `SkillGroupCard.tsx:10` falls back to `image.url` when `formats.thumbnail` is absent

So SVGs need **no code change** to display. Confirmed, not assumed.

---

## Part 2 — Employer logos

Every job already has a logo. This is an *upgrade*, and unlike the skill icons it
is only partially achievable.

Wikimedia direct-file URLs are deterministic:
`https://upload.wikimedia.org/wikipedia/commons/<h[0]>/<h[0:2]>/<Filename_with_underscores>`
where `h = md5(filename)`. No API call, so no rate limiting.

| Employer | Proposed asset | Format | Licence | Verdict |
|---|---|---|---|---|
| Pivotal | `commons/6/6b/Pivotal_Software_logo.svg` | SVG 394×98 | Public domain (`PD-textlogo`) | ✅ Ship. Correct historical mark for a 2018–19 role |
| Macquarie Group | `commons/f/f8/Macquarie_Logo.svg` | SVG 512×90 | Public domain | ✅ Ship. **Must use this one** — the alternative `commons/0/06/Macquarie-logo.svg` is CC BY-SA 4.0 and would drag attribution + share-alike onto the page |
| SAS | `commons/1/10/SAS_logo_horiz.svg` | SVG 1024×420 | Public domain | ✅ Ship |
| Civica | `civica.com/globalassets/6.images/rebrand/image-library/logo/civica_logo_desktop.svg` | SVG 170×36 | Proprietary, from Civica's own site | ✅ Ship. Teal `#009ca6`, legible in both themes |
| Universal Music Publishing | `commons/3/37/Universal_Music_Publishing_Group_2026.svg` | SVG 512×160 | Public domain | ⚠️ Ship with a caveat — this is the **2026 refresh**; the 2011–18 tenure used the older mark. Anachronism to accept or decline |
| Global | `commons/e/ed/Global_Media_%26_Entertainment_Black.png` | **PNG 900×1320** | Public domain | ⚠️ Raster only — no SVG exists. Still a real upgrade on today's 406×600 JPEG. **But the aspect ratio is portrait (stacked lockup)**, wrong for a horizontal strip — either crop to the `g` symbol or accept one tall item |
| Y-Tree | `y-tree.com/wp-content/uploads/2025/11/ytree-logo-white-text.svg` | SVG 91×56 | Proprietary | ⚠️ **White text — invisible on the light theme.** Needs the text `fill` edited to yield one asset usable in both themes |
| University of Newcastle | — | — | Fair use only | ❌ **Do not ship.** The official SVG is behind a WAF (403 to every automated fetch); the only reachable alternative is a fair-use en.wikipedia upload, not licensed for reuse. Keep the existing asset. (Also `isEducation`, so it is not in the logo strip anyway) |
| Workcover Queensland | — | — | — | ❌ No reachable source; site returns 403. Keep the existing `..._medium.jpg` |
| Upp Technologies | — | — | — | ❌ No source; `upp.ai` now serves only a placeholder favicon. Keep the existing `..._medium.png` |

**Net**: 4 clean upgrades, 3 upgrades with a caveat, 3 left as-is.

### ⚠️ Dark-theme legibility — verified by rendering on `#0f1115`

**Global, UMG, Macquarie and the UoN fallback are solid-black artwork and
effectively disappear on the dark theme.** Y-Tree has the inverse problem on
light. Only Pivotal (teal), Civica (teal) and SAS (blue) survive both untreated.

Because the nine logos come from six different sources with different weights and
aspect ratios, per-logo fixes will not produce a coherent row. The recommendation
is a **single monochrome treatment flipped per theme**, applied to the strip and
the timeline:

```css
.employer-logo-strip__logo        { filter: grayscale(1) brightness(0) opacity(.7); }
[data-theme="dark"] .employer-logo-strip__logo
                                  { filter: grayscale(1) brightness(0) invert(1) opacity(.7); }
```

This also normalises the weight mismatch, which is the larger visual problem —
and it satisfies FR-006 ("legible in light and dark") and FR-030 ("normalised in
height") in one rule. It does mean the strip is monochrome by design; that is
consistent with the design doc's "one quiet row".

---

## Part 3 — Reference: verified Devicon and Lucide URLs

Kept in case Option A is chosen or a specific brand mark is wanted later.
All were fetched and confirmed `200 image/svg+xml`.

**Devicon** — `https://raw.githubusercontent.com/devicons/devicon/v2.17.0/icons/<name>/<name>-<variant>.svg`
(pin the tag; `master` also resolves). Licence MIT; the marks remain their
owners' trademarks.

Verified: `java-original`, `kotlin-original`, `spring-original`,
`kubernetes-plain`, `jenkins-original`, `react-original`,
`apachekafka-original`, `mongodb-original`, `elasticsearch-original`,
`docker-original`, `typescript-original`, `javascript-original`,
`python-original`, `git-original`, `terraform-original`,
`postgresql-original`, `redis-original`, `nginx-original`, `graphql-plain`,
`grafana-original`.

Variant gotchas: **graphql has no `-original`** (only `plain`), **nginx has only
`-original`**, and `spring`/`react`/`apachekafka` have **no `plain`**. Not in
Devicon at all: `keycloak`, `auth0`, `openai`, `anthropic`, `cucumberio`.

**Lucide** — `https://raw.githubusercontent.com/lucide-icons/lucide/1.27.0/icons/<name>.svg`.
Licence **ISC** (not MIT — MIT covers only the pre-fork Feather heritage).
No in-product attribution required.

**Not in Simple Icons** (404): `java`, `openai`, `playwright`,
`amazonwebservices`, `amazonaws`, `oracle`.

---

## Approval checklist for T075

- [ ] Skill icon set: **Option A (Simple Icons)** or **Option B (Lucide, recommended)**
- [ ] If Option A: replacement for `anthropic` on "Artificial Intelligence"
- [ ] Icon theming approach: **bake one uniform tone (recommended)** / CSS filter / `mask-image`
- [ ] Employer logos: accept the 4 clean upgrades
- [ ] Universal Music Publishing: accept the 2026 mark, or keep the existing asset
- [ ] Global: crop to the `g` symbol, or accept a portrait lockup in the row
- [ ] Y-Tree: approve editing the SVG's text `fill`
- [ ] Logo strip monochrome-per-theme treatment: approved?
- [ ] Confirm the three no-source employers keep their current assets

Once ticked, record the decisions in this file and proceed to T076.
