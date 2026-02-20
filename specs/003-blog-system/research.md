# Research: Blog System

**Feature**: 003-blog-system | **Date**: 2026-02-21

## 1. Markdown Rendering in React

### Approach: react-markdown + rehype/remark plugins

The existing reference application (`react-ui`) already uses `react-markdown` with `rehype-raw` for HTML passthrough. This is the established pattern and should be carried forward.

**Library chain**:
- `react-markdown` -- core Markdown-to-React component
- `rehype-raw` -- allows raw HTML embedded in Markdown to pass through (required because some existing blog content contains inline HTML)
- `remark-gfm` -- GitHub Flavored Markdown support (tables, strikethrough, task lists, autolinks)

**Key configuration from reference app** (`BlogDetail.tsx`):
```tsx
<ReactMarkdown
  rehypePlugins={[rehypeRaw]}
  components={{ code: CodeBlock, a: ExternalLink }}
>
  {blog.content}
</ReactMarkdown>
```

**Decision**: Continue using `react-markdown` + `rehype-raw`. Add `remark-gfm` for table support that may exist in blog content. The `components` prop allows custom renderers for code blocks and links without wrapper complexity.

**Alternatives considered**:
- `marked` + `DOMPurify`: Lower-level, requires manual sanitization, no React component model. Rejected for added complexity.
- `mdx-js/mdx`: Overkill for rendering stored Markdown. MDX is for authoring, not display. Rejected per YAGNI principle.

---

## 2. Syntax Highlighting for Code Blocks

### Approach: react-syntax-highlighter with Prism.js

The reference app uses `react-syntax-highlighter` with the Prism engine and the `coy` theme. This provides language-aware highlighting for code blocks.

**Reference implementation** (`CodeBlock.tsx`):
```tsx
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { coy } from "react-syntax-highlighter/dist/cjs/styles/prism";
```

