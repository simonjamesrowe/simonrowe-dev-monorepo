# Feature Specification: Blog Post Series Phase 2 - Beyond the Rebuild

**Feature Branch**: `013-blog-posts-phase-2`
**Created**: 2026-04-03
**Status**: Draft
**Input**: User description: "Generate blog posts covering recent website changes (March-April 2026): CMS with Auth0, AI Chat with MCP tools, and Docker Compose deployment with observability"

## Clarifications

### Session 2026-04-03

- Q: What writing voice/tone should the blog posts use? → A: Hybrid — first-person narrative framing with tutorial-style code walkthrough sections
- Q: Should code snippets be simplified pseudocode or actual repo code? → A: Full working examples — longer code blocks from the actual repo that readers could copy-paste and adapt
- Q: Should all 14 proposed new tags be created or trimmed? → A: Trim to ~9 high-value, reusable tags — drop single-use niche tags (OAuth2, Google Gemini, WebSocket, Google Drive API, Backup)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read the Phase 2 Blog Series (Priority: P1)

A site visitor discovers the second series of blog posts documenting the continued evolution of simonrowe.dev. They can read 3 new articles covering the CMS build, AI chat integration, and production deployment — each standing alone while also connecting back to the original 5-part rebuild series.

**Why this priority**: The core deliverable is readable, engaging blog content. Without the posts, nothing else matters.

**Independent Test**: Navigate to the blog listing page, find the 3 new posts, read each one, and verify the content is coherent, technically accurate, and renders correctly with formatted markdown and code snippets.

**Acceptance Scenarios**:

1. **Given** a visitor is on the blog listing page, **When** they browse available posts, **Then** they see 3 new blog posts covering CMS, AI chat, and production deployment
2. **Given** a visitor opens any post in the series, **When** they read the content, **Then** the post renders correctly with formatted markdown, code blocks with syntax highlighting, and referenced images
3. **Given** a visitor has read the original rebuild series, **When** they read a Phase 2 post, **Then** the post references the earlier series where relevant while remaining self-contained
4. **Given** a visitor lands on any individual post via search or direct link, **When** they read it, **Then** the post provides enough context to be understood standalone

---

### User Story 2 - Discover Posts from the Homepage (Priority: P2)

A visitor arriving at the homepage sees the latest blog posts preview, which includes posts from this new series. They can click through to read the full posts.

**Why this priority**: Homepage discoverability is the primary traffic driver, but the posts themselves must exist first.

**Independent Test**: Load the homepage, verify that at least one Phase 2 post appears in the latest blogs section, and confirm clicking it navigates to the full post.

**Acceptance Scenarios**:

1. **Given** the blog posts are published, **When** a visitor loads the homepage, **Then** the latest blog preview section includes the most recent Phase 2 post(s)
2. **Given** a visitor clicks a Phase 2 blog post from the homepage, **When** the blog detail page loads, **Then** the full post content is displayed with proper formatting

---

### User Story 3 - Find Posts via Search (Priority: P3)

A visitor searching for topics like "Auth0", "Spring AI", "MCP", "Docker Compose", or "Grafana" finds relevant posts from this series in search results.

**Why this priority**: Search discoverability drives long-term organic traffic but is secondary to having the content published and visible on the homepage.

**Independent Test**: Use the site search to search for "MCP tools" and verify that relevant blog posts from the series appear in results.

**Acceptance Scenarios**:

1. **Given** the blog posts are indexed, **When** a visitor searches for "Auth0 CMS", **Then** the CMS blog post appears in search results
2. **Given** the blog posts are indexed, **When** a visitor searches for "Spring AI" or "MCP", **Then** the AI chat post appears in search results
3. **Given** the blog posts are indexed, **When** a visitor searches for "Docker Compose" or "Grafana", **Then** the deployment post appears in search results

---

### Edge Cases

