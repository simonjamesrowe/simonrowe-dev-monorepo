# Research: Skills & Employment

**Feature**: 004-skills-employment | **Date**: 2026-02-21

## Decision 1: PDF Generation Library

### Context

The resume feature (P4) requires server-side PDF generation from dynamic data. The PDF must have a sidebar layout with contact info/skills and a main content area with employment history. It must render markdown-formatted job descriptions and support visual elements like star ratings.

### Options Evaluated

| Library | License | Maturity | Spring Boot Compat | Layout Control | Markdown Support |
|---------|---------|----------|--------------------:|----------------|------------------|
| **OpenPDF** | LGPL/MPL | Fork of iText 4, actively maintained | Excellent (pure Java) | Full programmatic control via `PdfPTable`, `PdfPCell` | Manual: parse markdown to styled `Paragraph`/`Chunk` objects |
| **iText 7+ (Core)** | AGPL | Industry standard | Excellent | Full programmatic + `ColumnDocumentRenderer` for sidebar | Built-in pdfHTML add-on (commercial license) |
| **Apache PDFBox** | Apache 2.0 | Mature, lower-level | Excellent | Low-level page drawing; no table abstraction | Manual: full custom rendering |
| **Flying Saucer (OpenHTMLtoPDF)** | LGPL | Maintained fork | Good | HTML/CSS driven layout with `float` support | Convert markdown to HTML first, then render |
| **JasperReports** | LGPL | Mature, heavyweight | Good but heavy | Template-based (JRXML) | Via Groovy expressions |

### Decision: OpenPDF

**Rationale**:
- **License**: LGPL/MPL is permissive for a personal website; no AGPL concerns unlike iText 7+.
- **Simplicity**: Programmatic API (`PdfPTable` with column widths) directly maps to the sidebar + main content layout without needing HTML/CSS templates or JRXML.
- **Dependency weight**: Lightweight single dependency vs. JasperReports (dozens of transitive deps) or iText 7 (multiple modules).
- **YAGNI**: No need for HTML-to-PDF pipeline (Flying Saucer) when the layout is a fixed two-column structure with known content types.
- **Markdown handling**: Job descriptions are simple markdown (bold, italic, lists, links). A lightweight markdown-to-PDF converter using `Paragraph` and `Chunk` objects is sufficient. The `commonmark-java` library can parse markdown AST, which maps directly to OpenPDF styled text.

**Trade-offs accepted**:
- More manual layout code than HTML/CSS approaches -- acceptable given the single fixed layout.
- No WYSIWYG template editing -- acceptable since the resume format is stable and changes infrequently.

### Implementation Approach

```
OpenPDF dependency: com.github.librepdf:openpdf:2.0.3
Markdown parsing:   org.commonmark:commonmark:0.24.0

Layout strategy:
  - PdfPTable with 2 columns (30%/70% split)
  - Left column: contact info, links, skill categories with star ratings
  - Right column: professional summary, employment history, education
  - Font: Helvetica (built-in, no external font files needed)
  - Star ratings: Unicode star characters (filled/empty) in monospaced font
```

---

## Decision 2: Timeline Layout CSS Approach

### Context

The employment timeline (P2) requires entries alternating between left and right sides, with date information and job headline swapping position based on side. The existing reference UI uses a grid-based approach with CSS classes `ex_leftsidebox` and `ex_rightsidebox`.

### Options Evaluated

| Approach | Complexity | Responsive | Maintainability |
|----------|------------|------------|-----------------|
| **CSS Grid with alternating classes** | Low | Good with media queries | Simple pattern: even/odd indices |
| **CSS Flexbox with `order` property** | Medium | Good | Requires order manipulation per breakpoint |
| **Dedicated timeline CSS library** | Low (external dep) | Depends on lib | Adds dependency for simple layout |
| **Pure CSS `::before`/`::after` pseudo-elements** | Medium-High | Fragile | Hard to maintain connector lines |

### Decision: CSS Grid with alternating classes

