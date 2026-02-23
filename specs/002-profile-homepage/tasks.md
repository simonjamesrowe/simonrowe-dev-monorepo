# Tasks: Profile & Homepage

**Input**: Design documents from `/specs/002-profile-homepage/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/profile-api.yaml

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Exact file paths included in descriptions

## Path Conventions

- **Web app**: `backend/src/` for Java/Spring Boot, `frontend/src/` for React/TypeScript
- Backend package root: `backend/src/main/java/com/simonrowe/`
- Frontend source root: `frontend/src/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: No project setup needed -- this feature uses the infrastructure established in Spec 001 (Docker Compose, MongoDB, backend/frontend project scaffolding, CI/CD pipeline).

No tasks in this phase.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Backend domain models, repository layer, service layer, REST API endpoint, and frontend data-fetching infrastructure that ALL user stories depend on.

**CRITICAL**: No user story work can begin until this phase is complete.

### Backend

- [X] T001 [P] Create Image embedded record in backend/src/main/java/com/simonrowe/profile/Image.java -- fields: url, name, width, height, mime, formats (see data-model.md Image subdocument)
- [X] T002 [P] Create ImageFormats embedded record in backend/src/main/java/com/simonrowe/profile/ImageFormats.java -- fields: thumbnail, small, medium, large (each of type Image)
- [X] T003 [P] Create SocialMediaLink MongoDB document record in backend/src/main/java/com/simonrowe/profile/SocialMediaLink.java -- @Document(collection = "social_medias"), fields: id, type, name, link, includeOnResume, createdAt, updatedAt
- [X] T004 Create Profile MongoDB document record in backend/src/main/java/com/simonrowe/profile/Profile.java -- @Document(collection = "profiles"), fields: id, name, title, headline, description, profileImage, sidebarImage, backgroundImage, mobileBackgroundImage, location, phoneNumber, primaryEmail, secondaryEmail, cvUrl, createdAt, updatedAt (depends on T001, T002)
- [X] T005 [P] Create SocialMediaLinkResponse DTO record in backend/src/main/java/com/simonrowe/profile/SocialMediaLinkResponse.java -- fields: type, name, url (mapped from link), includeOnResume; include static factory method fromEntity(SocialMediaLink)
- [X] T006 Create ProfileResponse DTO record in backend/src/main/java/com/simonrowe/profile/ProfileResponse.java -- all profile fields plus socialMediaLinks list; include static factory method fromEntities(Profile, List<SocialMediaLink>) per contracts/profile-api.yaml schema (depends on T001, T002, T005)
- [X] T007 [P] Create ProfileRepository interface in backend/src/main/java/com/simonrowe/profile/ProfileRepository.java -- extends MongoRepository<Profile, String> with Optional<Profile> findFirstBy()
- [X] T008 [P] Create SocialMediaLinkRepository interface in backend/src/main/java/com/simonrowe/profile/SocialMediaLinkRepository.java -- extends MongoRepository<SocialMediaLink, String>
- [X] T009 Create ProfileService in backend/src/main/java/com/simonrowe/profile/ProfileService.java -- getProfile() method calls ProfileRepository.findFirstBy() and SocialMediaLinkRepository.findAll(), assembles ProfileResponse; throws ResponseStatusException(404) if no profile found (depends on T006, T007, T008)
- [X] T010 Create ProfileController in backend/src/main/java/com/simonrowe/profile/ProfileController.java -- @RestController, GET /api/profile endpoint returning ProfileResponse; delegates to ProfileService (depends on T009)

### Backend Tests

- [X] T011 [P] Create ProfileServiceTest unit test in backend/src/test/java/com/simonrowe/profile/ProfileServiceTest.java -- mock repositories, test happy path returns assembled ProfileResponse, test 404 when no profile exists
- [X] T012 [P] Create ProfileControllerTest integration test in backend/src/test/java/com/simonrowe/profile/ProfileControllerTest.java -- @SpringBootTest with Testcontainers MongoDB, seed test data, verify GET /api/profile returns 200 with correct JSON shape, verify 404 when collection empty

### Frontend

- [X] T013 [P] Create Profile TypeScript interface in frontend/src/types/Profile.ts -- match ProfileResponse schema from contracts/profile-api.yaml including Image, ImageFormats, nested types
- [X] T014 [P] Create SocialMediaLink TypeScript interface in frontend/src/types/SocialMediaLink.ts -- fields: type ('github' | 'linkedin' | 'twitter'), name, url, includeOnResume
- [X] T015 Create profileApi service in frontend/src/services/profileApi.ts -- fetchProfile() function calling GET /api/profile, returns Promise<Profile>; handle errors gracefully (depends on T013, T014)
- [X] T016 Create useProfile custom hook in frontend/src/hooks/useProfile.ts -- manages loading, error, and profile data state; calls profileApi.fetchProfile() on mount; returns { profile, loading, error } (depends on T015)

