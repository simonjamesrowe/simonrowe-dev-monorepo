# Tasks: Skills & Employment

**Feature**: 004-skills-employment | **Date**: 2026-02-21 | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

## Phase 2: Foundational (Backend Models, Repositories, Services, Controllers, Frontend Types & API Services)

### Backend: Skills Domain

- [ ] T001 [P1] Create `Skill` embedded model with fields: id, name, rating, displayOrder, description, image — `backend/src/main/java/com/simonrowe/skills/Skill.java`
- [ ] T002 [P1] Create `SkillGroup` document model with fields: id, name, description, rating, displayOrder, image, skills (embedded `List<Skill>`) — `backend/src/main/java/com/simonrowe/skills/SkillGroup.java`
- [ ] T003 [P1] Create `Image` shared model with fields: url, name, width, height, mime, formats (containing thumbnail, small, medium, large sub-objects) — `backend/src/main/java/com/simonrowe/common/Image.java`
- [ ] T004 [P1] Create `ImageFormat` shared model with fields: url, width, height — `backend/src/main/java/com/simonrowe/common/ImageFormat.java`
- [ ] T005 [P1] Create `SkillGroupRepository` extending `MongoRepository<SkillGroup, String>` with query method `findAllByOrderByDisplayOrderAsc()` — `backend/src/main/java/com/simonrowe/skills/SkillGroupRepository.java`
- [ ] T006 [P1] Create `SkillGroupService` with methods: `getAllSkillGroups()` returning all groups sorted by displayOrder, `getSkillGroupById(String id)` returning a single group — `backend/src/main/java/com/simonrowe/skills/SkillGroupService.java`
- [ ] T007 [P1] Create `SkillGroupController` with `GET /api/skills` (returns all skill groups) and `GET /api/skills/{id}` (returns skill group detail) — `backend/src/main/java/com/simonrowe/skills/SkillGroupController.java`

### Backend: Employment Domain

- [ ] T008 [P2] Create `Job` document model with fields: id, title, company, companyUrl, companyImage, startDate, endDate (nullable), location, shortDescription, longDescription, isEducation, includeOnResume, skills (list of skill ID strings) — `backend/src/main/java/com/simonrowe/employment/Job.java`
- [ ] T009 [P2] Create `JobRepository` extending `MongoRepository<Job, String>` with query methods: `findAllByOrderByStartDateDesc()`, `findBySkillsContaining(String skillId)`, `findByIncludeOnResumeTrueOrderByStartDateDesc()` — `backend/src/main/java/com/simonrowe/employment/JobRepository.java`
- [ ] T010 [P2] Create `JobService` with methods: `getAllJobs()` returning all jobs sorted by startDate descending, `getJobById(String id)` returning a single job with resolved skill objects — `backend/src/main/java/com/simonrowe/employment/JobService.java`
- [ ] T011 [P2] Create `JobController` with `GET /api/jobs` (returns all jobs) and `GET /api/jobs/{id}` (returns job detail with resolved skills) — `backend/src/main/java/com/simonrowe/employment/JobController.java`

### Backend: API Response DTOs

