# Feature Specification: Blog Post Series - Rebuilding simonrowe.dev with AI

**Feature Branch**: `010-blog-posts`
**Created**: 2026-02-27
**Status**: Draft
**Input**: User description: "Create a series of 4-5 blog posts documenting the rebuild of simonrowe.dev using Claude Code, Conductor, and SpecKit, derived from the project's commit history"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read the Complete Blog Series (Priority: P1)

A site visitor discovers the blog series about rebuilding simonrowe.dev with AI tools. They can browse all posts in the series, read them in chronological order, and understand the full journey from initial setup through to a fully functional personal website built primarily by AI coding agents.

**Why this priority**: The core purpose of this feature is to publish readable, engaging blog content. Without the posts themselves, nothing else matters.

**Independent Test**: Navigate to the blog listing page, find the rebuild series posts, read each one in order, and verify the narrative flows logically from start to finish with no broken content or missing context.

**Acceptance Scenarios**:

1. **Given** a visitor is on the blog listing page, **When** they browse available posts, **Then** they see 5 blog posts related to the website rebuild series, clearly identifiable as a series
2. **Given** a visitor opens any blog post in the series, **When** they read the content, **Then** the post renders correctly with formatted markdown, code snippets, and any referenced images
3. **Given** a visitor finishes reading one post, **When** they want to continue the series, **Then** they can easily navigate to the next post in sequence
4. **Given** a visitor lands on any individual post via search or direct link, **When** they read it, **Then** the post provides enough context to be understood standalone while also indicating it is part of a larger series

---

### User Story 2 - Discover the Series from the Homepage (Priority: P2)

A visitor arriving at the homepage sees the latest blog posts preview, which includes posts from this rebuild series. They can click through to read the full posts and discover the rest of the series.

**Why this priority**: Discoverability via the homepage is the primary way visitors will find these posts, but the posts themselves (P1) must exist first.

**Independent Test**: Load the homepage, verify that at least one rebuild series post appears in the latest blogs section, and confirm clicking it navigates to the full post.

**Acceptance Scenarios**:

1. **Given** the blog posts are published, **When** a visitor loads the homepage, **Then** the latest blog preview section includes the most recent rebuild series post(s)
2. **Given** a visitor clicks a rebuild series blog post from the homepage, **When** the blog detail page loads, **Then** the full post content is displayed with proper formatting

---

### User Story 3 - Find Series Posts via Search (Priority: P3)

A visitor searching for topics like "Claude Code", "Conductor", "SpecKit", or "AI website rebuild" finds relevant posts from this series in search results.

**Why this priority**: Search discoverability is important for long-term value but secondary to having the content published and discoverable from the homepage.

**Independent Test**: Use the site search to search for "Claude Code" and verify that relevant blog posts from the series appear in results.

**Acceptance Scenarios**:

1. **Given** the blog posts are indexed, **When** a visitor searches for "Claude Code", **Then** relevant posts from the series appear in search results
2. **Given** the blog posts are indexed, **When** a visitor searches for "Conductor", **Then** posts mentioning Conductor appear in search results
3. **Given** a visitor clicks a search result, **When** the blog detail page loads, **Then** the full post renders correctly

---

### Edge Cases

- What happens if a post contains code blocks with special characters or very long lines? The markdown renderer must handle gracefully with horizontal scrolling
- What happens if a visitor accesses a draft/unpublished post directly? The system returns a 404 or "not found" response
- What happens if the blog series posts are loaded on a mobile device? Content must be readable and responsive with appropriate line wrapping

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST store and serve 5 blog posts as part of a "Rebuilding simonrowe.dev with AI" series
- **FR-002**: Each blog post MUST contain a title, short description, full markdown content body, tags, creation date, and published flag
- **FR-003**: Blog posts MUST render markdown content including headings, paragraphs, code blocks with syntax highlighting, links, lists, and inline formatting
- **FR-004**: Blog posts MUST be tagged with relevant tags for categorisation and discoverability
- **FR-005**: Blog posts MUST appear in the existing blog listing page sorted by creation date (newest first)
- **FR-006**: Blog posts MUST be indexed for site-wide search so visitors can find them by searching for key topics
- **FR-007**: Blog posts MUST be accessible via the existing blog detail page route with full markdown rendering
- **FR-008**: Each blog post MUST be readable as a standalone article while also fitting into the broader series narrative

