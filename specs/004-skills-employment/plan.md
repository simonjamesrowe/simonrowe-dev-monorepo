# Implementation Plan: Skills & Employment

**Branch**: `004-skills-employment` | **Date**: 2026-02-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/004-skills-employment/spec.md`

## Summary

Skills and employment feature providing a skills grid with 9 category groups (71 total skills), an employment timeline with alternating left/right layout (9 entries), bidirectional skill-job navigation, and dynamic PDF resume generation. The backend exposes REST endpoints from Spring Boot 4 backed by MongoDB, with OpenPDF for server-side PDF generation. The frontend renders a skills grid with color-coded proficiency ratings (green >= 9, blue 8.5-8.9, orange < 8.5), a timeline with drawer-based detail views containing tabbed About/Skills interfaces, and skill detail views showing correlated job cards. All four user stories (P1-P4) are delivered incrementally.

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 4.x, Spring Data MongoDB, OpenPDF (PDF generation), React (latest stable), react-markdown (markdown rendering)
**Storage**: MongoDB (skill_groups, skills, jobs collections)
**Testing**: JUnit 5 + Testcontainers with MongoDB (backend), Vitest + React Testing Library (frontend), JaCoCo (coverage)
**Target Platform**: Docker containers on Linux (production via Docker Compose + Pinggy)
**Project Type**: Web application (backend + frontend monorepo)
**Performance Goals**: Skills grid renders within 500ms; PDF generation completes within 5 seconds (SC-004); timeline loads within 500ms
**Constraints**: All quality gates must pass before merge; Testcontainers for integration tests; 9 skill groups, 71 skills, 9 jobs in MongoDB
**Scale/Scope**: 9 skill category groups, 71 individual skills, 9 employment/education entries, 1 PDF template

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Justification |
|---|-----------|--------|---------------|
| I | Monorepo with Separate Containers | PASS | Backend API endpoints added to existing `backend/` subproject. Frontend components added to existing `frontend/` project. No new containers required -- skills, employment, and resume APIs are part of the single backend service. |
| II | Modern Java & React Stack | PASS | Java 25 with Spring Boot 4 for REST controllers and MongoDB repositories. React with TypeScript for skills grid, timeline, and detail components. MongoDB as primary persistence. No CMS -- data managed through MongoDB documents. |
| III | Quality Gates (NON-NEGOTIABLE) | PASS | Google Java Style enforced via Checkstyle. JaCoCo coverage for service and controller layers. Testcontainers with MongoDB for repository and integration tests. Frontend component tests with Vitest + React Testing Library. SonarQube analysis on PR. |
| IV | Observability & Operability | PASS | Existing Spring Boot Actuator and OpenTelemetry instrumentation covers new endpoints automatically. Structured logging for PDF generation timing and error cases. No additional observability infrastructure required. |
| V | Simplicity & Incremental Delivery | PASS | Four user stories delivered incrementally by priority (P1: skills grid, P2: employment timeline, P3: skill-job correlations, P4: PDF resume). No premature abstractions -- direct MongoDB queries, simple REST controllers. Skills and jobs stored as flat MongoDB documents with ObjectId references for correlations. |

## Project Structure

### Documentation (this feature)

```text
specs/004-skills-employment/
├── plan.md              # This file
├── research.md          # Phase 0: PDF library, timeline layout, rating colors
├── data-model.md        # Phase 1: MongoDB document schemas
├── quickstart.md        # Phase 1: Verification steps
├── contracts/           # Phase 1: OpenAPI specifications
│   ├── skills-api.yaml  # Skills endpoints
│   ├── jobs-api.yaml    # Jobs/employment endpoints
│   └── resume-api.yaml  # Resume PDF endpoint
├── checklists/
│   └── requirements.md  # Specification quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/
│   ├── main/java/com/simonrowe/
│   │   ├── skills/
│   │   │   ├── SkillGroup.java              # SkillGroup document entity
│   │   │   ├── Skill.java                   # Embedded skill within group
│   │   │   ├── SkillGroupRepository.java    # Spring Data MongoDB repository
│   │   │   ├── SkillGroupService.java       # Business logic + rating aggregation
│   │   │   └── SkillGroupController.java    # REST endpoints: GET /api/skills, GET /api/skills/{id}
│   │   ├── employment/
│   │   │   ├── Job.java                     # Job document entity
│   │   │   ├── JobRepository.java           # Spring Data MongoDB repository
│   │   │   ├── JobService.java              # Business logic + chronological ordering
│   │   │   └── JobController.java           # REST endpoints: GET /api/jobs, GET /api/jobs/{id}
│   │   └── resume/
│   │       ├── ResumeController.java        # REST endpoint: GET /api/resume (PDF download)
│   │       └── ResumeService.java           # PDF generation with OpenPDF
│   └── test/java/com/simonrowe/
│       ├── skills/
│       │   ├── SkillGroupRepositoryTest.java    # Testcontainers MongoDB integration
│       │   ├── SkillGroupServiceTest.java       # Unit tests for business logic
│       │   └── SkillGroupControllerTest.java    # WebMvcTest for REST layer
│       ├── employment/
│       │   ├── JobRepositoryTest.java           # Testcontainers MongoDB integration
│       │   ├── JobServiceTest.java              # Unit tests for business logic
│       │   └── JobControllerTest.java           # WebMvcTest for REST layer
│       └── resume/
│           ├── ResumeServiceTest.java           # Unit tests for PDF generation
│           └── ResumeControllerTest.java        # WebMvcTest for download endpoint