- [ ] T012 [P1] Create `SkillGroupSummaryDto` DTO matching `SkillGroupSummary` schema from skills-api.yaml (id, name, rating, displayOrder, description, image, skills as `List<SkillSummaryDto>`) — `backend/src/main/java/com/simonrowe/skills/dto/SkillGroupSummaryDto.java`
- [ ] T013 [P1] Create `SkillSummaryDto` DTO matching `SkillSummary` schema (id, name, rating, displayOrder, description, image) — `backend/src/main/java/com/simonrowe/skills/dto/SkillSummaryDto.java`
- [ ] T014 [P3] Create `SkillGroupDetailDto` DTO matching `SkillGroupDetail` schema (same as summary but skills include job correlations as `List<SkillDetailDto>`) — `backend/src/main/java/com/simonrowe/skills/dto/SkillGroupDetailDto.java`
- [ ] T015 [P3] Create `SkillDetailDto` DTO matching `SkillDetail` schema (extends SkillSummary with `List<JobReferenceDto>` for correlated jobs) — `backend/src/main/java/com/simonrowe/skills/dto/SkillDetailDto.java`
- [ ] T016 [P3] Create `JobReferenceDto` DTO matching `JobReference` schema (id, title, company, startDate, endDate, companyImage) — `backend/src/main/java/com/simonrowe/skills/dto/JobReferenceDto.java`
- [ ] T017 [P2] Create `JobSummaryDto` DTO matching `JobSummary` schema from jobs-api.yaml (id, title, company, companyUrl, companyImage, startDate, endDate, location, shortDescription, isEducation, includeOnResume) — `backend/src/main/java/com/simonrowe/employment/dto/JobSummaryDto.java`
- [ ] T018 [P2] Create `JobDetailDto` DTO matching `JobDetail` schema (all fields plus longDescription and resolved `List<SkillReferenceDto>`) — `backend/src/main/java/com/simonrowe/employment/dto/JobDetailDto.java`
- [ ] T019 [P3] Create `SkillReferenceDto` DTO matching `SkillReference` schema (id, name, rating, image, skillGroupId) for bidirectional navigation — `backend/src/main/java/com/simonrowe/employment/dto/SkillReferenceDto.java`
- [ ] T020 [P1] Create `ErrorResponseDto` DTO matching `ErrorResponse` schema (message, status, timestamp) — `backend/src/main/java/com/simonrowe/common/ErrorResponseDto.java`

### Backend: Error Handling

- [ ] T021 [P1] Create `ResourceNotFoundException` extending `RuntimeException` for 404 responses when skill group or job not found — `backend/src/main/java/com/simonrowe/common/ResourceNotFoundException.java`
- [ ] T022 [P1] Create `GlobalExceptionHandler` with `@ControllerAdvice` mapping `ResourceNotFoundException` to 404 and generic exceptions to 500 using `ErrorResponseDto` — `backend/src/main/java/com/simonrowe/common/GlobalExceptionHandler.java`

### Frontend: Types

- [ ] T023 [P1] Create `IImage` and `IImageFormat` TypeScript interfaces matching the Image/ImageFormat schemas — `frontend/src/types/image.ts`
- [ ] T024 [P1] Create `ISkill`, `ISkillGroup`, `ISkillDetail`, `IJobReference` TypeScript interfaces matching skills-api.yaml schemas — `frontend/src/types/skill.ts`
- [ ] T025 [P2] Create `IJob`, `IJobDetail`, `ISkillReference` TypeScript interfaces matching jobs-api.yaml schemas — `frontend/src/types/job.ts`

### Frontend: API Services

- [ ] T026 [P1] Create `skillsApi` service with `fetchSkillGroups(): Promise<ISkillGroup[]>` and `fetchSkillGroup(id: string): Promise<ISkillGroupDetail>` calling `GET /api/skills` and `GET /api/skills/{id}` — `frontend/src/services/skillsApi.ts`
- [ ] T027 [P2] Create `jobsApi` service with `fetchJobs(): Promise<IJob[]>` and `fetchJob(id: string): Promise<IJobDetail>` calling `GET /api/jobs` and `GET /api/jobs/{id}` — `frontend/src/services/jobsApi.ts`

---

## Phase 3: US1 — Skills by Category (P1)

### Backend: Skills Endpoints (Full Implementation)