**How it works**:
1. `react-markdown` detects fenced code blocks (` ```language `) and passes them to the custom `code` component
2. The `CodeBlock` component extracts the language from the `className` prop (format: `language-xxx`)
3. For multi-line code blocks with a language, it renders `SyntaxHighlighter`
4. For inline code or blocks without a language, it falls back to a plain `<code>` element

**Language support**: Prism.js supports 300+ languages out of the box. The existing blog content includes Java, JavaScript, TypeScript, YAML, Bash, Kotlin, Groovy, JSON, and XML code blocks.

**Bundle size consideration**: Using the `cjs` import path (as in the reference app) includes only the languages detected at build time. For a personal blog with known content, this is acceptable. If bundle size becomes a concern, individual language imports can be used.

**Decision**: Carry forward `react-syntax-highlighter` with Prism.js and `coy` theme. No changes needed from the reference implementation.

**Alternatives considered**:
- `highlight.js` via `react-highlight`: Slightly smaller bundle but fewer themes and less React integration. Rejected because Prism is already proven in the reference app.
- `shiki`: High-quality VS Code-style highlighting but heavier (loads TextMate grammars). Rejected per simplicity principle; Prism is sufficient for a personal blog.

---

## 3. Blog Search with Elasticsearch

### Approach: Spring Data Elasticsearch + dedicated search endpoint

The constitution mandates Elasticsearch for search functionality. Blog search requires matching against titles, short descriptions, and full Markdown content. The site-wide search (Spec 005) will extend this pattern to include jobs and skills.

**Architecture**:
1. **Indexing**: When blog data is loaded/updated, a Kafka message triggers Elasticsearch index updates (per Constitution Principle II requiring Kafka for async messaging)
2. **Search document**: A denormalized `BlogSearchDocument` stored in Elasticsearch containing: id, title, shortDescription, content (plain text extracted from Markdown), tags, skills, thumbnailImage, createdDate
3. **Query**: Multi-match query across title (boosted), shortDescription, and content fields
4. **API**: `GET /api/search/blogs?q={query}` returns `BlogSearchResult[]` with id, title, thumbnailImage, createdDate

**Elasticsearch index mapping**:
```json
{
  "mappings": {
    "properties": {
      "title": { "type": "text", "boost": 3 },
      "shortDescription": { "type": "text", "boost": 2 },
      "content": { "type": "text" },
      "tags": { "type": "keyword" },
      "skills": { "type": "keyword" },
      "createdDate": { "type": "date" },
      "thumbnailImage": { "type": "keyword", "index": false }
    }
  }
}
```

**Content extraction**: Strip Markdown syntax from content before indexing to improve search relevance. A simple regex-based approach (removing `#`, `*`, `` ` ``, `[]()`, etc.) is sufficient given the controlled content set.

**Decision**: Use Spring Data Elasticsearch with a custom multi-match query. Index blog content as plain text (Markdown stripped). Keep search logic in `BlogSearchService` separate from `BlogService` to maintain single responsibility.

**Note on Spec 005 overlap**: The blog search API defined here (`GET /api/search/blogs`) is also referenced in Spec 005 (Site-Wide Search, FR-005). This spec implements the blog-specific search endpoint. Spec 005 will add the site-wide endpoint that aggregates across blogs, jobs, and skills.

---

## 4. Mixed-Layout Grid CSS Approach

### Approach: CSS Grid with modular card variants

The reference app uses a repeating 6-item pattern for the blog listing grid:
- Index 0, 5: Vertical card (1/3 width)
- Index 1: Horizontal-image-right card (2/3 width), followed by horizontal-image-left
- Index 3: Horizontal-image-left card (2/3 width), followed by horizontal-image-right
- Index 2, 4: Rendered as part of the horizontal pair above (not standalone)

**Pattern visualization** (6-item cycle):
```
[Vertical 1/3] [Horizontal-Right + Horizontal-Left  2/3]
[Horizontal-Left + Horizontal-Right  2/3] [Vertical 1/3]
```

This creates visual rhythm without requiring complex CSS Grid area templates. The reference app achieves this with Bootstrap's 12-column grid:
- `col-lg-4` for vertical cards (4/12 = 1/3)
- `col-lg-8` for horizontal card pairs (8/12 = 2/3)

**Decision**: Replicate the reference app's layout logic using CSS Grid or a utility-first CSS framework (TBD based on frontend framework decisions in Spec 001). The key is the `i % 6` index-based variant selection in the rendering loop.

**Key implementation detail**: The horizontal cards come in pairs. Index 1 renders both index 1 (image-right) and index 2 (image-left). Index 3 renders both index 3 (image-left) and index 4 (image-right). Indices 2 and 4 are skipped in the main loop because they are rendered as part of their paired horizontal card.

---

## 5. Image Handling for Blog Cards

### Approach: Direct image URL from MongoDB + responsive variants

The existing data model stores image references as MongoDB ObjectId references to an `upload_file` collection (Strapi's media library pattern). In the new architecture, images will be stored as URLs rather than Strapi media objects.

**Image storage migration**:
- The Strapi backup contains `upload_file` documents with multiple format variants (thumbnail, small, medium, large)
- In the new system, images will be stored as URL strings in the Blog document
- A `featuredImageUrl` field will replace the Strapi `image` ObjectId reference
- Thumbnail variants for search results will be derived from the main image URL

**Fallback handling** (FR-019):
- Blog posts without a featured image will display a default placeholder image
- The placeholder should match the site's visual design (a generic blog post graphic or gradient)
- Frontend components must check for null/empty `featuredImageUrl` before rendering

**Responsive images**:
- Use CSS `object-fit: cover` with fixed aspect ratios for consistent card layouts
- Vertical cards: 16:9 aspect ratio for the image area
- Horizontal cards: Square or 4:3 aspect ratio, side-by-side with content

**Decision**: Store image URLs as simple strings in MongoDB. Handle responsive display with CSS rather than multiple stored variants. Use a CSS placeholder/gradient for posts without images. This is simpler than replicating Strapi's multi-format image system and aligns with the simplicity principle.

---

## 6. Date Formatting (FR-017)

### Approach: Native Intl.DateTimeFormat

The reference app uses `moment.js` for date formatting (`Moment(date).format("DD-MMM-YYYY")`). Moment.js is deprecated and adds significant bundle weight.

**Decision**: Use the browser-native `Intl.DateTimeFormat` API for human-readable date formatting:
```typescript
new Intl.DateTimeFormat('en-GB', {
  year: 'numeric',
  month: 'long',
  day: 'numeric'
}).format(new Date(dateString))
// Output: "15 January 2025"
```

This produces the format specified in FR-017 with zero additional dependencies.

**Backend**: Return dates as ISO 8601 strings (`2025-01-15T00:00:00Z`). The frontend handles display formatting.

---

## 7. External vs Internal Link Handling (FR-007, FR-008)

### Approach: Custom link component in react-markdown

The reference app already handles this with an `ExternalLink` component that sets `target="_blank"` and `rel="noreferrer"` on all links. However, FR-008 requires internal links to open in the same tab.

**Enhanced approach**:
```tsx
const SmartLink = ({ href, children, ...props }) => {
  const isExternal = href?.startsWith('http') && !href?.includes('simonrowe.dev');
  if (isExternal) {
    return <a href={href} target="_blank" rel="noopener noreferrer" {...props}>{children}</a>;
  }
  return <Link to={href} {...props}>{children}</Link>;
};
```

**Decision**: Extend the reference app's `ExternalLink` into a `SmartLink` component that detects external URLs (starting with `http` and not matching the site domain) and applies appropriate target behavior. Internal links use React Router's `Link` component for SPA navigation.

---

## 8. Typeahead Search Component

### Approach: Custom debounced search with dropdown

The reference app uses `react-bootstrap-typeahead` (AsyncTypeahead). This is a heavyweight dependency for a single use case.

**Options**:
1. **react-bootstrap-typeahead**: Proven in reference app, feature-rich, but large dependency and tightly coupled to Bootstrap
2. **Custom implementation**: Debounced input + dropdown with fetch. More control, smaller bundle, but more code to maintain
3. **Headless UI library** (e.g., Downshift, Radix Combobox): Accessible, unstyled, composable

**Decision**: Use a custom debounced search implementation. The typeahead requirements are straightforward (input, debounce, fetch, render dropdown, navigate on click). A custom component with a 300ms debounce avoids the `react-bootstrap-typeahead` dependency and gives full control over styling and behavior. Use `AbortController` to cancel in-flight requests when the query changes.

**Key behaviors**:
- Debounce: 300ms after last keystroke before firing search
- Minimum query length: 2 characters (matching reference app)
- Cancel previous request on new input
- Close dropdown on result click or click outside
- Keyboard navigation (arrow keys + Enter) for accessibility

---

## Summary of Technology Decisions

| Concern | Decision | Rationale |
|---------|----------|-----------|
| Markdown rendering | react-markdown + rehype-raw + remark-gfm | Proven in reference app, React-native component model |
| Syntax highlighting | react-syntax-highlighter (Prism, coy theme) | Proven in reference app, 300+ language support |
| Search backend | Spring Data Elasticsearch, multi-match query | Constitution mandates Elasticsearch |
| Search indexing | Kafka event-driven + periodic sync | Constitution mandates Kafka for async messaging |
| Grid layout | Index-based 6-item cycle with card variants | Replicates reference app's visual pattern |
| Image handling | URL strings in MongoDB, CSS responsive display | Simpler than Strapi's multi-format system |
| Date formatting | Intl.DateTimeFormat (native browser API) | Zero dependencies, replaces deprecated moment.js |
| Link handling | Custom SmartLink detecting internal/external | Satisfies FR-007 and FR-008 |
| Typeahead search | Custom debounced input + dropdown | Lightweight alternative to react-bootstrap-typeahead |