**Rationale**:
- The existing reference UI already uses this pattern successfully with `ex_leftsidebox` / `ex_rightsidebox` class toggling based on index.
- The alternation pattern is deterministic: indices `[0,1,4,5,8,9,12,13]` are left-side, `[2,3,6,7,10,11,14,15]` are right-side (pairs of two alternate).
- On mobile viewports, the grid collapses to a single column -- no alternation needed.
- No external library dependency needed for this straightforward layout.

### Implementation Approach

```
Pattern: 2-column grid at desktop, 1-column at mobile
Alternation: Every pair of 2 entries swaps side
  - Indices 0,1 -> left (dates first, then headline)
  - Indices 2,3 -> right (headline first, then dates)
  - Indices 4,5 -> left ... (repeating)

Mobile breakpoint: Below 768px, single column, dates always above headline
```

---

## Decision 3: Skill Rating Color Coding

### Context

Skill proficiency ratings (0-10 scale) must display with color-coded visual indicators: green for 9+, blue for 8.5-8.9, orange for below 8.5 (FR-004). The existing reference UI uses Bootstrap's `ProgressBar` with variant mappings (`success`, `info`, `warning`).

### Options Evaluated

| Approach | Visual Clarity | Accessibility | Implementation |
|----------|---------------|---------------|----------------|
| **Bootstrap ProgressBar with variants** | High (filled bar with color) | Good (color + width convey meaning) | Minimal -- matches reference UI |
| **Custom SVG rating bars** | High | Requires ARIA labels | More code, more flexibility |
| **Star ratings (filled/empty)** | Medium | Good (count-based) | Better for PDF, less suitable for web grid |
| **Numeric badge with background color** | Medium | Good (number is primary) | Simpler but less visual impact |

### Decision: Progress bar with color-coded fill

**Rationale**:
- Matches the existing reference UI pattern, ensuring design continuity.
- Bootstrap's `ProgressBar` component (or equivalent) provides accessible progress semantics out of the box.
- The three-tier color system (green/blue/orange) is simple to implement as a pure function of the rating value.
- For the PDF resume, the same color thresholds apply but use star characters (filled/empty out of 10) since progress bars do not translate to PDF.

### Implementation Approach

```typescript
// Frontend: rating -> color mapping
function getRatingColor(rating: number): 'green' | 'blue' | 'orange' {
  if (rating >= 9) return 'green';     // CSS: #28a745 or var(--bs-success)
  if (rating >= 8.5) return 'blue';    // CSS: #17a2b8 or var(--bs-info)
  return 'orange';                      // CSS: #ffc107 or var(--bs-warning)
}

// Progress bar width: rating * 10 (e.g., 9.2 -> 92%)
// Edge case: missing/invalid rating -> default to 0, orange color
```

```java
// Backend PDF: rating -> star representation
// 9.2 -> 9 filled stars + 1 empty star (round down for star count)
// Color applied to star characters using same thresholds
```

---

## Decision 4: Bidirectional Navigation Pattern

### Context

Users need to navigate from skill details to job details and vice versa (FR-016). The existing reference UI uses drawer-based navigation (Material UI `SwipeableDrawer`) where clicking a skill group opens a right-side drawer, and job cards within that drawer link to job details.

### Options Evaluated

| Pattern | UX Quality | Implementation | Back Navigation |
|---------|-----------|----------------|-----------------|
| **Drawer stack (drawer opens drawer)** | Good -- layered context | Medium -- manage drawer state stack | Close top drawer returns to previous |
| **Route-based navigation (separate pages)** | Standard | Low -- React Router handles state | Browser back button works naturally |
| **Modal within drawer** | Confusing -- nested overlays | High -- z-index management | Unclear which close button does what |
| **Inline expansion (accordion within drawer)** | Limited -- cramped content | Low | Collapse returns to list |

### Decision: Route-based navigation with drawer presentation