- [ ] T028 [P1] [US1] Implement `SkillGroupService.getAllSkillGroups()` — fetch all groups sorted by displayOrder, map to `SkillGroupSummaryDto` with embedded skills sorted by displayOrder — `backend/src/main/java/com/simonrowe/skills/SkillGroupService.java`
- [ ] T029 [P1] [US1] Implement `SkillGroupService.getSkillGroupById(String id)` — fetch single group, throw `ResourceNotFoundException` if not found, return `SkillGroupSummaryDto` (without job correlations, correlations added in Phase 5) — `backend/src/main/java/com/simonrowe/skills/SkillGroupService.java`
- [ ] T030 [P1] [US1] Implement `SkillGroupController.getAllSkillGroups()` — `@GetMapping("/api/skills")` returning `List<SkillGroupSummaryDto>` with 200 status — `backend/src/main/java/com/simonrowe/skills/SkillGroupController.java`
- [ ] T031 [P1] [US1] Implement `SkillGroupController.getSkillGroupById(String id)` — `@GetMapping("/api/skills/{id}")` returning `SkillGroupSummaryDto` with 200 or 404 — `backend/src/main/java/com/simonrowe/skills/SkillGroupController.java`

### Backend: Skills Tests

- [ ] T032 [P1] [US1] Write `SkillGroupRepositoryTest` with Testcontainers MongoDB — test `findAllByOrderByDisplayOrderAsc()` returns groups in correct order, test data with 9 groups and 71 skills — `backend/src/test/java/com/simonrowe/skills/SkillGroupRepositoryTest.java`
- [ ] T033 [P1] [US1] Write `SkillGroupServiceTest` unit tests — test `getAllSkillGroups()` returns sorted groups, test `getSkillGroupById()` returns group and throws for unknown ID — `backend/src/test/java/com/simonrowe/skills/SkillGroupServiceTest.java`
- [ ] T034 [P1] [US1] Write `SkillGroupControllerTest` with `@WebMvcTest` — test `GET /api/skills` returns 200 with array, test `GET /api/skills/{id}` returns 200 for valid ID and 404 for invalid ID — `backend/src/test/java/com/simonrowe/skills/SkillGroupControllerTest.java`

### Frontend: Skills Components

- [ ] T035 [P1] [US1] Create `SkillRatingBar` component — color-coded progress bar: green for rating >= 9, blue for 8.5-8.9, orange for < 8.5; width proportional to rating (rating * 10%); ARIA label for accessibility — `frontend/src/components/skills/SkillRatingBar.tsx`
- [ ] T036 [P1] [US1] Create `SkillGroupCard` component — displays skill group image (thumbnail), category name, aggregated rating via `SkillRatingBar`; onClick handler to navigate to `/skills-groups/{id}` — `frontend/src/components/skills/SkillGroupCard.tsx`
- [ ] T037 [P1] [US1] Create `SkillGroupGrid` component — fetches skill groups via `skillsApi.fetchSkillGroups()`, renders responsive grid of `SkillGroupCard` components, handles loading and error states — `frontend/src/components/skills/SkillGroupGrid.tsx`
- [ ] T038 [P1] [US1] Create `SkillCard` component — displays individual skill with image, name, `SkillRatingBar`, and description text; used within `SkillGroupDetail` — `frontend/src/components/skills/SkillCard.tsx`
- [ ] T039 [P1] [US1] Create `SkillGroupDetail` component — right-side drawer/modal; fetches skill group detail via `skillsApi.fetchSkillGroup(id)`; displays group name, description, image, and list of `SkillCard` components; close button navigates to `/` — `frontend/src/components/skills/SkillGroupDetail.tsx`
- [ ] T040 [P1] [US1] Create `SkillsSection` component — section wrapper with "My Skills" heading; renders `SkillGroupGrid`; positioned within the homepage layout — `frontend/src/components/skills/SkillsSection.tsx`

### Frontend: Skills Route Integration

- [ ] T041 [P1] [US1] Add route `/skills-groups/:groupId` to open `SkillGroupDetail` drawer — use `useParams` to extract groupId; drawer visibility driven by URL presence — `frontend/src/App.tsx` (or route configuration file)

### Frontend: Skills Tests

