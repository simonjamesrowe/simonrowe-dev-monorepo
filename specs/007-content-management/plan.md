# Implementation Plan: Content Management System

**Branch**: `007-content-management` | **Date**: 2026-02-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/007-content-management/spec.md`

## Summary

Auth0-protected admin CRUD system for all website content types (blogs, jobs, skills, skill groups, profile, social media links, tags, tour steps). The backend exposes Spring Boot REST endpoints secured with Auth0 JWT validation. The frontend provides a React admin dashboard with Markdown editing (live preview), image upload with automatic variant generation, drag-and-drop ordering for skills and skill groups, and a media library. A one-time data migration service imports existing content from the Strapi MongoDB backup (18 blog posts, 9 jobs, 71 skills, 9 skill groups, 26 tags, 1 profile, 4 social media links, 7 tour steps, and 161 media assets).

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 4, Spring Security 6 (OAuth2 Resource Server), Spring Data MongoDB, Auth0 React SDK (`@auth0/auth0-react`), MDXEditor (Markdown editing), Thumbnailator (image resizing)
**Storage**: MongoDB (primary persistence), local filesystem or GridFS (media assets)
**Testing**: JUnit 5 + Testcontainers (backend integration), Vitest + React Testing Library (frontend), Spring Security test support for JWT mocking
**Target Platform**: Docker containers on Linux (production via Docker Compose + Pinggy)
**Project Type**: Web application (backend + frontend monorepo)
**Performance Goals**: Image variant generation completes within 10 seconds; admin UI responses under 2 seconds; validation feedback within 2 seconds
**Constraints**: Auth0 as sole auth provider; no self-registration; single admin profile entity; all quality gates must pass
**Scale/Scope**: 8 content types, ~10 admin pages, ~16 REST endpoints, ~300 existing records to migrate

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Justification |
|---|-----------|--------|---------------|
| I | Monorepo with Separate Containers | PASS | All admin backend code lives in `backend/` alongside existing API code. Admin frontend pages live in `frontend/` alongside existing public pages. No new containers required -- admin endpoints are part of the existing backend service, admin pages are part of the existing frontend application. |
| II | Modern Java & React Stack | PASS | Java 25, Spring Boot 4, Spring Security 6 with OAuth2 Resource Server for JWT validation. Auth0 as sole authentication provider with no self-registration. MongoDB for persistence. React with Auth0 React SDK for frontend authentication. |
| III | Quality Gates (NON-NEGOTIABLE) | PASS | Admin controllers tested with Testcontainers and Spring Security test JWT mocking. Frontend admin components tested with Vitest and React Testing Library. JaCoCo coverage enforced. Google Java Style via Checkstyle. SonarQube analysis on PR. |
| IV | Observability & Operability | PASS | Admin endpoints exposed through existing actuator/metrics infrastructure. Structured logging for all admin operations (create, update, delete). OpenTelemetry tracing covers admin API calls. |
| V | Simplicity & Incremental Delivery | PASS | Four user stories delivered incrementally by priority (P1: Blog CRUD, P2: Jobs, P3: Skills, P4: Profile/Media). No premature abstractions -- each admin controller directly uses Spring Data MongoDB repositories. Image processing uses a simple synchronous approach with Thumbnailator (async only if needed based on measured performance). |

## Project Structure

### Documentation (this feature)

```text
specs/007-content-management/
├── plan.md              # This file
├── research.md          # Phase 0: Technology research and decisions
├── data-model.md        # Phase 1: MongoDB document schemas
├── quickstart.md        # Phase 1: Developer quickstart guide
├── contracts/           # Phase 1: API contracts
│   ├── admin-api.yaml   # Admin CRUD endpoints OpenAPI spec
│   └── media-api.yaml   # Media upload/library endpoints OpenAPI spec
├── checklists/
│   └── requirements.md  # Specification quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/
│   ├── auth/
│   │   ├── SecurityConfig.java          # Spring Security OAuth2 resource server config
│   │   └── Auth0JwtDecoder.java         # Auth0-specific JWT decoder and audience validation
│   ├── admin/
│   │   ├── AdminBlogController.java     # Blog CRUD endpoints (POST/PUT/GET/DELETE /api/admin/blogs)
│   │   ├── AdminJobController.java      # Job CRUD endpoints (POST/PUT/GET/DELETE /api/admin/jobs)
│   │   ├── AdminSkillController.java    # Skill CRUD + reorder (POST/PUT/GET/DELETE/PATCH /api/admin/skills)
│   │   ├── AdminSkillGroupController.java # Skill group CRUD + reorder
│   │   ├── AdminProfileController.java  # Profile GET/PUT (single entity)
│   │   ├── AdminSocialMediaController.java # Social media link CRUD
│   │   ├── AdminTagController.java      # Tag CRUD + bulk operations
│   │   └── AdminTourStepController.java # Tour step CRUD + reorder
│   ├── media/
│   │   ├── MediaController.java         # Upload + library endpoints (/api/admin/media)
│   │   ├── MediaService.java            # Upload handling, variant orchestration
│   │   └── ImageVariantGenerator.java   # Thumbnail/small/medium/large generation via Thumbnailator
│   └── migration/
│       └── DataMigrationService.java    # One-time Strapi backup import (CLI runner or admin endpoint)
├── src/test/java/com/simonrowe/
│   ├── auth/
│   │   └── SecurityConfigTest.java      # JWT validation, role-based access tests
│   ├── admin/
│   │   ├── AdminBlogControllerTest.java # Blog CRUD integration tests with Testcontainers
│   │   ├── AdminJobControllerTest.java
│   │   ├── AdminSkillControllerTest.java
│   │   ├── AdminSkillGroupControllerTest.java
│   │   ├── AdminProfileControllerTest.java
│   │   ├── AdminSocialMediaControllerTest.java
│   │   ├── AdminTagControllerTest.java
│   │   └── AdminTourStepControllerTest.java
│   └── media/
│       ├── MediaControllerTest.java
│       └── ImageVariantGeneratorTest.java

