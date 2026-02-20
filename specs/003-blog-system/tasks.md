# Tasks: Blog System

**Input**: Design documents from `/specs/003-blog-system/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/blog-api.yaml

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

- **Web app**: `backend/src/`, `frontend/src/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Backend domain model, repository, service, and controller that ALL user stories depend on. Frontend TypeScript types and API client shared across all blog components.

**CRITICAL**: No user story work can begin until this phase is complete.

### Backend Foundation

- [ ] T001 [P] Create Blog MongoDB document entity with all fields (id, title, shortDescription, content, published, featuredImageUrl, createdDate, updatedDate, @DBRef tags, @DBRef skills) in `backend/src/main/java/com/simonrowe/blog/Blog.java`
- [ ] T002 [P] Create Tag MongoDB document entity with id and unique name in `backend/src/main/java/com/simonrowe/blog/Tag.java`
- [ ] T003 [P] Create TagRepository extending MongoRepository with lookup-by-name support in `backend/src/main/java/com/simonrowe/blog/TagRepository.java`
- [ ] T004 Create BlogRepository extending MongoRepository with queries for findByPublishedTrueOrderByCreatedDateDesc and findByIdAndPublishedTrue in `backend/src/main/java/com/simonrowe/blog/BlogRepository.java` (depends on T001)
- [ ] T005 Create BlogService with business logic: listPublished (all published sorted newest first), getPublishedById (single blog, 404 if not found or unpublished), getLatest (top N published) in `backend/src/main/java/com/simonrowe/blog/BlogService.java` (depends on T004)
- [ ] T006 Create BlogController with REST endpoints: GET /api/blogs (list published), GET /api/blogs/{id} (detail), GET /api/blogs/latest?limit=3 (homepage preview) per contracts/blog-api.yaml in `backend/src/main/java/com/simonrowe/blog/BlogController.java` (depends on T005)

### Frontend Foundation

- [ ] T007 [P] Create TypeScript interfaces for Blog, BlogSummary, BlogDetail, BlogSearchResult, TagRef, SkillRef, and ErrorResponse matching the OpenAPI schemas in `frontend/src/types/blog.ts`
- [ ] T008 Create frontend API service with functions: fetchBlogs(), fetchBlogById(id), fetchLatestBlogs(limit), searchBlogs(query) using fetch API in `frontend/src/services/blogApi.ts` (depends on T007)

**Checkpoint**: Backend API serves blog data from MongoDB. Frontend can call all blog endpoints. All user stories can now begin.

---

## Phase 3: User Story 1 - View Blog Article Listing (Priority: P1)

**Goal**: Visitors see a visually appealing mixed-layout grid of all published blog posts with featured images, titles, descriptions, dates, and tags.

**Independent Test**: Navigate to /blogs and verify all 18 published posts display in the mixed-layout grid with complete metadata and are clickable.

### Implementation for User Story 1

- [ ] T009 [P] [US1] Create BlogCard component with vertical and horizontal variants; vertical shows image top with content below (col-lg-4), horizontal shows image beside content (col-lg-8) with image-left and image-right sub-variants; display featured image (with placeholder fallback for FR-019), title, shortDescription, formatted date, and tag list in `frontend/src/components/blog/BlogCard.tsx`
- [ ] T010 [P] [US1] Create date formatting utility using Intl.DateTimeFormat('en-GB', { year: 'numeric', month: 'long', day: 'numeric' }) for human-readable dates (FR-017) in `frontend/src/utils/dateFormat.ts`
- [ ] T011 [US1] Create BlogGrid component implementing the 6-item repeating layout cycle: index 0,5 = vertical (1/3), index 1 = horizontal-right + horizontal-left pair (2/3), index 3 = horizontal-left + horizontal-right pair (2/3); skip indices 2 and 4 in main loop as they render within pairs in `frontend/src/components/blog/BlogGrid.tsx` (depends on T009)
- [ ] T012 [US1] Create BlogListingPage that fetches all published blogs via blogApi.fetchBlogs(), renders BlogGrid, and handles loading/error states in `frontend/src/pages/BlogListingPage.tsx` (depends on T008, T011)
- [ ] T013 [US1] Add route for /blogs pointing to BlogListingPage in the React Router configuration; ensure blog cards navigate to /blogs/:id on click (FR-020) in the app router file
- [ ] T014 [US1] Handle edge cases: blog posts with no tags display without layout breaking (FR-018), blog posts without featured images show placeholder (FR-019), long titles and descriptions truncate gracefully in `frontend/src/components/blog/BlogCard.tsx`

**Checkpoint**: Blog listing page displays all 18 published posts in the mixed-layout grid. Each card shows image, title, description, date, and tags. Clicking a card navigates to /blogs/:id.

