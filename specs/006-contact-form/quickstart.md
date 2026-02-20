# Quickstart: Contact Form

**Feature**: 006-contact-form
**Date**: 2026-02-21

## Prerequisites

- Java 25 installed
- Node.js (latest LTS) and npm installed
- Docker and Docker Compose running (for infrastructure services)
- SendGrid account with API key (free tier sufficient)
- Google reCAPTCHA v2 site key and secret key

## Environment Setup

### 1. Obtain API Keys

**SendGrid**:
1. Sign up or log in at https://sendgrid.com
2. Navigate to Settings > API Keys > Create API Key
3. Grant "Mail Send" permission (restricted access is sufficient)
4. Copy the generated API key

**Google reCAPTCHA v2**:
1. Go to https://www.google.com/recaptcha/admin
2. Register a new site with reCAPTCHA v2 ("I'm not a robot" checkbox)
3. Add `localhost` and `simonrowe.dev` to the domains list
4. Copy both the Site Key (frontend) and Secret Key (backend)

### 2. Configure Environment Variables

Create or update a `.env` file in the repository root:

```bash
# SendGrid
SENDGRID_API_KEY=SG.xxxxxxxxxxxxxxxxxxxx

# Google reCAPTCHA v2
RECAPTCHA_SECRET_KEY=6LeXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX

# Frontend reCAPTCHA site key
VITE_RECAPTCHA_SITE_KEY=6LeXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
```

### 3. Backend Configuration

The following properties are added to `backend/src/main/resources/application.yml`:

```yaml
spring:
  sendgrid:
    api-key: ${SENDGRID_API_KEY}

contact:
  email:
    from: simon@simonjamesrowe.com
    to: simon.rowe@gmail.com
  recaptcha:
    secret-key: ${RECAPTCHA_SECRET_KEY}
    verify-url: https://www.google.com/recaptcha/api/siteverify
```

### 4. Backend Dependencies

Add to `backend/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.sendgrid:sendgrid-java:4.+")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
```

### 5. Frontend Dependencies

Install from the `frontend/` directory:

```bash
cd frontend
npm install react-google-recaptcha @types/react-google-recaptcha react-hook-form @hookform/resolvers zod
```

## Running Locally

### Start Infrastructure

From the repository root:

```bash
docker compose up -d
```

### Start Backend

```bash
cd backend
SENDGRID_API_KEY=your-key RECAPTCHA_SECRET_KEY=your-secret ./gradlew bootRun
```

The backend starts on `http://localhost:8080`. The contact endpoint is available at `POST http://localhost:8080/api/contact-us`.

### Start Frontend

```bash
cd frontend
VITE_RECAPTCHA_SITE_KEY=your-site-key npm run dev
```

The frontend starts on `http://localhost:5173` (default Vite port).

## Testing the Contact Form

### Manual Test (cURL)

Submit a test contact form (bypasses reCAPTCHA -- only works if backend has a test profile that skips verification):

```bash
curl -X POST http://localhost:8080/api/contact-us \
  -H "Content-Type: application/json" \
  -H "Referer: http://localhost:5173/" \
  -d '{
    "firstName": "Test",
    "lastName": "User",
    "email": "test@example.com",
    "subject": "Test message",
    "message": "This is a test contact form submission.",
    "recaptchaToken": "test-token"
  }'
```

**Expected responses**:
- `200 OK` -- submission processed, email sent
- `400 Bad Request` -- validation failed (check response body for field errors)
- `500 Internal Server Error` -- email delivery failed

### Validation Test (missing fields)

```bash
curl -X POST http://localhost:8080/api/contact-us \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "",
    "email": "not-an-email",
    "subject": "",
    "message": ""
  }'
```

**Expected**: `400 Bad Request` with errors for `firstName`, `lastName`, `email`, `subject`, `message`, and `recaptchaToken`.

### Run Backend Tests

```bash
cd backend
./gradlew test
```

Tests cover:
- `ContactControllerTest` -- endpoint validation, success/error responses
- `ContactServiceTest` -- orchestration of reCAPTCHA verification and email sending
- `EmailServiceTest` -- email construction and SendGrid API interaction
- `RecaptchaServiceTest` -- reCAPTCHA token verification logic

### Run Frontend Tests

```bash
cd frontend
npm test
```

Tests cover:
- `ContactForm.test.tsx` -- form rendering, validation error display, submission flow, success/error messages
- `ContactInfo.test.tsx` -- contact information rendering, `tel:` and `mailto:` link generation

## Key Files

### Backend

| File | Purpose |
|------|---------|
| `backend/src/main/java/com/simonrowe/contact/ContactController.java` | REST endpoint `POST /api/contact-us` |
| `backend/src/main/java/com/simonrowe/contact/ContactRequest.java` | Request DTO with validation annotations |
| `backend/src/main/java/com/simonrowe/contact/ContactService.java` | Orchestrates reCAPTCHA verification and email dispatch |
| `backend/src/main/java/com/simonrowe/contact/EmailService.java` | Constructs and sends email via SendGrid |
| `backend/src/main/java/com/simonrowe/contact/RecaptchaService.java` | Verifies reCAPTCHA tokens with Google |

### Frontend

| File | Purpose |
|------|---------|
| `frontend/src/components/contact/ContactForm.tsx` | Contact form with validation and reCAPTCHA |
| `frontend/src/components/contact/ContactInfo.tsx` | Location, phone, and email display |
| `frontend/src/components/contact/FormField.tsx` | Reusable form field with error message |
| `frontend/src/services/contactApi.ts` | API client for contact form submission |

### Configuration

| File | Purpose |
|------|---------|
| `backend/src/main/resources/application.yml` | SendGrid and reCAPTCHA config |
| `specs/006-contact-form/contracts/contact-api.yaml` | OpenAPI contract |

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `401 Unauthorized` from SendGrid | Verify `SENDGRID_API_KEY` is set and valid |
| `403 Forbidden` from SendGrid | Ensure sender authentication is configured for `simonjamesrowe.com` domain in SendGrid |
| reCAPTCHA widget not rendering | Check `VITE_RECAPTCHA_SITE_KEY` is set and `localhost` is in the reCAPTCHA domain list |
| reCAPTCHA verification always fails | Verify `RECAPTCHA_SECRET_KEY` matches the secret key for the site key used on the frontend |
| Email not received | Check spam/junk folder; verify SendGrid activity feed for delivery status |
| `Connection refused` on port 8080 | Ensure the backend is running (`./gradlew bootRun`) |
| Validation errors not showing on frontend | Ensure the API error response format matches the `ApiErrorResponse` TypeScript type |