**Checkpoint**: Backend serves GET /api/profile with full profile + social media links. Frontend can fetch and hold profile data in state. All user stories can now begin.

---

## Phase 3: User Story 1 -- First Impression & Profile Discovery (Priority: P1) -- MVP

**Goal**: Visitor lands on the homepage and immediately sees the professional profile: name, title, headline, images, about section with Markdown rendering, and expandable contact details.

**Independent Test**: Load the homepage URL in a browser and verify all profile elements are visible and readable.

**Requirements**: FR-001, FR-002, FR-003, FR-004, FR-005, FR-016, FR-018, FR-019, FR-020
**Acceptance Scenarios**: US1-1 through US1-5

### Implementation for User Story 1

- [X] T017 [P] [US1] Install react-markdown dependency in frontend/package.json -- npm install react-markdown rehype-sanitize (per RD-004)
- [X] T018 [US1] Create ProfileBanner component in frontend/src/components/profile/ProfileBanner.tsx -- displays name, professional title, headline message, background image (desktop), mobile background image (via CSS media query per RD-003/FR-009), profile photograph; receives profile data as props (depends on T013)
- [X] T019 [US1] Create AboutSection component in frontend/src/components/profile/AboutSection.tsx -- renders profile.description using react-markdown with rehype-sanitize; custom 'a' component override opens links in new tab with target="_blank" rel="noopener noreferrer" (per RD-004, FR-003, FR-020) (depends on T017)
- [X] T020 [US1] Create ContactDetails component in frontend/src/components/profile/ContactDetails.tsx -- expand/collapse panel (useState toggle) showing location, primaryEmail (mailto link), secondaryEmail (if non-empty), phoneNumber; collapse/expand within 200ms (SC-007, FR-004, FR-005)
- [X] T021 [US1] Create a loading indicator component or inline loading state in frontend/src/components/common/LoadingIndicator.tsx -- spinner or skeleton displayed while useProfile.loading is true; appears within 100ms of page load (SC-009, FR-016)
- [X] T022 [US1] Create an error state component or inline error display in frontend/src/components/common/ErrorMessage.tsx -- displayed when useProfile.error is truthy; shows user-friendly message and optional retry action (FR-019)
- [X] T023 [US1] Create HomePage container in frontend/src/pages/HomePage.tsx -- uses useProfile hook; renders LoadingIndicator while loading, ErrorMessage on error, otherwise renders ProfileBanner, AboutSection, ContactDetails; assigns section id attributes for anchor navigation (depends on T016, T018, T019, T020, T021, T022)
- [X] T024 [US1] Add HomePage route to frontend/src/App.tsx -- configure React Router with "/" route pointing to HomePage (depends on T023)
- [X] T025 [US1] Create CSS/styles for ProfileBanner, AboutSection, ContactDetails -- responsive layout for desktop (>991px), tablet (768px), mobile (375px) per FR-018; background image swap via CSS media query for mobile (FR-009, RD-003); contact panel transition within 200ms

### Frontend Tests for User Story 1

- [X] T026 [P] [US1] Create ProfileBanner test in frontend/tests/components/ProfileBanner.test.tsx -- verify name, title, headline rendered; verify background image present
- [X] T027 [P] [US1] Create AboutSection test in frontend/tests/components/AboutSection.test.tsx -- verify Markdown rendered to HTML (bold, links, lists); verify links open in new tab
- [X] T028 [P] [US1] Create ContactDetails test in frontend/tests/components/ContactDetails.test.tsx -- verify collapsed by default; verify expand/collapse toggle shows/hides details; verify email, phone, location displayed
- [X] T029 [P] [US1] Create HomePage test in frontend/tests/pages/HomePage.test.tsx -- mock useProfile hook; verify loading state shown; verify error state shown; verify all profile sections rendered on success

**Checkpoint**: Homepage loads and displays the full professional profile. Visitor sees name, title, headline, images, formatted about text, and expandable contact details. User Story 1 is independently functional and testable.

---

## Phase 4: User Story 2 -- Section Navigation & Content Discovery (Priority: P2)

**Goal**: Persistent sidebar navigation on desktop with icons, mobile toggle menu, smooth scroll to sections, and scroll-to-top button.

**Independent Test**: Click each navigation item and verify smooth scrolling to the correct section on both desktop and mobile viewports.

**Requirements**: FR-006, FR-007, FR-008, FR-010, FR-011, FR-012
**Acceptance Scenarios**: US2-1 through US2-6

### Implementation for User Story 2

