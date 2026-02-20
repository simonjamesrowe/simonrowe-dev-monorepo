# Tasks: Contact Form

**Feature**: 006-contact-form
**Date**: 2026-02-21
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

## Phase 2: Foundational

- [ ] T001 [P1] Add SendGrid Java SDK dependency (`com.sendgrid:sendgrid-java:4.+`) and Spring Boot Starter Validation dependency to `backend/build.gradle.kts`
- [ ] T002 [P1] Add SendGrid and reCAPTCHA configuration properties (`spring.sendgrid.api-key`, `contact.email.from`, `contact.email.to`, `contact.recaptcha.secret-key`, `contact.recaptcha.verify-url`) to `backend/src/main/resources/application.yml`
- [ ] T003 [P1] Create ContactRequest Java record with Jakarta validation annotations (`@NotBlank`, `@Email`, `@Size`) for fields: firstName, lastName, email, subject, message, recaptchaToken (`backend/src/main/java/com/simonrowe/contact/ContactRequest.java`)
- [ ] T004 [P1] Create ContactSubmission Java record (internal model with referrer field extracted from HTTP Referer header) (`backend/src/main/java/com/simonrowe/contact/ContactSubmission.java`)
- [ ] T005 [P1] Create ContactService that orchestrates reCAPTCHA verification via RecaptchaService, builds ContactSubmission from ContactRequest + Referer header, and dispatches email via EmailService (`backend/src/main/java/com/simonrowe/contact/ContactService.java`)
- [ ] T006 [P1] Create ContactController with `POST /api/contact-us` endpoint accepting `@Valid @RequestBody ContactRequest` and `@RequestHeader(value = "Referer", required = false) String referer`, delegating to ContactService, returning 200 on success (`backend/src/main/java/com/simonrowe/contact/ContactController.java`)
- [ ] T007 [P1] Create global exception handler (`@RestControllerAdvice`) to return 400 with `{ "errors": [{ "field": "...", "message": "..." }] }` for `MethodArgumentNotValidException`, 500 for email delivery failures, and 503 for reCAPTCHA service unavailability (`backend/src/main/java/com/simonrowe/contact/ContactExceptionHandler.java`)
- [ ] T008 [P1] Install frontend dependencies: `react-google-recaptcha`, `@types/react-google-recaptcha`, `react-hook-form`, `@hookform/resolvers`, `zod` in `frontend/package.json`

## Phase 3: US1 - Submit Contact Message (P1)

- [ ] T009 [P1] [US1] Create RecaptchaService that sends POST to Google siteverify endpoint with secret key and token, returns boolean success, throws exception on service unavailability (`backend/src/main/java/com/simonrowe/contact/RecaptchaService.java`)
- [ ] T010 [P1] [US1] Create EmailService that constructs a plain-text email via SendGrid SDK (from: simon@simonjamesrowe.com, to: simon.rowe@gmail.com) with subject from submission and body containing referrer, email, name, and message content (`backend/src/main/java/com/simonrowe/contact/EmailService.java`)
- [ ] T011 [P1] [US1] Write RecaptchaServiceTest covering: successful verification, failed verification (success=false), Google API unreachable (throws exception) (`backend/src/test/java/com/simonrowe/contact/RecaptchaServiceTest.java`)
- [ ] T012 [P1] [US1] Write EmailServiceTest covering: successful email construction and send, SendGrid API failure handling (`backend/src/test/java/com/simonrowe/contact/EmailServiceTest.java`)
- [ ] T013 [P1] [US1] Write ContactServiceTest covering: successful orchestration (reCAPTCHA pass + email sent), reCAPTCHA failure (400), reCAPTCHA unavailable (503), email send failure (500), Referer header present and absent (`backend/src/test/java/com/simonrowe/contact/ContactServiceTest.java`)
- [ ] T014 [P1] [US1] Write ContactControllerTest (MockMvc) covering: valid submission returns 200, missing required fields returns 400 with field errors, invalid email format returns 400, reCAPTCHA failure returns 400, email delivery failure returns 500, reCAPTCHA service unavailable returns 503, Referer header is passed through (`backend/src/test/java/com/simonrowe/contact/ContactControllerTest.java`)
- [ ] T015 [P1] [US1] Create Zod validation schema (`contactFormSchema`) and `ContactFormData` type matching the data model constraints (firstName max 100, lastName max 100, email max 254, subject max 200, message max 5000, recaptchaToken required) (`frontend/src/components/contact/contactFormSchema.ts`)
- [ ] T016 [P1] [US1] Create FormField reusable component that renders a label, input/textarea element, and conditional error message from React Hook Form field errors (`frontend/src/components/contact/FormField.tsx`)
- [ ] T017 [P1] [US1] Create contactApi service with `submitContactForm(data: ContactFormData): Promise<void>` that POSTs to `/api/contact-us` and parses error responses into `ApiErrorResponse` type with `errors` array or `error` string (`frontend/src/services/contactApi.ts`)
- [ ] T018 [P1] [US1] Create ContactForm component using React Hook Form with Zod resolver, rendering FormField components for firstName, lastName, email, subject, message; integrating react-google-recaptcha v2 widget; displaying submit button; showing success banner on 200 or field-level/general error messages on failure; resetting form and reCAPTCHA on success (`frontend/src/components/contact/ContactForm.tsx`)
- [ ] T019 [P1] [US1] Write ContactForm.test.tsx covering: renders all five fields and reCAPTCHA, shows validation errors when submitting empty form, shows email format error for invalid email, submits successfully and displays success message, displays server error message on 500, displays reCAPTCHA error when token missing, resets form after successful submission (`frontend/tests/components/contact/ContactForm.test.tsx`)

## Phase 4: US2 - View Contact Information (P2)

- [ ] T020 [P2] [US2] Create ContactInfo component displaying location, phone number as clickable `tel:` link, primary email as clickable `mailto:` link, and secondary email as clickable `mailto:` link; contact data provided via props or constants (`frontend/src/components/contact/ContactInfo.tsx`)
- [ ] T021 [P2] [US2] Write ContactInfo.test.tsx covering: renders location text, renders phone number with `tel:` href, renders primary email with `mailto:` href, renders secondary email with `mailto:` href, distinguishes between primary and secondary email labels (`frontend/tests/components/contact/ContactInfo.test.tsx`)
- [ ] T022 [P2] [US2] Integrate ContactForm and ContactInfo components into the homepage Contact section layout (side-by-side or stacked based on viewport) (`frontend/src/components/contact/ContactSection.tsx`)

## Phase 5: Polish

- [ ] T023 Verify responsive layout of ContactForm and ContactInfo across mobile (< 640px), tablet (640-1024px), and desktop (> 1024px) viewports; adjust Tailwind/CSS classes as needed
- [ ] T024 Add structured logging in ContactService for submission received, reCAPTCHA result, and email dispatch outcome (success/failure with timing)
- [ ] T025 Add OpenTelemetry span annotations on EmailService.send() and RecaptchaService.verify() for observability
- [ ] T026 Run full backend test suite (`./gradlew test`) and verify all contact tests pass with JaCoCo coverage on new classes
- [ ] T027 Run full frontend test suite (`npm test`) and verify all contact component tests pass
- [ ] T028 End-to-end manual test: fill out contact form on frontend, submit, verify success message appears, verify email arrives at simon.rowe@gmail.com with correct content including Referer