- [ ] T042 [P1] [US1] Write `SkillRatingBar.test.tsx` — test green color for rating 9.0, 9.5, 10; test blue color for rating 8.5, 8.7; test orange color for rating 7.0, 8.4; test width calculation; test edge case with missing/invalid rating — `frontend/tests/components/skills/SkillRatingBar.test.tsx`
- [ ] T043 [P1] [US1] Write `SkillGroupGrid.test.tsx` — test renders 9 group cards; test cards displayed in displayOrder; test loading state; test click navigates to skill group detail — `frontend/tests/components/skills/SkillGroupGrid.test.tsx`
- [ ] T044 [P1] [US1] Write `SkillGroupDetail.test.tsx` — test drawer opens with group data; test lists all skills within group; test skill rating color coding; test close button returns to grid; test handles null images — `frontend/tests/components/skills/SkillGroupDetail.test.tsx`
- [ ] T045 [P1] [US1] Write `skillsApi.test.ts` — test `fetchSkillGroups()` calls `GET /api/skills` and returns parsed response; test `fetchSkillGroup(id)` calls `GET /api/skills/{id}` and returns parsed response; test error handling for 404 and 500 — `frontend/tests/services/skillsApi.test.ts`

---

## Phase 4: US2 — Employment Timeline (P2)

### Backend: Jobs Endpoints (Full Implementation)

- [ ] T046 [P2] [US2] Implement `JobService.getAllJobs()` — fetch all jobs sorted by startDate descending, map to `JobSummaryDto` (no longDescription, no resolved skills) — `backend/src/main/java/com/simonrowe/employment/JobService.java`
- [ ] T047 [P2] [US2] Implement `JobService.getJobById(String id)` — fetch single job, throw `ResourceNotFoundException` if not found, resolve skill ID references to full `SkillReferenceDto` objects by querying `SkillGroupRepository`, return `JobDetailDto` — `backend/src/main/java/com/simonrowe/employment/JobService.java`
- [ ] T048 [P2] [US2] Implement `JobController.getAllJobs()` — `@GetMapping("/api/jobs")` returning `List<JobSummaryDto>` with 200 status — `backend/src/main/java/com/simonrowe/employment/JobController.java`
- [ ] T049 [P2] [US2] Implement `JobController.getJobById(String id)` — `@GetMapping("/api/jobs/{id}")` returning `JobDetailDto` with 200 or 404 — `backend/src/main/java/com/simonrowe/employment/JobController.java`

### Backend: Jobs Tests

- [ ] T050 [P2] [US2] Write `JobRepositoryTest` with Testcontainers MongoDB — test `findAllByOrderByStartDateDesc()` returns jobs in reverse chronological order; test `findBySkillsContaining()` returns correct jobs for a skill ID — `backend/src/test/java/com/simonrowe/employment/JobRepositoryTest.java`
- [ ] T051 [P2] [US2] Write `JobServiceTest` unit tests — test `getAllJobs()` returns sorted jobs; test `getJobById()` returns job with resolved skills and throws for unknown ID; test null endDate handling — `backend/src/test/java/com/simonrowe/employment/JobServiceTest.java`
- [ ] T052 [P2] [US2] Write `JobControllerTest` with `@WebMvcTest` — test `GET /api/jobs` returns 200 with array; test `GET /api/jobs/{id}` returns 200 with resolved skills for valid ID and 404 for invalid ID — `backend/src/test/java/com/simonrowe/employment/JobControllerTest.java`

### Frontend: Employment Components