---

## Phase 4: User Story 2 - Read Full Blog Article (Priority: P2)

**Goal**: Visitors read the full blog article with rich Markdown rendering, syntax-highlighted code blocks, and smart link handling for internal vs external URLs.

**Independent Test**: Navigate directly to /blogs/{id} and verify the article renders with full formatting, code highlighting, and correct link behavior.

### Implementation for User Story 2

- [ ] T015 [P] [US2] Create CodeBlock component using react-syntax-highlighter with Prism engine and coy theme; extract language from className prop (format: language-xxx); render SyntaxHighlighter for multi-line blocks with a language, fall back to plain <code> for inline code or blocks without a language in `frontend/src/components/blog/CodeBlock.tsx`
- [ ] T016 [P] [US2] Create SmartLink component that detects external URLs (starts with http and does not include simonrowe.dev) and renders with target="_blank" rel="noopener noreferrer" (FR-007); renders internal links using React Router Link for SPA navigation in the same tab (FR-008) in `frontend/src/components/blog/SmartLink.tsx`
- [ ] T017 [US2] Create MarkdownRenderer component configured with react-markdown, rehype-raw plugin for HTML passthrough, remark-gfm for GitHub Flavored Markdown (tables, strikethrough, task lists); wire custom components map: code -> CodeBlock, a -> SmartLink in `frontend/src/components/blog/MarkdownRenderer.tsx` (depends on T015, T016)
- [ ] T018 [US2] Create BlogDetail component displaying: featured image (hero), title, author ("Simon Rowe"), formatted publication date, tags as labels, associated skills, then full article content via MarkdownRenderer in `frontend/src/components/blog/BlogDetail.tsx` (depends on T017)
- [ ] T019 [US2] Create BlogDetailPage that extracts blog ID from route params, fetches full blog via blogApi.fetchBlogById(id), renders BlogDetail component, handles loading state, and shows 404 page for non-existent or unpublished posts in `frontend/src/pages/BlogDetailPage.tsx` (depends on T018, T008)
- [ ] T020 [US2] Add route for /blogs/:id pointing to BlogDetailPage in the React Router configuration

**Checkpoint**: Blog detail pages render Markdown content with proper formatting. Code blocks show syntax highlighting. External links open in new tabs, internal links navigate within the SPA. 404 returned for invalid or unpublished blog IDs.

---

## Phase 5: User Story 3 - Search Blog Content (Priority: P3)

**Goal**: Visitors search blog content via a typeahead field; results appear in a dropdown within 500ms matching against titles, descriptions, and full content via Elasticsearch.

**Independent Test**: Type keywords into the search field on the blog listing page and verify matching results appear in a dropdown within 500ms, display thumbnails/titles/dates, and navigate to the detail page on click.

### Backend Search

- [ ] T021 [P] [US3] Create BlogSearchDocument class annotated for Elasticsearch index "blogs" with fields: id (keyword), title (text, boost 3), shortDescription (text, boost 2), content (text), tags (keyword), skills (keyword), thumbnailImage (keyword, not indexed), createdDate (date) in `backend/src/main/java/com/simonrowe/blog/BlogSearchDocument.java`
- [ ] T022 [US3] Create BlogSearchService implementing multi-match search query across title, shortDescription, and content fields; include method to index blog documents (strip Markdown syntax from content before indexing); return BlogSearchResult DTOs with id, title, thumbnailImage, createdDate in `backend/src/main/java/com/simonrowe/blog/BlogSearchService.java` (depends on T021)
- [ ] T023 [US3] Add search endpoint GET /api/search/blogs?q={query} to BlogController (or a dedicated SearchController); validate minimum query length of 2 characters; return 400 for invalid queries per contracts/blog-api.yaml in `backend/src/main/java/com/simonrowe/blog/BlogController.java` (depends on T022)

### Frontend Search

- [ ] T024 [US3] Create BlogSearch component with: text input field, 300ms debounce on keystroke, minimum 2-character query, AbortController to cancel in-flight requests on new input, dropdown rendering BlogSearchResult items (thumbnail, title, formatted date), click handler navigating to /blogs/:id and closing dropdown, click-outside handler to dismiss dropdown, keyboard navigation (arrow keys + Enter) for accessibility, "no results" message for empty result sets in `frontend/src/components/blog/BlogSearch.tsx` (depends on T008)
- [ ] T025 [US3] Integrate BlogSearch component into BlogListingPage at the top of the page above the BlogGrid in `frontend/src/pages/BlogListingPage.tsx` (depends on T024)

**Checkpoint**: Typing in the search field triggers Elasticsearch queries. Results appear in a dropdown within 500ms. Clicking a result navigates to the blog detail page. Search matches titles, descriptions, and full content.