**Rationale**:
- The homepage is a single-page layout with inline sections (per Spec 002). Skills and employment are sections within this page.
- Opening a skill group or job detail uses a right-side drawer (consistent with existing reference UI), but the URL updates to reflect the current view (e.g., `/skills-groups/{id}`, `/jobs/{id}`).
- Bidirectional navigation replaces the current drawer content rather than stacking drawers. Clicking a job card from within a skill group drawer closes the skill drawer and opens the job drawer.
- Browser back button and URL sharing work naturally because state is encoded in the route.
- This matches the reference UI behavior where `navigate('/skills-groups/${id}')` and `navigate('/jobs/${id}')` drive drawer open/close state.

### Implementation Approach

```
URL patterns:
  /                          -> Homepage (all sections)
  /skills-groups/{groupId}   -> Homepage + skill group drawer open
  /skills-groups/{groupId}#{skillId} -> Homepage + skill group drawer scrolled to skill
  /jobs/{jobId}              -> Homepage + job detail drawer open

Navigation flow:
  1. Skill grid card click -> navigate to /skills-groups/{id} -> drawer opens
  2. Job card within skill drawer click -> navigate to /jobs/{id} -> skill drawer closes, job drawer opens
  3. Skill card within job Skills tab click -> navigate to /skills-groups/{groupId}#{skillId} -> job drawer closes, skill drawer opens
  4. Drawer close -> navigate to / -> drawer closes

State management:
  - URL params drive drawer visibility (useParams hook)
  - No separate state store needed -- URL is the single source of truth
```

---

## Decision 5: MongoDB Document Structure -- Embedded vs. Referenced Skills

### Context

The Strapi backup shows `skills_groups` referencing `skills` by ObjectId array, and `jobs` referencing `skills` by ObjectId array. The new system needs to decide whether to maintain separate collections or embed skills within their groups.

### Options Evaluated

| Structure | Query Simplicity | Data Integrity | Migration Effort |
|-----------|-----------------|----------------|------------------|
| **Embed skills in skill_groups** | Single query returns group + skills | Duplication if skill appears in multiple groups | Moderate -- denormalize on migration |
| **Separate collections with references** | Requires joins/lookups | Single source of truth per skill | Low -- mirrors Strapi structure |
| **Embed skills in both groups and jobs** | No joins needed | High duplication, update anomalies | High -- full denormalization |

### Decision: Embed skills within skill_groups, reference skills by ID in jobs

**Rationale**:
- Each skill belongs to exactly one skill group (1:N relationship). Embedding skills within their group document eliminates a join for the primary use case (displaying a skill group with all its skills).
- Jobs reference skills by ID. The API layer resolves these references when returning job details with skill information, using the embedded skill data from skill groups.
- This produces 2 collections (`skill_groups` and `jobs`) instead of 3, reducing query complexity for the most common operations.
- The `skill_groups` collection contains 9 documents, each with 3-15 embedded skills. Total document sizes remain well within MongoDB's 16MB limit.
- Bidirectional correlation is resolved at the API layer: given a skill ID, the service can query `jobs` where `skills` array contains that ID. Given a job, the embedded skill data is already available in the `skill_groups` documents.

### Implementation Approach

```
Collection: skill_groups (9 documents)
  - Embeds skills[] array with full skill data
  - Each skill has a unique ID for cross-referencing

Collection: jobs (9 documents)
  - Contains skills[] as array of skill IDs (references)
  - API layer enriches job responses with full skill data from skill_groups

Query patterns:
  GET /api/skills       -> db.skill_groups.find().sort({displayOrder: 1})
  GET /api/skills/{id}  -> db.skill_groups.findById(id) + db.jobs.find({skills: {$in: [skillIds]}})
  GET /api/jobs         -> db.jobs.find().sort({startDate: -1})
  GET /api/jobs/{id}    -> db.jobs.findById(id) + resolve skill IDs from skill_groups
  GET /api/resume       -> db.jobs.find({includeOnResume: true}) + db.skill_groups.find() + profile data
```