- [ ] T053 [P2] [US2] Create `TimelineEntry` component — displays company image, job title, start/end dates (format: "MMM YYYY"), "Present" for null endDate; education vs employment visual indicator; onClick navigates to `/jobs/{id}`; accepts `side` prop for left/right positioning — `frontend/src/components/employment/TimelineEntry.tsx`
- [ ] T054 [P2] [US2] Create `Timeline` component — fetches jobs via `jobsApi.fetchJobs()`; renders entries in CSS Grid with alternating left/right pattern (pairs of 2: indices 0,1 left; 2,3 right; 4,5 left; etc.); single column on mobile (<768px); handles loading and error states — `frontend/src/components/employment/Timeline.tsx`
- [ ] T055 [P2] [US2] Create `JobAboutTab` component — renders job longDescription as markdown via `react-markdown`; displays company name, location, and clickable company website link — `frontend/src/components/employment/JobAboutTab.tsx`
- [ ] T056 [P2] [US2] Create `JobSkillsTab` component — renders grid of skill cards for the job's resolved skills; each skill shows name, rating, and image; onClick navigates to `/skills-groups/{skillGroupId}#{skillId}` for bidirectional navigation — `frontend/src/components/employment/JobSkillsTab.tsx`
- [ ] T057 [P2] [US2] Create `JobDetail` component — right-side drawer; fetches job detail via `jobsApi.fetchJob(id)`; renders tabbed interface with `JobAboutTab` and `JobSkillsTab`; close button navigates to `/` — `frontend/src/components/employment/JobDetail.tsx`
- [ ] T058 [P2] [US2] Create `ExperienceSection` component — section wrapper with "My Experience" heading; renders `Timeline`; positioned within the homepage layout — `frontend/src/components/employment/ExperienceSection.tsx`

### Frontend: Employment Route Integration

- [ ] T059 [P2] [US2] Add route `/jobs/:jobId` to open `JobDetail` drawer — use `useParams` to extract jobId; drawer visibility driven by URL presence — `frontend/src/App.tsx` (or route configuration file)

### Frontend: Markdown Rendering

- [ ] T060 [P2] [US2] Add `react-markdown` dependency to `frontend/package.json` and configure for rendering job longDescription in `JobAboutTab` — `frontend/package.json`

### Frontend: Employment Tests

- [ ] T061 [P2] [US2] Write `Timeline.test.tsx` — test renders 9 entries; test alternating left/right layout pattern; test date formatting (MMM YYYY); test "Present" for null endDate; test education entries have visual indicator — `frontend/tests/components/employment/Timeline.test.tsx`
- [ ] T062 [P2] [US2] Write `JobDetail.test.tsx` — test drawer opens with job data; test About tab renders markdown content; test Skills tab shows resolved skills; test tab switching; test close button navigates away — `frontend/tests/components/employment/JobDetail.test.tsx`
- [ ] T063 [P2] [US2] Write `jobsApi.test.ts` — test `fetchJobs()` calls `GET /api/jobs`; test `fetchJob(id)` calls `GET /api/jobs/{id}`; test error handling for 404 and 500 — `frontend/tests/services/jobsApi.test.ts`

---

## Phase 5: US3 — Skill-Job Correlations (P3)

### Backend: Correlation Resolution

- [ ] T064 [P3] [US3] Enhance `SkillGroupService.getSkillGroupById()` to return `SkillGroupDetailDto` — for each skill in the group, query `JobRepository.findBySkillsContaining(skillId)` to find correlated jobs; map to `SkillDetailDto` with `List<JobReferenceDto>` sorted chronologically (startDate ascending) — `backend/src/main/java/com/simonrowe/skills/SkillGroupService.java`
- [ ] T065 [P3] [US3] Update `SkillGroupController.getSkillGroupById()` to return `SkillGroupDetailDto` (with job correlations) instead of `SkillGroupSummaryDto` — `backend/src/main/java/com/simonrowe/skills/SkillGroupController.java`
- [ ] T066 [P3] [US3] Ensure `JobService.getJobById()` resolves skill references with `skillGroupId` populated in each `SkillReferenceDto` — needed for frontend bidirectional navigation from job Skills tab to skill group detail — `backend/src/main/java/com/simonrowe/employment/JobService.java`

### Backend: Correlation Tests

