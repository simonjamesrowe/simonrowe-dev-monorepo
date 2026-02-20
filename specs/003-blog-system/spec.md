# Feature Specification: Blog System

**Feature Branch**: `003-blog-system`
**Created**: 2026-02-21
**Status**: Draft

## Clarifications

### Session 2026-02-21

- Q: What format is blog content stored/authored in? → A: Markdown — stored as Markdown, rendered to HTML for display (matches existing data from backup)
**Input**: User description: "Blog system for simonrowe.dev personal website including blog listing, search, detail pages, and homepage preview"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View Blog Article Listing (Priority: P1)

A visitor arrives at the blog section to discover and browse published articles. They see a visually appealing grid of blog posts with featured images, titles, brief descriptions, publication dates, and topic tags. Each post is presented as a clickable card that navigates to the full article. The layout uses mixed card formats (some vertical, some horizontal) to create visual interest and hierarchy.

**Why this priority**: This is the primary entry point for blog content discovery. Without this, users cannot access any blog content. It represents the MVP for the blog feature.

**Independent Test**: Can be fully tested by navigating to the blog section and verifying that all published articles are displayed with complete metadata and are clickable, delivering immediate value for content discovery.

**Acceptance Scenarios**:

1. **Given** there are 18 published blog posts in the system, **When** a visitor navigates to the blog listing page, **Then** all 18 posts are displayed in a mixed-layout grid with featured images, titles, short descriptions, publication dates, and tags
2. **Given** a visitor is viewing the blog listing page, **When** they click on any blog post card, **Then** they are navigated to the full article detail page
3. **Given** some blog posts are marked as unpublished, **When** a visitor views the blog listing, **Then** only published posts are visible
4. **Given** blog posts have varying numbers of tags (including posts with no tags), **When** displayed in the listing, **Then** all tags are shown for each post without layout breaking
5. **Given** the blog listing page is loading, **When** measured, **Then** the page becomes interactive within 2 seconds

---

### User Story 2 - Read Full Blog Article (Priority: P2)

A visitor clicks on a blog post to read the full content. They see a professional article page with the featured image, article title, author name, publication date, and topic tags at the top. The article content is displayed with rich formatting including headings, paragraphs, lists, blockquotes, inline code, and multi-line code blocks with syntax highlighting. Links within articles open appropriately (external links in new tabs, internal links in same tab). The reading experience is clean and focused.

**Why this priority**: Reading the full article is the core value delivery of the blog. Without this, the listing page serves no purpose. This is P2 because it requires P1 (listing) to be accessible first.

**Independent Test**: Can be tested by directly navigating to a blog post URL and verifying that all content renders correctly with proper formatting, code highlighting, and interactive elements.

**Acceptance Scenarios**:

1. **Given** a visitor clicks on a blog post from the listing, **When** the detail page loads, **Then** they see the featured image, title, author, publication date, and tags prominently displayed
2. **Given** a blog article contains formatted content, **When** rendered, **Then** headings, paragraphs, lists, blockquotes, inline code, and code blocks all display with appropriate formatting
3. **Given** a blog article contains code snippets in various languages, **When** rendered, **Then** each code block displays with language-appropriate syntax highlighting
4. **Given** a blog article contains hyperlinks, **When** a visitor clicks an external link, **Then** it opens in a new browser tab
5. **Given** a blog article contains hyperlinks, **When** a visitor clicks an internal link, **Then** it opens in the same browser tab
6. **Given** blog posts are associated with skill topics, **When** viewing a post, **Then** related skills are displayed and accessible

---

### User Story 3 - Search Blog Content (Priority: P3)

A visitor wants to find specific blog content without scrolling through all posts. They type keywords into a search field and see matching results appear immediately in a dropdown as they type. Results show post thumbnails, titles, and publication dates. They can click any result to navigate directly to that article. The search examines article titles, descriptions, and full content to find matches.

**Why this priority**: Search enhances discoverability but is not required for basic blog functionality. Users can still browse all posts via P1. This improves user experience for returning visitors or those with specific interests.

**Independent Test**: Can be tested by entering various search terms and verifying that matching results appear within 500ms, deliver relevant articles, and navigate correctly when clicked.

**Acceptance Scenarios**:

1. **Given** a visitor is on the blog section, **When** they type keywords into the search field, **Then** matching blog posts appear in a dropdown within 500 milliseconds
2. **Given** search results are displayed, **When** viewed, **Then** each result shows a thumbnail image, title, and publication date
3. **Given** a search query matches article titles, **When** executed, **Then** those articles appear in the results
4. **Given** a search query matches article descriptions, **When** executed, **Then** those articles appear in the results
5. **Given** a search query matches words within article content, **When** executed, **Then** those articles appear in the results
6. **Given** search results are displayed, **When** a visitor clicks on a result, **Then** they navigate to that article's detail page and the search dropdown closes

