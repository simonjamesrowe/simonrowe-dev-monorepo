# Implementation Plan: Profile & Homepage

**Branch**: `002-profile-homepage` | **Date**: 2026-02-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-profile-homepage/spec.md`

## Summary

Single-page homepage displaying a professional profile with sidebar navigation, responsive design, and smooth scrolling between sections. The backend serves profile and social media data via REST API endpoints backed by MongoDB. The React frontend renders a single-page layout with an About section (name, title, headline, description, contact details, profile image), Download CV button (linked to Spec 004), social media links, and persistent sidebar navigation that collapses to a mobile toggle menu on smaller viewports. A scroll-to-top button appears when the user scrolls down. Markdown content in the description field is safely rendered using a Markdown library.

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript/React latest stable (frontend)
**Primary Dependencies**: Spring Boot 4, Spring Data MongoDB, React, React Router, react-markdown
**Storage**: MongoDB (profile and social_medias collections)
**Testing**: JUnit 5 + Testcontainers (backend), Vitest + React Testing Library (frontend)
**Target Platform**: Docker containers orchestrated via Docker Compose, accessible via browser
**Project Type**: Web application (separate backend and frontend containers)
**Performance Goals**: Homepage loads and displays all profile content within 3 seconds (SC-001); navigation transitions within 1 second (SC-003)
**Constraints**: Mobile menu open/close within 300ms (SC-006); contact expand/collapse within 200ms (SC-007)
**Scale/Scope**: Single-user personal website; one profile document, 3-5 social media links

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| **I. Monorepo with Separate Containers** | PASS | Backend and frontend are separate directories with independent Dockerfiles and build configs. Both deploy as separate containers orchestrated via Docker Compose. |
| **II. Modern Java & React Stack** | PASS | Backend uses Java 25, Spring Boot 4, Gradle (Kotlin DSL), MongoDB. Frontend uses latest stable React. No CMS; content managed through MongoDB persistence. |
| **III. Quality Gates (NON-NEGOTIABLE)** | PASS | Google Java Style enforced via linter. JaCoCo coverage thresholds enforced. Testcontainers used for backend integration tests. Frontend tests cover critical user journeys (profile load, navigation, responsive behavior). SonarQube runs on PRs. CycloneDX BOM generated. |
| **IV. Observability & Operability** | PASS | Spring Boot Actuator exposes Prometheus metrics on a separate management port. OpenTelemetry integrated for distributed tracing. Structured logging (JSON) across backend. Frontend integrates analytics tracking (GA4). |
| **V. Simplicity & Incremental Delivery** | PASS | Feature delivered as independently testable increment. No premature abstractions: simple REST endpoint returns profile data, simple React components render it. Profile and social media are the only entities; no unnecessary patterns. |

## Project Structure

### Documentation (this feature)

```text
specs/002-profile-homepage/
├── plan.md              # This file
├── research.md          # Phase 0 output - technology decisions
├── data-model.md        # Phase 1 output - MongoDB document models
├── quickstart.md        # Phase 1 output - verification steps
├── contracts/           # Phase 1 output - OpenAPI spec
│   └── profile-api.yaml
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── build.gradle.kts
├── src/
│   ├── main/
│   │   ├── java/com/simonrowe/
│   │   │   ├── Application.java
│   │   │   ├── config/
│   │   │   │   ├── WebConfig.java
│   │   │   │   └── MongoConfig.java
│   │   │   └── profile/
│   │   │       ├── Profile.java                    # MongoDB document entity
│   │   │       ├── SocialMediaLink.java             # MongoDB document entity
│   │   │       ├── ProfileRepository.java           # Spring Data MongoDB repository
│   │   │       ├── SocialMediaLinkRepository.java   # Spring Data MongoDB repository
│   │   │       ├── ProfileService.java              # Business logic layer
│   │   │       ├── ProfileController.java           # REST API controller
│   │   │       ├── ProfileResponse.java             # API response DTO
│   │   │       └── SocialMediaLinkResponse.java     # API response DTO
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/com/simonrowe/profile/
│           ├── ProfileControllerTest.java           # Slice test with Testcontainers
│           └── ProfileServiceTest.java              # Unit test
├── Dockerfile
└── docker-compose.yml                               # At repo root, shared

frontend/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.tsx
│   ├── App.tsx                                      # Root component with Router
│   ├── components/
│   │   ├── layout/
│   │   │   ├── Sidebar.tsx                          # Fixed sidebar nav (desktop)
│   │   │   ├── MobileMenu.tsx                       # Toggle hamburger menu (mobile)
│   │   │   └── ScrollToTop.tsx                      # Scroll-to-top button
│   │   └── profile/
│   │       ├── ProfileBanner.tsx                    # Hero banner with background image
│   │       ├── AboutSection.tsx                     # About text with markdown rendering
│   │       ├── ContactDetails.tsx                   # Expandable contact panel
│   │       └── SocialLinks.tsx                      # Social media icon links
│   ├── pages/
│   │   └── HomePage.tsx                             # Single-page homepage composition
│   ├── services/
│   │   └── profileApi.ts                            # API client for profile endpoints
│   ├── hooks/
│   │   └── useProfile.ts                            # Custom hook for profile data fetching
│   └── types/
│       ├── Profile.ts                               # TypeScript interface for Profile
│       └── SocialMediaLink.ts                       # TypeScript interface for SocialMediaLink
├── tests/
│   ├── components/
│   │   ├── ProfileBanner.test.tsx
│   │   ├── AboutSection.test.tsx
│   │   ├── ContactDetails.test.tsx
│   │   ├── SocialLinks.test.tsx
│   │   ├── Sidebar.test.tsx
│   │   └── ScrollToTop.test.tsx
│   └── pages/
│       └── HomePage.test.tsx
├── Dockerfile
└── nginx.conf
```

**Structure Decision**: Web application layout selected (Option 2 from template). Backend and frontend are separate top-level directories with independent build configurations, Dockerfiles, and test suites. This aligns with Constitution Principle I (Monorepo with Separate Containers). The backend follows a feature-based package structure (`com.simonrowe.profile`) rather than a layered structure, keeping related code co-located. The frontend follows a component-based structure with `components/`, `pages/`, `services/`, `hooks/`, and `types/` directories.

## Complexity Tracking

> No constitution violations. All principles pass without exceptions.