- What happens if a post contains code blocks with special characters or very long lines? The markdown renderer must handle gracefully with horizontal scrolling
- What happens if a visitor accesses a draft/unpublished post directly? The system returns a 404 or "not found" response
- What happens if the blog series posts are loaded on a mobile device? Content must be readable and responsive with appropriate line wrapping
- What happens if a visitor follows a link to the Phase 1 rebuild series from a Phase 2 post? The linked post must exist and load correctly

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST store and serve 3 blog posts as part of a "Beyond the Rebuild" phase 2 series
- **FR-002**: Each blog post MUST contain a title, short description, full markdown content body (800+ words), tags, associated skills, creation date, featured image, and published flag. Posts MUST use a hybrid voice: first-person narrative framing ("I built this because...") with tutorial-style code walkthrough sections containing full working examples from the actual codebase that readers can copy-paste and adapt
- **FR-003**: Blog posts MUST render markdown content including headings, paragraphs, code blocks with syntax highlighting, links, lists, and inline formatting
- **FR-004**: Blog posts MUST be tagged with relevant existing and new tags for categorisation and discoverability
- **FR-005**: Blog posts MUST appear in the existing blog listing page sorted by creation date (newest first)
- **FR-006**: Blog posts MUST be indexed for site-wide search so visitors can find them by searching for key topics
- **FR-007**: Blog posts MUST be accessible via the existing blog detail page route with full markdown rendering
- **FR-008**: Each blog post MUST be readable as a standalone article while referencing the earlier rebuild series where appropriate
- **FR-009**: Each blog post MUST have a featured image consistent in style with the existing blog images
- **FR-010**: New tags MUST be created in the system for topics not already covered by existing tags

### Blog Post Content Plan

The 3 blog posts cover features built from March to April 2026, continuing the narrative from the original 5-part rebuild series.