- [ ] T067 [P3] [US3] Update `SkillGroupServiceTest` — add tests for job correlation resolution: verify `getSkillGroupById()` returns skills with non-empty job lists; verify jobs are sorted chronologically; verify skills used in zero jobs return empty job list — `backend/src/test/java/com/simonrowe/skills/SkillGroupServiceTest.java`
- [ ] T068 [P3] [US3] Update `SkillGroupControllerTest` — add test for `GET /api/skills/{id}` returning job correlations in response; verify `SkillGroupDetail` schema — `backend/src/test/java/com/simonrowe/skills/SkillGroupControllerTest.java`
- [ ] T069 [P3] [US3] Update `JobServiceTest` — add test verifying resolved skills include `skillGroupId` field for bidirectional navigation — `backend/src/test/java/com/simonrowe/employment/JobServiceTest.java`

### Frontend: Skill-Job Correlation Display

- [ ] T070 [P3] [US3] Add job cards within `SkillGroupDetail` — for each skill that has correlated jobs, display `JobReferenceCard` components below the skill's rating and description; each card shows job title, company, dates; onClick navigates to `/jobs/{jobId}` — `frontend/src/components/skills/SkillGroupDetail.tsx`
- [ ] T071 [P3] [US3] Create `JobReferenceCard` component — lightweight card displaying job title, company name, date range, and company image; used within skill detail to show correlated jobs; onClick navigates to job detail — `frontend/src/components/skills/JobReferenceCard.tsx`
- [ ] T072 [P3] [US3] Enhance `JobSkillsTab` with navigation links — each skill card in the job's Skills tab links to `/skills-groups/{skillGroupId}#{skillId}` enabling bidirectional navigation from job to skill group detail — `frontend/src/components/employment/JobSkillsTab.tsx`

### Frontend: Bidirectional Navigation

- [ ] T073 [P3] [US3] Implement drawer swap navigation — when navigating from a skill group drawer to a job (click job card), close skill drawer and open job drawer; when navigating from a job drawer to a skill group (click skill card), close job drawer and open skill group drawer; URL is single source of truth — `frontend/src/App.tsx` (or route configuration file)
- [ ] T074 [P3] [US3] Handle hash-based skill scrolling — when navigating to `/skills-groups/{groupId}#{skillId}`, auto-scroll the skill group detail drawer to the specific skill element identified by the hash fragment — `frontend/src/components/skills/SkillGroupDetail.tsx`

### Frontend: Correlation Tests

- [ ] T075 [P3] [US3] Update `SkillGroupDetail.test.tsx` — add tests for job correlation cards appearing below skills; test click on job card navigates to job detail; test skills with zero correlated jobs show no cards — `frontend/tests/components/skills/SkillGroupDetail.test.tsx`
- [ ] T076 [P3] [US3] Update `JobDetail.test.tsx` — add test for clicking a skill in the Skills tab navigates to the correct skill group detail URL with hash fragment — `frontend/tests/components/employment/JobDetail.test.tsx`
- [ ] T077 [P3] [US3] Write bidirectional navigation integration test — test full cycle: open skill group -> click job card -> job drawer opens -> click skill in Skills tab -> skill group drawer opens with correct scroll position — `frontend/tests/integration/bidirectionalNavigation.test.tsx`

---

## Phase 6: US4 — PDF Resume Generation (P4)

### Backend: Dependencies

- [ ] T078 [P4] [US4] Add OpenPDF dependency (`com.github.librepdf:openpdf:2.0.3`) to `backend/build.gradle.kts` — `backend/build.gradle.kts`
- [ ] T079 [P4] [US4] Add commonmark-java dependency (`org.commonmark:commonmark:0.24.0`) to `backend/build.gradle.kts` for markdown-to-PDF rendering — `backend/build.gradle.kts`

### Backend: Resume Service

