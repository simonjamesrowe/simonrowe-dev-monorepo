# Tasks: Content Management System

**Input**: Design documents from `/specs/007-content-management/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Auth0 security, media infrastructure, shared admin UI components, and API service layer that MUST be complete before ANY user story can be implemented

**CRITICAL**: No user story work can begin until this phase is complete

### Backend Auth & Security

- [ ] T001 [P] Add spring-boot-starter-oauth2-resource-server and Thumbnailator dependencies to backend/build.gradle
- [ ] T002 [P] Implement Auth0 JWT decoder with audience validation in backend/src/main/java/com/simonrowe/auth/Auth0JwtDecoder.java -- custom JwtDecoder bean using NimbusJwtDecoder with AudienceValidator for Auth0 aud claim, combined with issuer validation via DelegatingOAuth2TokenValidator
- [ ] T003 Implement Spring Security filter chain config in backend/src/main/java/com/simonrowe/auth/SecurityConfig.java -- protect /api/admin/** as authenticated, permit all other requests, configure OAuth2 resource server with JWT, disable CSRF, set stateless session management (depends on T002)
- [ ] T004 [P] Write SecurityConfig integration tests in backend/src/test/java/com/simonrowe/auth/SecurityConfigTest.java -- verify /api/admin/** returns 401 without token, 200 with valid mock JWT, public endpoints remain accessible; use Spring Security test jwt() post-processor

### Backend Media Infrastructure

- [ ] T005 [P] Create MediaAsset MongoDB document model in backend/src/main/java/com/simonrowe/media/MediaAsset.java -- fields: id, fileName, mimeType, fileSize, originalPath, variants (Map<String, VariantInfo>), createdAt, updatedAt, legacyId; include VariantInfo nested class with path, width, height, fileSize; add Spring Data MongoDB @Document annotation for media_assets collection
- [ ] T006 [P] Create MediaAssetRepository interface in backend/src/main/java/com/simonrowe/media/MediaAssetRepository.java -- extend MongoRepository<MediaAsset, String>; add findByFileNameContainingIgnoreCase for search, findByMimeType for filtering
- [ ] T007 Implement ImageVariantGenerator in backend/src/main/java/com/simonrowe/media/ImageVariantGenerator.java -- use Thumbnailator to generate four variants (thumbnail 150px, small 300px, medium 600px, large 1200px) from uploaded original; return VariantInfo with actual dimensions and file size; handle JPEG/PNG/GIF/WebP; skip variant generation for SVG files (depends on T005)
- [ ] T008 Implement MediaService in backend/src/main/java/com/simonrowe/media/MediaService.java -- handle file upload to configurable MEDIA_DIR, validate MIME type (image/jpeg, image/png, image/gif, image/webp, image/svg+xml) and max 10MB size, delegate to ImageVariantGenerator for variant creation, persist MediaAsset via repository, support list with pagination/search/filter, delete with file cleanup (depends on T005, T006, T007)
- [ ] T009 Implement MediaController in backend/src/main/java/com/simonrowe/media/MediaController.java -- POST /api/admin/media (multipart upload), GET /api/admin/media (paginated list with mimeType and search params), GET /api/admin/media/{id}, DELETE /api/admin/media/{id} with 409 if referenced by content entities; per media-api.yaml contract (depends on T008)
- [ ] T010 [P] Write ImageVariantGenerator unit tests in backend/src/test/java/com/simonrowe/media/ImageVariantGeneratorTest.java -- verify four variants generated with correct max dimensions, quality settings, output format; verify SVG passthrough
- [ ] T011 Write MediaController integration tests in backend/src/test/java/com/simonrowe/media/MediaControllerTest.java -- test upload with valid image, upload with invalid MIME type returns 400, upload exceeding 10MB returns 413, list with pagination, search by fileName, delete, delete referenced asset returns 409; use Testcontainers for MongoDB and mock JWT (depends on T009)

### Frontend Auth & Admin Shell

- [ ] T012 [P] Install @auth0/auth0-react and @mdxeditor/editor npm dependencies in frontend/package.json
- [ ] T013 [P] Implement Auth0 provider config and useAuth hook wrapper in frontend/src/services/auth.ts -- configure Auth0Provider with VITE_AUTH0_DOMAIN, VITE_AUTH0_CLIENT_ID, VITE_AUTH0_AUDIENCE from env; export wrapped useAuth hook providing login/logout/getAccessTokenSilently
- [ ] T014 Implement admin API service with auth token injection in frontend/src/services/adminApi.ts -- create HTTP client wrapper (fetch or axios) that injects Auth0 bearer token via getAccessTokenSilently on every request; export typed methods for GET, POST, PUT, PATCH, DELETE to /api/admin/* endpoints; handle 401 by triggering re-authentication (depends on T013)
- [ ] T015 Implement AdminLayout component with Auth0 login gate in frontend/src/pages/admin/AdminLayout.tsx -- wrap with withAuthenticationRequired HOC from Auth0; include sidebar navigation with links to Dashboard, Blogs, Jobs, Skills, Profile, Tags, Tour Steps, Media; render Outlet for nested routes; show loading spinner during auth redirect (depends on T013)
- [ ] T016 [P] Write AdminLayout tests in frontend/tests/admin/AdminLayout.test.tsx -- verify unauthenticated users trigger Auth0 redirect, authenticated users see sidebar nav, navigation links render correctly; mock @auth0/auth0-react

### Shared Admin Components

- [ ] T017 [P] Implement MarkdownEditor component in frontend/src/components/admin/MarkdownEditor.tsx -- wrap MDXEditor with headingsPlugin, listsPlugin, quotePlugin, linkPlugin, imagePlugin (connected to media upload API), codeBlockPlugin, markdownShortcutPlugin, toolbarPlugin with BoldItalicUnderlineToggles, BlockTypeSelect, InsertImage; accept markdown string and onChange callback props
- [ ] T018 [P] Write MarkdownEditor tests in frontend/tests/admin/MarkdownEditor.test.tsx -- verify toolbar renders, content changes fire onChange, image plugin triggers upload handler
- [ ] T019 Implement MediaLibrary component in frontend/src/components/admin/MediaLibrary.tsx -- display paginated grid of uploaded images (thumbnail variant), support search by file name, MIME type filter, click-to-select with onSelect callback returning MediaAsset ID; use adminApi GET /api/admin/media (depends on T014)
- [ ] T020 Implement ImageUploader component in frontend/src/components/admin/ImageUploader.tsx -- drag-and-drop zone for image file upload, show upload progress, validate file type and size client-side, call adminApi POST /api/admin/media, display generated thumbnail on completion; accept onUploadComplete callback with MediaAsset (depends on T014)
- [ ] T021 [P] Write MediaLibrary tests in frontend/tests/admin/MediaLibrary.test.tsx -- verify grid renders thumbnails, search filters results, selection callback fires with correct ID

**Checkpoint**: Foundation ready -- Auth0 security protects admin endpoints, media upload and variant generation works, shared UI components (Markdown editor, media library, image uploader, admin layout) are available. User story implementation can now begin.

---

## Phase 3: US1 - Blog Post Management (Priority: P1)

**Goal**: Authenticated administrators can create, edit, publish, and delete blog posts with Markdown content, featured images, tags, and skill associations

**Independent Test**: Authenticate as admin, create a draft blog post with title, content, image, tags, and skills. Publish it and verify it appears on the public blog listing page.

### Backend

- [ ] T022 [P] [US1] Create Blog MongoDB document model in backend/src/main/java/com/simonrowe/admin/Blog.java -- fields per data-model.md: id, title, shortDescription, content (Markdown), published (boolean, default false), featuredImage (MediaAsset ID), tags (String[]), skills (String[]), createdAt, updatedAt, legacyId; @Document for blogs collection; add indexes for {published, createdAt}, {tags}, {legacyId}
- [ ] T023 [P] [US1] Create BlogRepository interface in backend/src/main/java/com/simonrowe/admin/BlogRepository.java -- extend MongoRepository<Blog, String>; add findByPublishedOrderByCreatedAtDesc for public listing, findByLegacyId for migration
- [ ] T024 [US1] Implement AdminBlogController in backend/src/main/java/com/simonrowe/admin/AdminBlogController.java -- GET /api/admin/blogs (list all with optional published filter, paginated), POST /api/admin/blogs (create, return 201), GET /api/admin/blogs/{id} (return 404 if missing), PUT /api/admin/blogs/{id} (update), DELETE /api/admin/blogs/{id} (return 204); validate required fields (title max 200, shortDescription max 500, content non-empty when published, at least one tag when published); return ValidationErrorResponse with field errors on 400; per admin-api.yaml contract (depends on T022, T023)
- [ ] T025 [US1] Write AdminBlogController integration tests in backend/src/test/java/com/simonrowe/admin/AdminBlogControllerTest.java -- test create draft blog returns 201, create published blog without required tag returns 400, get by id returns 200, get missing id returns 404, update blog returns 200, delete blog returns 204, list with published filter, unauthenticated access returns 401; use Testcontainers MongoDB and mock JWT (depends on T024)

### Frontend

- [ ] T026 [US1] Implement BlogEditor component in frontend/src/components/admin/BlogEditor.tsx -- form with: title input (max 200), shortDescription textarea (max 500), MarkdownEditor for content, published toggle switch, featured image selector (opens MediaLibrary modal), tag multi-select (fetches from GET /api/admin/tags), skill multi-select (fetches from GET /api/admin/skills); client-side validation matching backend rules; save and cancel buttons (depends on T017, T019, T014)
- [ ] T027 [US1] Implement BlogsAdmin page in frontend/src/pages/admin/BlogsAdmin.tsx -- list view showing all blogs in a table (title, published status, createdAt, tag count); "New Blog Post" button; click row to edit; delete button with confirmation dialog; pagination controls; route to BlogEditor for create/edit (depends on T026)
- [ ] T028 [P] [US1] Write BlogEditor tests in frontend/tests/admin/BlogEditor.test.tsx -- verify form renders all fields, draft/published toggle works, validation prevents publishing without required fields, save calls correct API endpoint, tag and skill multi-selects populate from API

**Checkpoint**: Blog post CRUD is fully functional. Admins can create drafts, publish posts with tags/skills/images, edit and delete. Published posts appear on public site, drafts do not.

---

## Phase 4: US2 - Employment History Management (Priority: P2)

**Goal**: Authenticated administrators can manage employment records and educational achievements with company details, date ranges, Markdown descriptions, and skill associations

**Independent Test**: Authenticate as admin, create a new job entry with company name, dates, description, and skills. Verify it appears chronologically on the resume/about page.

### Backend

- [ ] T029 [P] [US2] Create Job MongoDB document model in backend/src/main/java/com/simonrowe/admin/Job.java -- fields per data-model.md: id, title, company, companyUrl, companyImage (MediaAsset ID), startDate (ISO string), endDate (nullable for current), location, shortDescription, longDescription (Markdown), education (boolean, default false), includeOnResume (boolean, default true), skills (String[]), createdAt, updatedAt, legacyId; @Document for jobs collection; indexes for {startDate desc}, {education}, {includeOnResume}, {legacyId}
- [ ] T030 [P] [US2] Create JobRepository interface in backend/src/main/java/com/simonrowe/admin/JobRepository.java -- extend MongoRepository<Job, String>; add findAllByOrderByStartDateDesc, findByEducation, findByIncludeOnResume, findByLegacyId
- [ ] T031 [US2] Implement AdminJobController in backend/src/main/java/com/simonrowe/admin/AdminJobController.java -- GET /api/admin/jobs (list all with optional education filter, paginated), POST /api/admin/jobs (create, return 201), GET /api/admin/jobs/{id}, PUT /api/admin/jobs/{id}, DELETE /api/admin/jobs/{id}; validate: title and company non-empty max 200, startDate required and valid ISO date, endDate must be after startDate when provided, shortDescription non-empty; per admin-api.yaml contract (depends on T029, T030)
- [ ] T032 [US2] Write AdminJobController integration tests in backend/src/test/java/com/simonrowe/admin/AdminJobControllerTest.java -- test create job returns 201, create with endDate before startDate returns 400, update job returns 200, delete returns 204, list with education filter, null endDate accepted for current position, unauthenticated returns 401; use Testcontainers MongoDB and mock JWT (depends on T031)

### Frontend

- [ ] T033 [US2] Implement JobEditor component in frontend/src/components/admin/JobEditor.tsx -- form with: title input, company input, companyUrl input, company image selector (opens MediaLibrary), startDate date picker, endDate date picker (nullable, "Current" checkbox), location input, shortDescription textarea, MarkdownEditor for longDescription, education/employment toggle, includeOnResume checkbox, skill multi-select; validate endDate after startDate; save and cancel buttons (depends on T017, T019, T014)
- [ ] T034 [US2] Implement JobsAdmin page in frontend/src/pages/admin/JobsAdmin.tsx -- list view showing jobs in a table (title, company, startDate-endDate, education flag, includeOnResume flag); "New Job" button; click row to edit; delete with confirmation; filter tabs for Employment vs Education; pagination (depends on T033)

**Checkpoint**: Employment and education history CRUD is fully functional. Admins can create job/education entries with dates, descriptions, skill associations, and resume inclusion flags.

---

## Phase 5: US3 - Skills and Competencies Management (Priority: P3)

**Goal**: Authenticated administrators can manage skills and skill groups with ratings, ordering, descriptions, and visual elements

**Independent Test**: Authenticate as admin, create a skill group (e.g., "Backend Development"), add skills with ratings, reorder them via drag-and-drop, and verify the skills display in correct order on the public skills page.

### Backend

- [ ] T035 [P] [US3] Create Skill MongoDB document model in backend/src/main/java/com/simonrowe/admin/Skill.java -- fields per data-model.md: id, name, rating (Double, 0-10), description (Markdown), image (MediaAsset ID), order (Integer), createdAt, updatedAt, legacyId; @Document for skills collection; indexes for {order}, {name unique}, {legacyId}
- [ ] T036 [P] [US3] Create SkillRepository interface in backend/src/main/java/com/simonrowe/admin/SkillRepository.java -- extend MongoRepository<Skill, String>; add findAllByOrderByOrderAsc, findByName, findByLegacyId
- [ ] T037 [P] [US3] Create SkillGroup MongoDB document model in backend/src/main/java/com/simonrowe/admin/SkillGroup.java -- fields per data-model.md: id, name, rating (Double, nullable), description, image (MediaAsset ID), order (Integer), skills (String[] of Skill IDs in display order), createdAt, updatedAt, legacyId; @Document for skill_groups collection; indexes for {order}, {legacyId}
- [ ] T038 [P] [US3] Create SkillGroupRepository interface in backend/src/main/java/com/simonrowe/admin/SkillGroupRepository.java -- extend MongoRepository<SkillGroup, String>; add findAllByOrderByOrderAsc, findByLegacyId
- [ ] T039 [US3] Implement AdminSkillController in backend/src/main/java/com/simonrowe/admin/AdminSkillController.java -- GET /api/admin/skills (list paginated, ordered by order field), POST /api/admin/skills (create, return 201), GET /api/admin/skills/{id}, PUT /api/admin/skills/{id}, DELETE /api/admin/skills/{id}, PATCH /api/admin/skills/reorder (accept ReorderRequest with orderedIds, update order field on each skill); validate: name non-empty max 100, rating 0.0-10.0, order non-negative; per admin-api.yaml contract (depends on T035, T036)
- [ ] T040 [US3] Implement AdminSkillGroupController in backend/src/main/java/com/simonrowe/admin/AdminSkillGroupController.java -- GET /api/admin/skill-groups (list paginated, ordered by order field), POST /api/admin/skill-groups (create, return 201), GET /api/admin/skill-groups/{id}, PUT /api/admin/skill-groups/{id}, DELETE /api/admin/skill-groups/{id}, PATCH /api/admin/skill-groups/reorder (accept ReorderRequest with orderedIds, update order field on each group); validate: name non-empty max 100, rating 0.0-10.0 when provided, order non-negative, skills array references valid Skill IDs; per admin-api.yaml contract (depends on T037, T038)
- [ ] T041 [US3] Write AdminSkillController integration tests in backend/src/test/java/com/simonrowe/admin/AdminSkillControllerTest.java -- test create skill returns 201, create with rating >10 returns 400, duplicate name returns 400/409, reorder updates order fields, delete returns 204, unauthenticated returns 401 (depends on T039)
- [ ] T042 [US3] Write AdminSkillGroupController integration tests in backend/src/test/java/com/simonrowe/admin/AdminSkillGroupControllerTest.java -- test create skill group returns 201, reorder skill groups updates order fields, update skill group with skills array, delete returns 204, unauthenticated returns 401 (depends on T040)

### Frontend

- [ ] T043 [US3] Implement SkillEditor component in frontend/src/components/admin/SkillEditor.tsx -- form with: name input (max 100), rating slider/input (0-10 numeric scale with 0.1 step), description MarkdownEditor, image selector (opens MediaLibrary), order number input; save and cancel buttons (depends on T017, T019, T014)
- [ ] T044 [US3] Implement SkillGroupEditor component in frontend/src/components/admin/SkillGroupEditor.tsx -- form with: name input (max 100), rating input (0-10 optional), description textarea, image selector, order number input, drag-and-drop sortable list of skills assigned to the group; add/remove skills from group; save triggers PUT with updated skills array order (depends on T043, T014)
- [ ] T045 [US3] Implement SkillsAdmin page in frontend/src/pages/admin/SkillsAdmin.tsx -- two-panel layout: left panel lists skill groups with drag-and-drop reorder (calls PATCH /api/admin/skill-groups/reorder), right panel shows skills within selected group with drag-and-drop reorder (calls PATCH /api/admin/skills/reorder); "New Skill" and "New Skill Group" buttons; click to edit inline or in modal; delete with confirmation; rating displayed as visual bar (depends on T043, T044)

**Checkpoint**: Skills and skill groups CRUD is fully functional. Admins can create skills with ratings, organize into groups, drag-and-drop reorder, and changes persist immediately on the public skills page.

---

## Phase 6: US4 - Profile and Media Management (Priority: P4)

**Goal**: Authenticated administrators can manage profile information, social media links, tags, tour steps, and perform data migration from Strapi backup

**Independent Test**: Authenticate as admin, update profile fields (name, headline, location), upload a profile image, add social media links, and verify changes appear on the about/contact pages.

### Backend

- [ ] T046 [P] [US4] Create Profile MongoDB document model in backend/src/main/java/com/simonrowe/admin/Profile.java -- fields per data-model.md: id, name, title, headline, description (Markdown), location, phoneNumber, primaryEmail, secondaryEmail, profileImage, sidebarImage, backgroundImage, mobileBackgroundImage (all MediaAsset IDs), createdAt, updatedAt; @Document for profiles collection; single-instance entity
- [ ] T047 [P] [US4] Create ProfileRepository interface in backend/src/main/java/com/simonrowe/admin/ProfileRepository.java -- extend MongoRepository<Profile, String>; add findFirst for singleton access
- [ ] T048 [P] [US4] Create SocialMediaLink MongoDB document model in backend/src/main/java/com/simonrowe/admin/SocialMediaLink.java -- fields per data-model.md: id, type (enum: github, linkedin, twitter, website, stackoverflow, medium, other), link, name, includeOnResume (boolean), createdAt, updatedAt, legacyId; @Document for social_media_links collection; indexes for {type}, {legacyId}
- [ ] T049 [P] [US4] Create SocialMediaLinkRepository interface in backend/src/main/java/com/simonrowe/admin/SocialMediaLinkRepository.java -- extend MongoRepository<SocialMediaLink, String>; add findByLegacyId
- [ ] T050 [P] [US4] Create Tag MongoDB document model in backend/src/main/java/com/simonrowe/admin/Tag.java -- fields per data-model.md: id, name, createdAt, updatedAt, legacyId; @Document for tags collection; indexes for {name unique case-insensitive}, {legacyId}
- [ ] T051 [P] [US4] Create TagRepository interface in backend/src/main/java/com/simonrowe/admin/TagRepository.java -- extend MongoRepository<Tag, String>; add findByNameIgnoreCase, findByLegacyId
- [ ] T052 [P] [US4] Create TourStep MongoDB document model in backend/src/main/java/com/simonrowe/admin/TourStep.java -- fields per data-model.md: id, title, selector, description, titleImage (MediaAsset ID), position (enum: top, bottom, left, right, center), order (Integer), createdAt, updatedAt, legacyId; @Document for tour_steps collection; indexes for {order}, {legacyId}
- [ ] T053 [P] [US4] Create TourStepRepository interface in backend/src/main/java/com/simonrowe/admin/TourStepRepository.java -- extend MongoRepository<TourStep, String>; add findAllByOrderByOrderAsc, findByLegacyId
- [ ] T054 [US4] Implement AdminProfileController in backend/src/main/java/com/simonrowe/admin/AdminProfileController.java -- GET /api/admin/profile (return singleton or 404 if not created), PUT /api/admin/profile (upsert singleton); validate: name non-empty max 100, title non-empty max 100, primaryEmail valid format when provided; per admin-api.yaml contract (depends on T046, T047)
- [ ] T055 [US4] Implement AdminSocialMediaController in backend/src/main/java/com/simonrowe/admin/AdminSocialMediaController.java -- GET /api/admin/social-media (list all), POST /api/admin/social-media (create, return 201), PUT /api/admin/social-media/{id} (update), DELETE /api/admin/social-media/{id} (return 204); validate: type must be valid enum, link must be valid URL, name non-empty max 100; per admin-api.yaml contract (depends on T048, T049)
- [ ] T056 [US4] Implement AdminTagController in backend/src/main/java/com/simonrowe/admin/AdminTagController.java -- GET /api/admin/tags (list all), POST /api/admin/tags (create, return 201, return 409 if duplicate name), PUT /api/admin/tags/{id} (rename, return 409 if duplicate), DELETE /api/admin/tags/{id} (delete tag and remove from all blog posts that reference it), POST /api/admin/tags/bulk (bulk create, skip existing); validate: name non-empty max 100, unique case-insensitive; per admin-api.yaml contract (depends on T050, T051)
- [ ] T057 [US4] Implement AdminTourStepController in backend/src/main/java/com/simonrowe/admin/AdminTourStepController.java -- GET /api/admin/tour-steps (list ordered), POST /api/admin/tour-steps (create, return 201), PUT /api/admin/tour-steps/{id} (update), DELETE /api/admin/tour-steps/{id} (return 204), PATCH /api/admin/tour-steps/reorder (accept ReorderRequest); validate: title non-empty max 200, selector non-empty, description non-empty, position valid enum, order non-negative; per admin-api.yaml contract (depends on T052, T053)
- [ ] T058 [US4] Write AdminProfileController integration tests in backend/src/test/java/com/simonrowe/admin/AdminProfileControllerTest.java -- test get profile 404 when empty, upsert profile returns 200, get after upsert returns profile, invalid email returns 400, unauthenticated returns 401 (depends on T054)
- [ ] T059 [US4] Write AdminSocialMediaController integration tests in backend/src/test/java/com/simonrowe/admin/AdminSocialMediaControllerTest.java -- test create returns 201, invalid type returns 400, update returns 200, delete returns 204, unauthenticated returns 401 (depends on T055)
- [ ] T060 [US4] Write AdminTagController integration tests in backend/src/test/java/com/simonrowe/admin/AdminTagControllerTest.java -- test create tag returns 201, duplicate name returns 409, rename tag, delete tag removes from blog posts, bulk create skips existing, unauthenticated returns 401 (depends on T056)
- [ ] T061 [US4] Write AdminTourStepController integration tests in backend/src/test/java/com/simonrowe/admin/AdminTourStepControllerTest.java -- test create returns 201, reorder updates order, invalid position returns 400, delete returns 204, unauthenticated returns 401 (depends on T057)

### Frontend

- [ ] T062 [US4] Implement ProfileEditor component in frontend/src/components/admin/ProfileEditor.tsx -- form with: name input, title input, headline textarea, description MarkdownEditor, location input, phoneNumber input, primaryEmail input, secondaryEmail input, profileImage selector (MediaLibrary), sidebarImage selector, backgroundImage selector, mobileBackgroundImage selector; save button calls PUT /api/admin/profile (depends on T017, T019, T014)
- [ ] T063 [US4] Implement SocialMediaEditor component in frontend/src/components/admin/SocialMediaEditor.tsx -- inline list editor: each row has type dropdown (github, linkedin, twitter, website, stackoverflow, medium, other), link input, name input, includeOnResume checkbox; add new row, delete with confirmation; each save calls POST or PUT /api/admin/social-media (depends on T014)
- [ ] T064 [US4] Implement TagManager component in frontend/src/components/admin/TagManager.tsx -- display all tags as a grid/list with inline rename; "New Tag" input and button; bulk create input (comma-separated names, calls POST /api/admin/tags/bulk); delete with confirmation showing count of associated blog posts; search/filter tags (depends on T014)
- [ ] T065 [US4] Implement TourStepEditor component in frontend/src/components/admin/TourStepEditor.tsx -- form with: title input (max 200), selector input (CSS selector), description MarkdownEditor, titleImage selector (MediaLibrary), position dropdown (top, bottom, left, right, center), order number input; save and cancel buttons (depends on T017, T019, T014)
- [ ] T066 [US4] Implement ProfileAdmin page in frontend/src/pages/admin/ProfileAdmin.tsx -- tabbed layout with Profile tab (ProfileEditor) and Social Media tab (SocialMediaEditor); loads profile on mount via GET /api/admin/profile; shows "Create Profile" state if 404 (depends on T062, T063)
- [ ] T067 [US4] Implement TagsAdmin page in frontend/src/pages/admin/TagsAdmin.tsx -- full-page TagManager with header showing total tag count (depends on T064)
- [ ] T068 [US4] Implement TourStepsAdmin page in frontend/src/pages/admin/TourStepsAdmin.tsx -- list of tour steps with drag-and-drop reorder (calls PATCH /api/admin/tour-steps/reorder); click to edit inline or modal; "New Tour Step" button; delete with confirmation (depends on T065)

### Data Migration

- [ ] T069 [US4] Implement DataMigrationService in backend/src/main/java/com/simonrowe/migration/DataMigrationService.java -- Spring Boot ApplicationRunner activated by --spring.profiles.active=migrate; parse BSON files from configurable backup path using org.bson; process in dependency order: (1) tags, (2) media assets + copy files + generate variants, (3) skills, (4) skill groups, (5) profile, (6) social media links, (7) jobs, (8) blogs, (9) tour steps; maintain old-ID-to-new-ID mapping for cross-references; upsert by legacyId for idempotent re-runs; log progress for each entity type (depends on T005, T022, T029, T035, T037, T046, T048, T050, T052)

### Admin Dashboard

- [ ] T070 [US4] Implement AdminDashboard page in frontend/src/pages/admin/AdminDashboard.tsx -- overview page showing content counts: total blogs (published/draft), total jobs, total skills, total skill groups, total tags, total media assets; quick-link cards to each content management section; fetch counts from respective list endpoints (depends on T014)

**Checkpoint**: All content types have full CRUD. Profile, social media, tags, and tour steps are manageable. Data migration imports all existing Strapi content. Admin dashboard provides an overview of all content.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories, final integration, and quality validation

- [ ] T071 [P] Configure admin route definitions in frontend/src/router.tsx (or equivalent) -- add /admin routes: /admin (Dashboard), /admin/blogs (BlogsAdmin), /admin/blogs/new (BlogEditor), /admin/blogs/:id/edit (BlogEditor), /admin/jobs (JobsAdmin), /admin/jobs/new (JobEditor), /admin/jobs/:id/edit (JobEditor), /admin/skills (SkillsAdmin), /admin/profile (ProfileAdmin), /admin/tags (TagsAdmin), /admin/tour-steps (TourStepsAdmin), /admin/media (MediaAdmin); all nested under AdminLayout for auth gate
- [ ] T072 [P] Implement MediaAdmin page in frontend/src/pages/admin/MediaAdmin.tsx -- full-page media library view with upload zone, paginated grid, search, MIME type filter, delete with confirmation
- [ ] T073 [P] Add unsaved changes warning -- implement beforeunload handler and in-app navigation guards on all editor components (BlogEditor, JobEditor, SkillEditor, SkillGroupEditor, ProfileEditor, TourStepEditor) to prevent data loss when navigating away from dirty forms
- [ ] T074 [P] Add confirmation dialogs for all delete operations across admin pages -- consistent modal with entity name, type, and warning about cascading effects (e.g., deleting a tag removes it from all blog posts, deleting a skill removes it from blog/job associations)
- [ ] T075 [P] Add loading states and error handling across all admin pages -- loading spinners during API calls, error toast/banner on API failure, retry buttons on transient errors, optimistic UI updates where appropriate
- [ ] T076 [P] Configure Spring Boot static resource serving for media files -- add file:${MEDIA_DIR:/data/media/} to spring.web.resources.static-locations in application.yml so uploaded images are publicly accessible at /media/{path}
- [ ] T077 [P] Add structured logging for all admin operations in backend controllers -- log create, update, delete operations with entity type, entity ID, and administrator identity (from JWT sub claim) using SLF4J structured logging
- [ ] T078 Run quickstart.md end-to-end validation -- start infrastructure via docker compose, start backend, start frontend, authenticate with Auth0, perform full CRUD cycle for each content type, verify media upload and variant generation, verify public pages reflect admin changes

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: No dependencies on other phases -- can start immediately. BLOCKS all user stories.
- **US1 Blog Post Management (Phase 3)**: Depends on Phase 2 completion. Requires Tag and Skill models from Phase 5/6 for association selectors, but can stub with IDs initially.
- **US2 Employment Management (Phase 4)**: Depends on Phase 2 completion. Requires Skill model from Phase 5 for association selectors.
- **US3 Skills Management (Phase 5)**: Depends on Phase 2 completion.
- **US4 Profile & Media Management (Phase 6)**: Depends on Phase 2 completion. DataMigrationService depends on all entity models from Phase 3, 4, and 5.
- **Polish (Phase 7)**: Depends on all user story phases being complete.

### Recommended Execution Order

Since entity models are needed across stories (Tags for US1, Skills for US1/US2), the recommended sequential order is:

1. **Phase 2**: Foundational (all tasks)
2. **Phase 5**: US3 Skills (creates Skill/SkillGroup models needed by US1 and US2)
3. **Phase 6**: US4 Profile & Media (creates Tag/Profile/SocialMedia/TourStep models; but defer T069 DataMigration until after US1/US2)
4. **Phase 3**: US1 Blog Post Management (uses Tag and Skill models from Phase 5/6)
5. **Phase 4**: US2 Employment Management (uses Skill model from Phase 5)
6. **Phase 6 T069**: DataMigrationService (all entity models now exist)
7. **Phase 7**: Polish

### Parallel Opportunities

- Within Phase 2: All tasks marked [P] can run in parallel
- Within each user story: Backend and frontend work can proceed in parallel once models exist
- All model creation tasks (T022, T029, T035, T037, T046, T048, T050, T052) marked [P] can run in parallel
- All repository tasks marked [P] can run in parallel
- All test tasks marked [P] can run in parallel within their phase

---

## Notes

- [P] tasks = different files, no dependencies on other tasks in the same phase
- [US#] label maps task to specific user story for traceability
- Each user story is independently completable and testable after Phase 2
- Backend tests use Testcontainers for MongoDB and Spring Security test jwt() for auth mocking
- Frontend tests use Vitest, React Testing Library, and mocked @auth0/auth0-react
- All admin API endpoints require Auth0 JWT bearer token
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