- [X] T030 [US2] Create Sidebar component in frontend/src/components/layout/Sidebar.tsx -- fixed-position sidebar on desktop (>991px) with navigation items: About, Experience, Skills, Blog, Contact; each item has an icon (FR-010); sidebar displays sidebarImage avatar at top; onClick uses document.getElementById(sectionId)?.scrollIntoView({ behavior: 'smooth' }) per RD-001; sidebar hidden on mobile via CSS (depends on T013)
- [X] T031 [US2] Create MobileMenu component in frontend/src/components/layout/MobileMenu.tsx -- hamburger toggle button visible on mobile (<991px); useState for isMenuOpen; toggles sidebar visibility with CSS transform translateX and 300ms transition per RD-002/SC-006; tapping a nav item closes menu and scrolls to section (depends on T030)
- [X] T032 [US2] Create ScrollToTop component in frontend/src/components/layout/ScrollToTop.tsx -- button appears after scrolling past 600px (tracked via scroll event listener or IntersectionObserver); onClick scrolls to top with window.scrollTo({ top: 0, behavior: 'smooth' }); returns to top within 1 second (SC-008, FR-012)
- [X] T033 [US2] Integrate Sidebar, MobileMenu, and ScrollToTop into HomePage in frontend/src/pages/HomePage.tsx -- add section id anchors for each navigation target (about, experience, skills, blog, contact); render Sidebar and MobileMenu in the page layout; render ScrollToTop globally (depends on T030, T031, T032)
- [X] T034 [US2] Create CSS/styles for Sidebar, MobileMenu, ScrollToTop -- fixed sidebar positioning; mobile off-screen transform with 300ms ease transition; scroll-to-top button positioning and fade-in; responsive breakpoints at 991px

### Frontend Tests for User Story 2

- [X] T035 [P] [US2] Create Sidebar test in frontend/tests/components/Sidebar.test.tsx -- verify navigation items rendered with correct labels; verify icons present; verify sidebarImage displayed; verify click triggers scrollIntoView
- [X] T036 [P] [US2] Create ScrollToTop test in frontend/tests/components/ScrollToTop.test.tsx -- verify button hidden initially; verify button appears on scroll; verify click scrolls to top

**Checkpoint**: Desktop visitors see a fixed sidebar with icons and avatar. Mobile visitors use a hamburger menu that slides in/out within 300ms. Clicking navigation items smoothly scrolls to the target section. Scroll-to-top button appears after scrolling down. User Story 2 is independently functional.

---

## Phase 5: User Story 3 -- Resume/CV Download (Priority: P3)

**Goal**: A visible "Download CV" button on the homepage that links to the resume download endpoint.

**Independent Test**: Click the Download CV button and verify it links to /api/resume.

**Requirements**: FR-013
**Acceptance Scenarios**: US3-1

**Note**: Actual PDF generation is owned by Spec 004 (Skills & Employment). This story only adds the homepage button and link.

### Implementation for User Story 3

- [X] T037 [US3] Add Download CV button to ProfileBanner or AboutSection in frontend/src/components/profile/ProfileBanner.tsx (or AboutSection.tsx) -- renders an anchor/button linking to profile.cvUrl (/api/resume); only rendered if cvUrl is non-null/non-empty; styled consistently with the profile section (depends on T018 or T019)

**Checkpoint**: Download CV button is visible on the homepage and links to the correct endpoint. Button is hidden if cvUrl is not configured. The endpoint itself may return 404 until Spec 004 is implemented.

---

## Phase 6: User Story 4 -- Social Media & External Profile Access (Priority: P4)

**Goal**: Social media links with platform icons displayed on the homepage, opening in new tabs.

**Independent Test**: Click each social media link and verify it opens the correct external profile in a new browser tab.

**Requirements**: FR-014, FR-015
**Acceptance Scenarios**: US4-1 through US4-3

### Implementation for User Story 4

- [X] T038 [US4] Create SocialLinks component in frontend/src/components/profile/SocialLinks.tsx -- renders a list of social media links from profile.socialMediaLinks; displays platform-specific icon for each type (github, linkedin, twitter); each link opens in new tab with target="_blank" rel="noopener noreferrer" (FR-015); only renders links that exist in the array (depends on T014)
- [X] T039 [US4] Integrate SocialLinks into the homepage layout in frontend/src/pages/HomePage.tsx -- render SocialLinks in the profile section and/or sidebar; pass socialMediaLinks from profile data (depends on T038, T023)

### Frontend Tests for User Story 4

- [X] T040 [P] [US4] Create SocialLinks test in frontend/tests/components/SocialLinks.test.tsx -- verify correct icons rendered for each platform type; verify links have target="_blank"; verify correct href URLs; verify handles empty array gracefully