frontend/
├── src/
│   ├── components/
│   │   ├── skills/
│   │   │   ├── SkillsSection.tsx            # Section wrapper with heading
│   │   │   ├── SkillGroupGrid.tsx           # Grid layout of skill group cards
│   │   │   ├── SkillGroupCard.tsx           # Individual group card (image, name, rating)
│   │   │   ├── SkillGroupDetail.tsx         # Drawer/modal with group description + skills list
│   │   │   ├── SkillCard.tsx                # Individual skill (image, name, rating bar, description)
│   │   │   └── SkillRatingBar.tsx           # Color-coded progress bar (green/blue/orange)
│   │   └── employment/
│   │       ├── ExperienceSection.tsx         # Section wrapper with heading
│   │       ├── Timeline.tsx                  # Alternating left/right timeline layout
│   │       ├── TimelineEntry.tsx             # Single entry (company image, title, dates)
│   │       ├── JobDetail.tsx                 # Drawer with tabbed interface
│   │       ├── JobAboutTab.tsx               # Markdown description, company info, location
│   │       └── JobSkillsTab.tsx              # Grid of skill cards used in position
│   ├── services/
│   │   ├── skillsApi.ts                     # API client: fetchSkillGroups(), fetchSkillGroup(id)
│   │   └── jobsApi.ts                       # API client: fetchJobs(), fetchJob(id)
│   └── types/
│       ├── skill.ts                         # TypeScript interfaces: ISkillGroup, ISkill
│       └── job.ts                           # TypeScript interfaces: IJob
└── tests/
    ├── components/
    │   ├── skills/
    │   │   ├── SkillGroupGrid.test.tsx      # Grid rendering, card display
    │   │   ├── SkillGroupDetail.test.tsx    # Detail drawer, skill list rendering
    │   │   └── SkillRatingBar.test.tsx      # Color coding thresholds
    │   └── employment/
    │       ├── Timeline.test.tsx            # Alternating layout, entry rendering
    │       └── JobDetail.test.tsx           # Tabbed interface, markdown rendering
    └── services/
        ├── skillsApi.test.ts               # API client mocking
        └── jobsApi.test.ts                 # API client mocking
```

**Structure Decision**: Option 2 (Web application) continues from Spec 001 infrastructure. New domain packages (`skills`, `employment`, `resume`) are added under the existing `backend/src/main/java/com/simonrowe/` package. New component directories (`skills/`, `employment/`) are added under the existing `frontend/src/components/`. No new Gradle subprojects or npm packages are needed -- all code integrates into the existing backend and frontend projects.

## Complexity Tracking

No constitution violations. All principles pass without exception.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| *None* | *N/A* | *N/A* |