- [ ] T080 [P4] [US4] Create `ResumeService` — assembles `ResumeData` by fetching: profile from `profiles` collection (Spec 002), employment jobs (`includeOnResume=true`, `isEducation=false`, sorted by startDate descending), education entries (`isEducation=true`, sorted by startDate descending), and all skill groups with nested skills — `backend/src/main/java/com/simonrowe/resume/ResumeService.java`
- [ ] T081 [P4] [US4] Implement PDF generation in `ResumeService.generateResumePdf()` — create `Document` with `PdfWriter`, configure page size (A4), margins; build two-column layout using `PdfPTable` with 30%/70% column widths — `backend/src/main/java/com/simonrowe/resume/ResumeService.java`
- [ ] T082 [P4] [US4] Implement PDF sidebar (left column) — render profile name, title, contact info (email, phone, location), professional links (LinkedIn, GitHub, website), and skill groups with star ratings (filled/empty Unicode stars, 0-10 scale, color-coded by rating threshold) — `backend/src/main/java/com/simonrowe/resume/ResumeService.java`
- [ ] T083 [P4] [US4] Implement PDF main content (right column) — render employment section header, then for each job: title, company, date range ("MMM YYYY - MMM YYYY" or "MMM YYYY - Present"), location, and longDescription converted from markdown to styled PDF text — `backend/src/main/java/com/simonrowe/resume/ResumeService.java`
- [ ] T084 [P4] [US4] Implement PDF education section — render education section header, then for each education entry: title, institution name, date range, location, and description — `backend/src/main/java/com/simonrowe/resume/ResumeService.java`
- [ ] T085 [P4] [US4] Implement markdown-to-PDF converter using commonmark-java — parse markdown AST, map `Heading` to bold `Paragraph`, `BulletList` to indented items with bullet characters, `Emphasis` to italic `Chunk`, `StrongEmphasis` to bold `Chunk`, `Link` to colored underlined `Chunk`, `Paragraph` to standard `Paragraph` — `backend/src/main/java/com/simonrowe/resume/MarkdownPdfRenderer.java`

### Backend: Resume Controller

- [ ] T086 [P4] [US4] Create `ResumeController` with `GET /api/resume` — calls `ResumeService.generateResumePdf()`, returns byte array with `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="simon-rowe-resume.pdf"`, `Cache-Control: no-cache, no-store, must-revalidate` — `backend/src/main/java/com/simonrowe/resume/ResumeController.java`

### Backend: Resume Tests

- [ ] T087 [P4] [US4] Write `ResumeServiceTest` unit tests — test `generateResumePdf()` returns non-empty byte array; test output is valid PDF (starts with `%PDF`); test only `includeOnResume=true` jobs appear; test education entries appear in dedicated section; test current positions show "Present"; test skills organized by category with star ratings — `backend/src/test/java/com/simonrowe/resume/ResumeServiceTest.java`
- [ ] T088 [P4] [US4] Write `MarkdownPdfRendererTest` unit tests — test bold text renders as bold chunks; test italic renders as italic; test bullet lists render with indentation; test links render with URL; test handles special characters; test empty markdown returns empty content — `backend/src/test/java/com/simonrowe/resume/MarkdownPdfRendererTest.java`
- [ ] T089 [P4] [US4] Write `ResumeControllerTest` with `@WebMvcTest` — test `GET /api/resume` returns 200 with `application/pdf` content type; test `Content-Disposition` header is set; test `Cache-Control` header is set; test 500 on generation failure — `backend/src/test/java/com/simonrowe/resume/ResumeControllerTest.java`

### Frontend: Resume Download

- [ ] T090 [P4] [US4] Add "Download Resume" button/link to the homepage — triggers `GET /api/resume` and initiates browser file download; button placed in a visible location (e.g., header, profile section, or both skills and employment sections) — `frontend/src/components/common/ResumeDownloadButton.tsx`

---

## Phase 7: Polish & Edge Cases

### Edge Case Handling