**Post 6: "Building a CMS from Scratch: Auth0, MDXEditor, and a Media Library"**
- Covers: Content management system (Feature 007, merged Mar 17, PR #11)
- Key themes: Why build a custom CMS instead of using an off-the-shelf solution, Auth0 OAuth2 integration for admin authentication, MDXEditor for rich markdown editing, media library with automatic image variant generation (thumbnail/small/medium/large), two-column admin layout with Lucide React icons, managing blog posts/employment/skills/profile from a single admin interface
- Tags: Auth0, Content Management, React, Spring Boot, AI
- Skills: Java, Spring Boot, React, TypeScript, MongoDB
- Content outline:
  - The motivation: migrating from Strapi to a purpose-built CMS
  - Setting up Auth0 as the authentication gateway
  - Building the blog editor with MDXEditor and live preview
  - The media library: upload, automatic resizing, and variant generation
  - Managing employment records, skills, and profile from one admin panel
  - Lessons learned: when custom beats off-the-shelf
- References: PR #11, 007-content-management spec

**Post 7: "Adding AI Chat to My Portfolio: Spring AI, Gemini, and MCP Tools"**
- Covers: Profile chat feature (Feature 009, merged Mar 22, PR #13), chat model upgrade (PR #17)
- Key themes: Embedding an AI chatbot into a personal portfolio site, using Spring AI with Google Gemini for natural language conversations, real-time streaming via WebSocket/STOMP, exposing portfolio data through Model Context Protocol (MCP) tool endpoints, rate limiting with Bucket4j, chat memory management
- Tags: Spring AI, MCP, AI, Chatbot
- Skills: Java, Spring Boot, Spring AI, React, TypeScript
- Content outline:
  - Why add a chatbot to a portfolio? Making the site interactive and conversational
  - Integrating Spring AI with Google Gemini: configuration and prompt engineering
  - Streaming responses in real-time: WebSocket/STOMP architecture
  - MCP tools: letting the AI query your own profile, blogs, jobs, and skills
  - Rate limiting: protecting against abuse with Bucket4j
  - The upgrade: switching models and fixing streaming edge cases
- References: PRs #13, #17; 009-profile-chat spec

**Post 8: "Production-Ready: Docker Compose, Backups, and Observability"**
- Covers: Admin data operations (Feature 011, merged Apr 2, PR #16), Docker Compose deployment (Feature 012, merged Apr 3, PR #18)
- Key themes: Building admin data operations (Google Drive backup/restore, search index rebuild), containerising the full stack with Docker Compose, nginx reverse proxy with domain-based routing, Pinggy tunnelling for public exposure, Grafana Cloud observability with Alloy (Loki logs + Tempo traces), managing stateful services (MongoDB, Kafka, Elasticsearch) with named volumes
- Tags: Docker, Nginx, Grafana, Observability, DevOps
- Skills: Docker, Nginx, Grafana, MongoDB, Kafka, Elasticsearch
- Content outline:
  - Data operations: one-click backup to Google Drive, restore with safety nets
  - Containerising everything: Docker Compose for the full stack
  - Nginx reverse proxy: routing simonrowe.dev and api.simonrowe.dev
  - Going public: Pinggy tunnelling with wildcard domains
  - Observability: Grafana Alloy shipping logs and traces to Grafana Cloud
  - Stateful services: managing MongoDB, Kafka, and Elasticsearch volumes
  - What production-ready really means for a personal project
- References: PRs #16, #18; 011-admin-data-ops spec, 012-docker-compose-deploy spec

### New Tags to Create

The following 9 reusable tags do not exist in the current system and must be created (single-use niche tags like OAuth2, Google Gemini, WebSocket, Google Drive API, and Backup were excluded to keep the tag system clean):
- Auth0
- Content Management
- Spring AI
- MCP (Model Context Protocol)
- Chatbot
- Nginx
- Grafana
- Observability
- DevOps

### Featured Image Generation Prompts

Each blog post needs a featured image generated via ChatGPT/DALL-E. The style must match the existing blog series images: **clean white background, flat/semi-flat tech illustrations in navy blue (#1a365d) and teal/blue accents, with the blog title and subtitle as overlaid text on the right side. Minimalist, modern, professional style. No photorealism.**

**Post 6 Image Prompt:**
> Create a clean, minimalist tech illustration on a white background. On the left side, show a flat-style illustration of a content management dashboard with panels showing a text editor, an image gallery grid, and a login/lock icon representing authentication. Use a navy blue (#1a365d) and teal color palette. The illustration should feel modern and professional, similar to a SaaS product illustration. Semi-flat style with subtle shadows. On the right side of the image, leave space for text overlay. Landscape orientation, 1200x630px.

**Post 7 Image Prompt:**
> Create a clean, minimalist tech illustration on a white background. On the left side, show a flat-style illustration of a chat conversation interface with speech bubbles, connected to abstract icons representing tools/APIs (a magnifying glass for search, a person silhouette for profile, a document for blogs). Include subtle AI/neural network connection lines between the chat and the tool icons. Use a navy blue (#1a365d) and teal color palette. Semi-flat style with subtle shadows. On the right side of the image, leave space for text overlay. Landscape orientation, 1200x630px.

**Post 8 Image Prompt:**
> Create a clean, minimalist tech illustration on a white background. On the left side, show a flat-style illustration of containerised services: Docker container boxes stacked/connected with arrows, a reverse proxy/gateway icon in front, and small monitoring dashboard widgets showing log lines and trace graphs. Include a cloud backup icon (cloud with an upload arrow). Use a navy blue (#1a365d) and teal color palette. Semi-flat style with subtle shadows. On the right side of the image, leave space for text overlay. Landscape orientation, 1200x630px.

### Key Entities

- **Blog Post**: Individual article with title, short description, markdown content body (800+ words), tags, skills, published status, featured image URL, and creation date. Each post belongs to the Phase 2 series and references specific PRs and features from the project history.
- **Tag**: Categorisation label applied to posts. 9 new reusable tags needed (listed above). Some existing tags will also apply (e.g., "Spring Boot", "React", "MongoDB", "Docker").
- **Skill**: Technical skill associated with a blog post to link content to the skills section. Uses existing skills already in the system.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All 3 blog posts are published and accessible from the blog listing page
- **SC-002**: Each blog post is at least 800 words and contains a mix of narrative text, code examples, and practical insights
- **SC-003**: Visitors can find any blog post in the series by searching for key topics (e.g., "Auth0", "Spring AI", "Docker Compose") using site search
- **SC-004**: Each blog post loads and renders correctly on both desktop and mobile viewports
- **SC-005**: The blog posts appear in the homepage latest posts preview section
- **SC-006**: All 9 new tags are created and properly associated with the relevant blog posts
- **SC-007**: Each blog post has a featured image that is visually consistent with the existing blog series images

## Assumptions

- The existing blog system (003-blog-system) is fully functional and supports creating new blog posts via data insertion or the admin CMS
- The existing tag system supports adding new tags for categorisation
- Search indexing is operational and will index new blog posts for discoverability
- The markdown renderer can handle the content complexity needed (code blocks, links, lists, headings)
- Blog post content will be written in markdown format, consistent with existing blog posts
- The site is accessible at simonrowe.dev and the blog listing is at /blogs
- Featured images will be generated externally using ChatGPT/DALL-E and uploaded via the media library or placed in the uploads directory
- Skills referenced in blog posts already exist in the system (Java, Spring Boot, React, TypeScript, MongoDB, Docker, Nginx, Grafana, Kafka, Elasticsearch, Spring AI)
- The Phase 1 rebuild series (spec 010) blog posts are already published and linkable
- Blog creation dates should be staggered to appear as a series published over time rather than all at once