**Checkpoint**: Social media links are displayed with platform icons. Clicking a link opens the external profile in a new tab. All links from the API response are rendered. User Story 4 is independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Analytics integration, error handling improvements, and final quality pass across all user stories.

- [X] T041 [P] Install and configure Google Analytics 4 via react-ga4 in frontend/src/main.tsx -- initialize GA4 with measurement ID from environment variable (per RD-005, FR-017)
- [X] T042 [P] Add GA4 page view tracking to HomePage in frontend/src/pages/HomePage.tsx -- fire page_view event on mount
- [X] T043 [P] Add GA4 custom event tracking across components -- navigate_section event in Sidebar/MobileMenu onClick; download_cv event on CV button click; social_media_click event on SocialLinks click; contact_expand event on ContactDetails toggle; scroll_to_top event on ScrollToTop click (per RD-005)
- [X] T044 [P] Add error boundary or fallback handling for missing/broken images in ProfileBanner -- placeholder image or graceful hiding when image URL returns 404 (edge case from spec.md)
- [X] T045 [P] Verify responsive layout at 320px, 375px, 768px, and >991px viewports -- ensure all content readable, no horizontal scrolling, text wraps/truncates gracefully for long content (edge cases from spec.md, FR-018)
- [X] T046 Run quickstart.md verification checklist (Steps 1-8) -- validate all profile elements, navigation, mobile responsive view, social media links, Download CV button, and error handling scenarios end-to-end

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Skipped -- uses Spec 001 infrastructure
- **Foundational (Phase 2)**: No external dependencies -- can start immediately
- **User Stories (Phases 3-6)**: ALL depend on Foundational phase completion
  - User stories can proceed in parallel if staffed, or sequentially by priority (P1 -> P2 -> P3 -> P4)
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Depends only on Phase 2 completion. No dependencies on other stories.
- **User Story 2 (P2)**: Depends on Phase 2 and Phase 3 (needs HomePage container and section id anchors from US1).
- **User Story 3 (P3)**: Depends on Phase 2 and Phase 3 (adds button to ProfileBanner/AboutSection created in US1).
- **User Story 4 (P4)**: Depends on Phase 2. Can run in parallel with US1 at the component level, but integration into HomePage depends on US1 (T023).

### Within Each Phase

- Backend: Image/ImageFormats records -> Profile/SocialMediaLink documents -> DTOs -> Repository -> Service -> Controller
- Frontend: Types -> API service -> Hook -> Components -> Page integration
- Tests can be written in parallel with implementation (marked [P])

### Parallel Opportunities

- T001, T002, T003 (Image, ImageFormats, SocialMediaLink records) can run in parallel
- T005, T007, T008 (DTO, repositories) can run in parallel
- T011, T012 (backend tests) can run in parallel
- T013, T014 (TypeScript types) can run in parallel
- T017 (install react-markdown) can run in parallel with any other US1 task
- T026, T027, T028, T029 (US1 frontend tests) can run in parallel
- T035, T036 (US2 frontend tests) can run in parallel
- T041, T042, T043, T044, T045 (polish tasks) can run in parallel

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 2: Foundational (backend API + frontend data layer)
2. Complete Phase 3: User Story 1 (profile display, about, contact)
3. **STOP and VALIDATE**: Test via quickstart.md Steps 1-3
4. Deploy/demo -- homepage shows complete professional profile

### Incremental Delivery

1. Phase 2 -> Foundation ready (API serves profile data)
2. Phase 3 (US1) -> Profile display MVP -> Validate Steps 1-3
3. Phase 4 (US2) -> Navigation and sidebar -> Validate Steps 4-5
4. Phase 5 (US3) -> Download CV button -> Validate Step 7
5. Phase 6 (US4) -> Social media links -> Validate Step 6
6. Phase 7 -> Analytics, polish, error handling -> Validate Step 8
7. Each increment adds value without breaking previous stories

---

## Notes

- [P] tasks = different files, no dependencies between them
- [Story] label maps task to specific user story for traceability
- Backend uses Java records (not classes) for all models and DTOs per data-model.md
- Frontend uses TypeScript interfaces (not classes) per plan.md conventions
- Image fields use embedded subdocuments, not separate collections
- The `link` field in SocialMediaLink MongoDB document maps to `url` in the API response DTO
- CV download endpoint (/api/resume) is owned by Spec 004; this spec only adds the button
- Smooth scrolling uses native scrollIntoView per RD-001, not a library
- Mobile menu uses CSS transform transitions per RD-002, not jQuery
- Markdown rendering uses react-markdown per RD-004, not dangerouslySetInnerHTML
- Analytics uses react-ga4 per RD-005
- Commit after each task or logical group
- Stop at any checkpoint to validate the story independently
