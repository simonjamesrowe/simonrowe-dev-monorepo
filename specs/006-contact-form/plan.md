# Implementation Plan: Contact Form

**Branch**: `006-contact-form` | **Date**: 2026-02-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/006-contact-form/spec.md`

## Summary

Contact form enabling visitors to send messages to the site owner, with client-side and server-side validation, Google reCAPTCHA v2 spam protection, and email notification delivery via SendGrid. The backend provides a `POST /api/contact-us` REST endpoint that validates the submission, verifies the reCAPTCHA token server-side, and dispatches an email containing all submission details (name, email, subject, message, referrer). The frontend renders a validated form with reCAPTCHA widget and displays success/error feedback. A separate contact information section displays the site owner's location, phone number, and clickable email links.

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 4.x, Spring Boot Starter Mail (fallback), SendGrid Java SDK (primary email transport), Spring Boot Starter Validation, React (latest stable), react-google-recaptcha
**Storage**: N/A -- contact submissions are not persisted, only forwarded via email
**Testing**: JUnit 5 + MockMvc (backend unit/integration), Vitest + React Testing Library (frontend)
**Target Platform**: Docker containers on Linux (production via Docker Compose + Pinggy)
**Project Type**: Web application (backend + frontend monorepo)
**Performance Goals**: Form submission and confirmation within 5 seconds (SC-001); 95% email delivery success rate (SC-002)
**Constraints**: Email sent from simon@simonjamesrowe.com to simon.rowe@gmail.com; reCAPTCHA must block 99%+ of bot submissions (SC-003); all viewports supported (SC-006)
**Scale/Scope**: Single endpoint, single email integration, 2 frontend components (form + contact info)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Justification |
|---|-----------|--------|---------------|
| I | Monorepo with Separate Containers | PASS | Backend contact endpoint in `backend/` directory, frontend contact components in `frontend/` directory. No new containers required -- uses existing backend and frontend containers. |
| II | Modern Java & React Stack | PASS | Java 25 with Spring Boot 4, Gradle Kotlin DSL for backend. React latest stable for frontend. No new infrastructure dependencies (no MongoDB, Kafka, or Elasticsearch needed for this feature). SendGrid is a managed email service already used in the previous implementation. |
| III | Quality Gates (NON-NEGOTIABLE) | PASS | Google Java Style enforced via Checkstyle. JaCoCo coverage for new service classes. Backend tests use MockMvc for controller validation. Frontend tests with Vitest cover form validation logic and submission flow. reCAPTCHA verification tested with mocked responses. |
| IV | Observability & Operability | PASS | Structured logging for contact submissions and email delivery outcomes. OpenTelemetry spans on email sending. No new metrics endpoints required -- uses existing actuator setup. |
| V | Simplicity & Incremental Delivery | PASS | No persistence layer -- submissions forwarded directly via email (simplest approach). No message queuing for email delivery (synchronous send, can be made async later if needed). Contact info is static/config-driven, not database-backed. Two user stories delivered by priority. |

## Project Structure

### Documentation (this feature)

```text
specs/006-contact-form/
├── plan.md              # This file
├── research.md          # Phase 0: Email service and reCAPTCHA research
├── data-model.md        # Phase 1: ContactSubmission request model
├── quickstart.md        # Phase 1: Developer quickstart guide
├── contracts/           # Phase 1: API contracts
│   └── contact-api.yaml # POST /api/contact-us OpenAPI spec
├── checklists/
│   └── requirements.md  # Specification quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/
│   ├── main/
│   │   ├── java/com/simonrowe/
│   │   │   └── contact/
│   │   │       ├── ContactController.java      # REST endpoint: POST /api/contact-us
│   │   │       ├── ContactRequest.java          # Request DTO with Jakarta Validation annotations
│   │   │       ├── ContactService.java          # Orchestrates validation, reCAPTCHA, and email
│   │   │       ├── EmailService.java            # SendGrid email dispatch
│   │   │       └── RecaptchaService.java        # Google reCAPTCHA v2 server-side verification
│   │   └── resources/
│   │       └── application.yml                  # SendGrid and reCAPTCHA configuration (additions)
│   └── test/
│       └── java/com/simonrowe/
│           └── contact/
│               ├── ContactControllerTest.java   # MockMvc tests for endpoint validation
│               ├── ContactServiceTest.java      # Unit tests for orchestration logic
│               ├── EmailServiceTest.java        # Unit tests for email construction
│               └── RecaptchaServiceTest.java    # Unit tests for reCAPTCHA verification

frontend/
├── src/
│   ├── components/
│   │   └── contact/
│   │       ├── ContactForm.tsx                  # Form with validation and reCAPTCHA widget
│   │       ├── ContactInfo.tsx                  # Location, phone, email display
│   │       └── FormField.tsx                    # Reusable form field with error display
│   └── services/
│       └── contactApi.ts                        # API client for POST /api/contact-us
└── tests/
    └── components/
        └── contact/
            ├── ContactForm.test.tsx             # Form validation and submission tests
            └── ContactInfo.test.tsx             # Contact info rendering tests
```

**Structure Decision**: Option 2 (Web application) selected, consistent with the existing monorepo layout established in 001-project-infrastructure. Backend contact classes are placed in a `contact` package under the existing `com.simonrowe` namespace. Frontend contact components are in a `contact` subdirectory under `components/`. No new top-level directories are introduced.

## Complexity Tracking

No constitution violations. All principles pass without exception.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| *None* | *N/A* | *N/A* |