- [ ] T091 [P1] Handle empty skill groups — if a skill group contains zero skills, display the group card with an appropriate indication (e.g., "No skills listed"); ensure the detail view gracefully shows an empty state — `frontend/src/components/skills/SkillGroupDetail.tsx`
- [ ] T092 [P1] Handle missing/null images — when `image` is null on a skill group or skill, display a placeholder image or hide the image container; prevent broken image icons — `frontend/src/components/skills/SkillGroupCard.tsx`, `frontend/src/components/skills/SkillCard.tsx`
- [ ] T093 [P2] Handle jobs with no associated skills — when a job has an empty skills array, the Skills tab should display an appropriate empty state message (e.g., "No skills recorded for this position") — `frontend/src/components/employment/JobSkillsTab.tsx`
- [ ] T094 [P3] Handle skills with zero job correlations — when a skill has no correlated jobs, display an appropriate empty state within the skill detail (e.g., "Not linked to any positions") instead of an empty job card section — `frontend/src/components/skills/SkillGroupDetail.tsx`
- [ ] T095 [P1] Handle invalid or missing skill ratings — when rating is null, undefined, or outside 0-10 range, default to 0 with orange color coding; add defensive checks in `SkillRatingBar` — `frontend/src/components/skills/SkillRatingBar.tsx`
- [ ] T096 [P4] Handle long job descriptions in PDF — implement pagination within `ResumeService` to prevent content overflow; ensure markdown content wraps correctly and page breaks occur between logical sections rather than mid-paragraph — `backend/src/main/java/com/simonrowe/resume/ResumeService.java`
- [ ] T097 [P4] Handle special characters in PDF — test and fix rendering of special characters (em-dash, curly quotes, ampersands, accented characters) in markdown-to-PDF conversion — `backend/src/main/java/com/simonrowe/resume/MarkdownPdfRenderer.java`

### Performance & UX Polish

- [ ] T098 [P1] Add loading skeletons for skill group grid — display placeholder skeleton cards while `fetchSkillGroups()` is in flight — `frontend/src/components/skills/SkillGroupGrid.tsx`
- [ ] T099 [P2] Add loading skeletons for employment timeline — display placeholder skeleton entries while `fetchJobs()` is in flight — `frontend/src/components/employment/Timeline.tsx`
- [ ] T100 [P2] Add drawer loading state for `JobDetail` — show spinner or skeleton while `fetchJob(id)` is loading — `frontend/src/components/employment/JobDetail.tsx`
- [ ] T101 [P1] Add drawer loading state for `SkillGroupDetail` — show spinner or skeleton while `fetchSkillGroup(id)` is loading — `frontend/src/components/skills/SkillGroupDetail.tsx`

### Responsive Design

- [ ] T102 [P1] Ensure skills grid is responsive — 3 columns on desktop, 2 on tablet, 1 on mobile; skill group cards scale appropriately — `frontend/src/components/skills/SkillGroupGrid.tsx`
- [ ] T103 [P2] Ensure timeline collapses to single column on mobile — alternating left/right pattern is desktop only; mobile (<768px) renders single column with dates above headline — `frontend/src/components/employment/Timeline.tsx`
- [ ] T104 [P1] [P2] Ensure drawers are responsive — skill group detail and job detail drawers are full-width on mobile; appropriate width on desktop (e.g., 60-70% viewport width) — `frontend/src/components/skills/SkillGroupDetail.tsx`, `frontend/src/components/employment/JobDetail.tsx`

### Accessibility

- [ ] T105 [P1] Add ARIA attributes to `SkillRatingBar` — `role="progressbar"`, `aria-valuenow`, `aria-valuemin="0"`, `aria-valuemax="10"`, `aria-label` with skill name and rating value — `frontend/src/components/skills/SkillRatingBar.tsx`
- [ ] T106 [P2] Add ARIA attributes to timeline — `role="list"` on timeline container, `role="listitem"` on entries, `aria-label` on clickable entries with job title and company — `frontend/src/components/employment/Timeline.tsx`
- [ ] T107 [P1] [P2] Add keyboard navigation for drawers — Escape key closes drawer, focus trap within open drawer, focus returns to trigger element on close — `frontend/src/components/skills/SkillGroupDetail.tsx`, `frontend/src/components/employment/JobDetail.tsx`