frontend/
├── src/
│   ├── components/
│   │   └── admin/
│   │       ├── AdminLayout.tsx          # Admin shell with sidebar nav, Auth0 login gate
│   │       ├── MarkdownEditor.tsx       # MDXEditor wrapper with live preview
│   │       ├── MediaLibrary.tsx         # Grid of uploaded images with select callback
│   │       ├── ImageUploader.tsx        # Drag-and-drop image upload with progress
│   │       ├── BlogEditor.tsx           # Blog create/edit form (title, markdown, tags, skills, image)
│   │       ├── JobEditor.tsx            # Job create/edit form (company, dates, markdown, skills)
│   │       ├── SkillEditor.tsx          # Skill create/edit form (name, rating, description, image)
│   │       ├── SkillGroupEditor.tsx     # Skill group form with drag-and-drop skill ordering
│   │       ├── ProfileEditor.tsx        # Profile single-entity editor
│   │       ├── SocialMediaEditor.tsx    # Social media link list editor
│   │       ├── TagManager.tsx           # Bulk tag create/rename/delete interface
│   │       └── TourStepEditor.tsx       # Tour step form with reorder controls
│   ├── pages/
│   │   └── admin/
│   │       ├── AdminDashboard.tsx       # Overview page with content counts
│   │       ├── BlogsAdmin.tsx           # Blog list + create/edit routing
│   │       ├── JobsAdmin.tsx            # Jobs list + create/edit routing
│   │       ├── SkillsAdmin.tsx          # Skills + skill groups management
│   │       ├── ProfileAdmin.tsx         # Profile + social media management
│   │       ├── TagsAdmin.tsx            # Tag management page
│   │       ├── TourStepsAdmin.tsx       # Tour steps management page
│   │       └── MediaAdmin.tsx           # Media library page
│   └── services/
│       ├── adminApi.ts                  # Axios/fetch wrapper with Auth0 token injection
│       └── auth.ts                      # Auth0 provider config, useAuth hook wrapper
├── tests/
│   └── admin/
│       ├── AdminLayout.test.tsx
│       ├── BlogEditor.test.tsx
│       ├── MarkdownEditor.test.tsx
│       └── MediaLibrary.test.tsx
```

**Structure Decision**: Option 2 (Web application) selected, consistent with Spec 001. Admin backend code is added as new packages within the existing `backend/` Gradle subproject -- no separate service is needed since admin endpoints are simply secured routes on the same Spring Boot application. Admin frontend code is added as new pages and components within the existing `frontend/` React application, gated behind Auth0 authentication at the route level.

## Complexity Tracking

No constitution violations. All principles pass without exception.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| *None* | *N/A* | *N/A* |