---

### User Story 4 - Preview Latest Articles on Homepage (Priority: P4)

A visitor lands on the homepage and immediately sees a preview section showcasing the 3 most recently published blog articles. Each preview shows the featured image, title, and short description. Clicking on any preview navigates to the full article. This section includes a link to view all blog posts.

**Why this priority**: This enhances the homepage by promoting blog content, but all blog functionality works independently through the dedicated blog section. This is a content marketing feature that drives engagement.

**Independent Test**: Can be tested by visiting the homepage and verifying that the 3 most recent published articles appear with correct metadata and navigation links.

**Acceptance Scenarios**:

1. **Given** there are published blog posts, **When** a visitor views the homepage, **Then** the 3 most recently published articles are displayed in a preview section
2. **Given** the homepage blog preview is displayed, **When** viewed, **Then** each preview shows the featured image, title, and short description
3. **Given** the homepage blog preview is displayed, **When** a visitor clicks on a preview, **Then** they navigate to that article's detail page
4. **Given** the homepage blog preview is displayed, **When** viewed, **Then** a "View All Posts" or similar link is visible that navigates to the main blog listing page

---

### Edge Cases

- What happens when a blog post has no featured image?
- What happens when a blog post has no tags?
- What happens when search returns no matching results?
- What happens when there are fewer than 3 published blog posts for the homepage preview?
- What happens when a blog post contains malformed content formatting?
- What happens when a blog post title or description exceeds typical length?
- What happens when a visitor navigates to a blog post URL that doesn't exist?
- What happens when a visitor navigates to an unpublished blog post URL directly?
- What happens when special characters or emoji appear in blog content?
- What happens when code blocks don't specify a programming language?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST display all published blog posts in a listing view with featured images, titles, short descriptions, publication dates, and topic tags
- **FR-002**: System MUST present blog posts in a mixed-layout grid alternating between vertical and horizontal card orientations
- **FR-003**: System MUST exclude unpublished blog posts from all public-facing views (listing, search, homepage preview)
- **FR-004**: System MUST render blog article content stored as Markdown into HTML with support for headings, paragraphs, lists, blockquotes, inline code, and multi-line code blocks
- **FR-005**: System MUST apply syntax highlighting to code blocks in blog articles
- **FR-006**: System MUST display article metadata (title, author, publication date, tags, featured image) on the article detail page
- **FR-007**: System MUST open external links in blog content in new browser tabs
- **FR-008**: System MUST open internal links in blog content in the same browser tab
- **FR-009**: System MUST provide a search capability that matches query terms against article titles, descriptions, and full content
- **FR-010**: System MUST display search results in a typeahead dropdown as the user types
- **FR-011**: System MUST show search results with thumbnail images, titles, and publication dates
- **FR-012**: System MUST display the 3 most recently published blog posts on the homepage
- **FR-013**: System MUST associate blog posts with topic tags for categorization
- **FR-014**: System MUST associate blog posts with related skills for cross-referencing
- **FR-015**: System MUST support all 18 existing blog posts from the data backup
- **FR-016**: System MUST support all 26 existing tags from the data backup
- **FR-017**: System MUST format publication dates in a human-readable format (e.g., "January 15, 2025")
- **FR-018**: System MUST handle blog posts with zero tags without display errors
- **FR-019**: System MUST handle blog posts without featured images with a fallback presentation
- **FR-020**: System MUST navigate to article detail pages when users click on blog cards or search results

### Key Entities

- **Blog Post**: Represents a published article with a title, short description (used in previews and search results), full content stored as Markdown (supporting headings, lists, code blocks, links, and other formatting when rendered to HTML), publication status flag (published/unpublished), featured image, creation timestamp, associated topic tags for categorization, and associated skills for cross-referencing with other site content
- **Tag**: Represents a categorization label that can be attached to multiple blog posts, with a unique name identifier

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Blog listing page becomes interactive within 2 seconds of navigation
- **SC-002**: Search results appear in the typeahead dropdown within 500 milliseconds of user input
- **SC-003**: All 18 existing blog posts are accessible and render correctly with complete content and metadata
- **SC-004**: Code blocks in blog articles display with appropriate syntax highlighting for all supported programming languages
- **SC-005**: 100% of blog posts with tags display those tags without layout breaking or truncation
- **SC-006**: External links in blog content open in new tabs 100% of the time
- **SC-007**: Homepage blog preview section displays the correct 3 most recent published posts
- **SC-008**: Search functionality returns results that match queries against titles, descriptions, and content with 100% accuracy
- **SC-009**: Unpublished blog posts are never visible to visitors in any view (0% leak rate)
- **SC-010**: Blog article pages render all content formatting (headings, lists, blockquotes, code) correctly 100% of the time