---

## Phase 6: User Story 4 - Preview Latest Articles on Homepage (Priority: P4)

**Goal**: The homepage displays a preview section with the 3 most recently published blog posts, each showing featured image, title, and short description, with a link to view all posts.

**Independent Test**: Visit the homepage and verify 3 most recent published posts appear with correct metadata, click through to detail pages, and "View All Posts" links to /blogs.

### Implementation for User Story 4

- [ ] T026 [US4] Create HomepageBlogPreview component that fetches 3 latest blogs via blogApi.fetchLatestBlogs(3), renders each with featured image (placeholder fallback), title, and shortDescription; each preview card links to /blogs/:id; include a "View All Posts" link navigating to /blogs in `frontend/src/components/blog/HomepageBlogPreview.tsx` (depends on T008)
- [ ] T027 [US4] Integrate HomepageBlogPreview component into the existing HomePage by adding the preview section at the appropriate position in `frontend/src/pages/HomePage.tsx` (depends on T026)

**Checkpoint**: Homepage displays the 3 most recent published blog posts. Clicking a preview navigates to the detail page. "View All Posts" navigates to /blogs.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Edge case handling, performance, and validation across all user stories.

- [ ] T028 [P] Handle edge case: blog post with no featured image renders a default placeholder image across BlogCard, BlogDetail, HomepageBlogPreview, and BlogSearch result items
- [ ] T029 [P] Handle edge case: search returns no matching results shows a user-friendly "no results" message in BlogSearch dropdown
- [ ] T030 [P] Handle edge case: fewer than 3 published blog posts for homepage preview renders available posts without layout errors in HomepageBlogPreview
- [ ] T031 [P] Handle edge case: navigating to a non-existent or unpublished blog post URL shows a 404 page in BlogDetailPage
- [ ] T032 [P] Handle edge case: code blocks without a specified language render as plain preformatted text without errors in CodeBlock
- [ ] T033 [P] Handle edge case: blog post with malformed Markdown content renders gracefully without crashing in MarkdownRenderer
- [ ] T034 Verify blog listing page becomes interactive within 2 seconds (SC-001); optimize API response size if needed by excluding content field from listing endpoint
- [ ] T035 Verify search typeahead results appear within 500ms of user input (SC-002); tune Elasticsearch query and debounce timing if needed
- [ ] T036 Run quickstart.md validation: execute all 10 verification steps and confirm expected outcomes

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: No dependencies on external phases. Blocks ALL user stories.
- **US1 - Blog Listing (Phase 3)**: Depends on Phase 2 completion.
- **US2 - Blog Detail (Phase 4)**: Depends on Phase 2 completion. Can run in parallel with US1, but US1 provides navigation to detail pages.
- **US3 - Blog Search (Phase 5)**: Depends on Phase 2 completion. Backend requires Elasticsearch setup. Can run in parallel with US1/US2.
- **US4 - Homepage Preview (Phase 6)**: Depends on Phase 2 completion. Can run in parallel with other stories.
- **Polish (Phase 7)**: Depends on all user stories being complete.

### Within Each Phase

- Models/entities before repositories
- Repositories before services
- Services before controllers/endpoints
- Backend endpoints before frontend API client
- Frontend types before API client
- API client before components
- Components before pages
- Pages before route registration

### Parallel Opportunities

- T001, T002, T003, T007 can all run in parallel (independent files, no dependencies)
- T009, T010 can run in parallel within US1 (independent components)
- T015, T016 can run in parallel within US2 (independent components)
- T021 can run in parallel with frontend US3 tasks (backend vs frontend)
- All Phase 7 tasks marked [P] can run in parallel

### Recommended Sequential Order (Single Developer)

1. Phase 2: T001 + T002 + T003 + T007 (parallel) -> T004 -> T005 -> T006 -> T008
2. Phase 3: T009 + T010 (parallel) -> T011 -> T012 -> T013 -> T014
3. Phase 4: T015 + T016 (parallel) -> T017 -> T018 -> T019 -> T020
4. Phase 5: T021 -> T022 -> T023 -> T024 -> T025
5. Phase 6: T026 -> T027
6. Phase 7: T028-T036

---

## Notes

- Blog content is stored as Markdown in MongoDB and rendered to HTML on the frontend using react-markdown
- The mixed-layout grid uses a 6-item repeating cycle with index-based variant selection (i % 6)
- Elasticsearch search index is populated via Kafka events from MongoDB changes
- Dates are returned as ISO 8601 from the backend and formatted on the frontend with Intl.DateTimeFormat
- The SmartLink component distinguishes external from internal links by checking if the href starts with http and does not contain simonrowe.dev
- All public-facing views filter on published=true; the published field is never exposed in API responses