### Blog Post Content Plan

The 5 blog posts, derived from the project's commit history (Feb 21-27, 2026), cover the following topics:

**Post 1: "From Zero to Specification: How I Used AI to Plan My Entire Website Rebuild"**
- Covers: Initial project setup (Feb 21), using SpecKit to create 9 feature specifications before writing any code
- Key themes: Spec-driven development, SpecKit's slash command workflows, why planning with AI beats "vibe coding", the value of upfront specification
- Tags: SpecKit, AI, Software Architecture, Spec-Driven Development
- References: PR #1 (Add complete project specifications), the specs/ directory structure

**Post 2: "Building the Foundation: Infrastructure and First Features in a Weekend"**
- Covers: Project infrastructure skeleton (Feb 23, PR #2), profile homepage (Feb 23, PR #3), blog system (Feb 24, PR #5)
- Key themes: Spring Boot + React monorepo setup, MongoDB data modelling, Conductor for parallel agent workspaces, how multiple features were built in 2 days
- Tags: Spring Boot, React, MongoDB, Conductor, Infrastructure, Claude Code
- References: PRs #2, #3, #5; 001-project-infrastructure, 002-profile-homepage, 003-blog-system specs

**Post 3: "Shipping Six Features in a Day: Parallel AI Agents with Conductor"**
- Covers: Skills & employment (PR #4), blog system (PR #5), site search (PR #7), contact form (PR #8) - all landed Feb 24
- Key themes: Conductor's parallel workspace model, how isolated git worktrees enable concurrent development, reviewing and merging AI-generated code, Elasticsearch integration for search
- Tags: Conductor, Claude Code, Elasticsearch, Parallel Development, AI Productivity
- References: PRs #4-#8; the explosion of features shipped on Feb 24

**Post 4: "Interactive Tours, Data Migration, and the Finishing Touches"**
- Covers: Interactive tour (PR #9), API/CORS configuration fix (PR #10), Strapi data migration, global job position with AI skills (PR #12)
- Key themes: Migrating from Strapi CMS to the new stack, solving real-world integration issues (CORS, base URLs), adding polish features like guided tours, updating CV/employment data with AI-specific skills
- Tags: Data Migration, Strapi, CORS, Interactive UI, Claude Code
- References: PRs #9-#12; 008, 009 specs; migration scripts

**Post 5: "Lessons Learned: What Worked, What Didn't, and What's Next"**
- Covers: Retrospective on the full rebuild experience, honest assessment of AI-assisted development
- Key themes: When AI coding agents excel vs struggle, the importance of specification-first approach, Conductor workflow tips, SpecKit best practices, what's planned next (content management, auth, AI chat)
- Tags: AI, Retrospective, Best Practices, SpecKit, Conductor, Claude Code
- References: 007-content-management spec (future), the overall project journey

### Key Entities

- **Blog Post**: Individual article with title, short description, markdown content body, tags, published status, and creation date. Each post belongs to the rebuild series and references specific commits/PRs from the project history.
- **Tag**: Categorisation label applied to posts. New tags needed: "SpecKit", "Conductor", "Claude Code", "Spec-Driven Development", "AI Productivity", "Parallel Development", "Data Migration", "Retrospective". Some existing tags may also apply (e.g., "Spring Boot", "React", "MongoDB", "Elasticsearch").

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All 5 blog posts are published and accessible from the blog listing page
- **SC-002**: Each blog post is at least 800 words and contains a mix of narrative text, code examples, and practical insights
- **SC-003**: Visitors can find any blog post in the series by searching for "Claude Code", "Conductor", or "SpecKit" using site search
- **SC-004**: Each blog post loads and renders correctly on both desktop and mobile viewports
- **SC-005**: The blog posts appear in the homepage latest posts preview section

## Assumptions

- The existing blog system (003-blog-system) is fully functional and supports creating new blog posts via data insertion
- The existing tag system supports adding new tags for categorisation
- Search indexing is operational and will index new blog posts for discoverability
- The markdown renderer can handle the content complexity needed (code blocks, links, lists, headings)
- Blog post content will be written in markdown format, consistent with existing blog posts
- The site is accessible at simonrowe.dev and the blog listing is at /blogs
- External links to Conductor (https://www.conductor.build/) and SpecKit (https://github.com/github/spec-kit) will be included as references within blog content
