# Research: Contact Form

**Feature**: 006-contact-form
**Date**: 2026-02-21
**Status**: Complete

## Research Areas

### 1. Email Service: Spring Mail + SMTP vs SendGrid

#### Option A: Spring Boot Starter Mail + SMTP

**How it works**: Spring Boot provides `spring-boot-starter-mail` which wraps JavaMail. Configure an SMTP server (Gmail SMTP, Amazon SES SMTP, or any SMTP relay) and use `JavaMailSender` to send messages.

**Pros**:
- Zero external SDK dependency -- uses standard JavaMail API bundled with Spring Boot
- Works with any SMTP-compliant provider (Gmail, Amazon SES, Mailgun, Postmark)
- Simple configuration via `spring.mail.*` properties
- No vendor lock-in at the code level

**Cons**:
- Gmail SMTP has daily sending limits (500/day for personal, 2000/day for Workspace)
- Gmail requires App Passwords or OAuth2 for SMTP auth (Google deprecated "less secure apps")
- SMTP connections can be slow (TCP handshake + TLS + AUTH on each send)
- No built-in delivery tracking, bounce handling, or analytics

**Configuration example**:
```yaml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: simon@simonjamesrowe.com
    password: ${SMTP_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

#### Option B: SendGrid Java SDK (HTTP API)

**How it works**: SendGrid provides a Java SDK that communicates with their REST API over HTTPS. Spring Boot has built-in auto-configuration for SendGrid via `spring.sendgrid.api-key`.

**Pros**:
- Spring Boot auto-configuration (`SendGridAutoConfiguration`) creates the `SendGrid` bean automatically
- HTTP-based -- faster than SMTP (no multi-step handshake)
- Free tier: 100 emails/day (sufficient for a personal portfolio contact form)
- Built-in delivery tracking, bounce handling, open/click analytics
- Already used in the previous version of simonrowe.dev (proven approach)
- Sender authentication via domain verification (already configured for simonjamesrowe.com)

**Cons**:
- Vendor dependency on SendGrid SDK
- API key management required
- Free tier rate limits (100/day) could be hit under spam attacks (mitigated by reCAPTCHA)

**Configuration example**:
```yaml
spring:
  sendgrid:
    api-key: ${SENDGRID_API_KEY}

contact:
  email:
    from: simon@simonjamesrowe.com
    to: simon.rowe@gmail.com
```

#### Decision: SendGrid Java SDK

**Rationale**:
1. **Continuity**: The previous simonrowe.dev backend already uses SendGrid with domain authentication configured for simonjamesrowe.com. Reusing this avoids setting up new sender verification.
2. **Spring Boot integration**: Auto-configuration creates the `SendGrid` bean from a single property (`spring.sendgrid.api-key`).
3. **Simplicity**: HTTP-based sending is faster and requires less configuration than SMTP (no TLS handshake tuning, no connection pooling concerns).
4. **Sufficient for scale**: A personal portfolio site receives very low contact form volume. The 100 emails/day free tier is more than adequate, especially with reCAPTCHA preventing spam.

**Fallback plan**: If SendGrid becomes unavailable or pricing changes, the `EmailService` interface can be swapped to a Spring Mail + SMTP implementation without changing the controller or service layer.

---

### 2. reCAPTCHA Integration: v2 Checkbox vs v3 Score-Based

#### Option A: reCAPTCHA v2 ("I'm not a robot" checkbox)

**How it works**: Renders a visible checkbox widget. User clicks it; Google analyzes browser behavior and may present an image challenge. On completion, a token is generated client-side. The backend verifies this token via Google's `siteverify` API.

**Client-side**: `react-google-recaptcha` npm package renders the widget and provides the token via callback.

**Server-side verification**:
```
POST https://www.google.com/recaptcha/api/siteverify
  secret={SECRET_KEY}&response={TOKEN}
```

Response:
```json
{
  "success": true,
  "challenge_ts": "2026-02-21T10:00:00Z",
  "hostname": "simonrowe.dev"
}
```

**Pros**:
- Explicit user action -- clear to the visitor that spam protection is active
- Binary pass/fail result -- no score threshold tuning needed
- Already implemented in the previous simonrowe.dev frontend (proven UX)
- Well-established `react-google-recaptcha` library for React integration

**Cons**:
- Slight friction for users (click + possible image challenge)
- Visible widget takes up form space
- Google reCAPTCHA v2 keys already exist from the previous site

#### Option B: reCAPTCHA v3 (Invisible, score-based)

**How it works**: Runs invisibly in the background on every page load. Returns a score (0.0 to 1.0) indicating the likelihood of human interaction. The backend decides the threshold.

**Pros**:
- Zero user friction -- no checkbox or challenges
- Continuous monitoring across the page lifecycle

**Cons**:
- Requires score threshold tuning (false positives/negatives)
- No user-facing feedback if blocked -- harder to debug
- New site key/secret key required (v2 and v3 keys are not interchangeable)
- Badge must be displayed (Google branding requirement) unless hidden with CSS and attribution text

#### Decision: reCAPTCHA v2 Checkbox

**Rationale**:
1. **Proven UX**: The previous simonrowe.dev site used reCAPTCHA v2 with existing site keys. Users are familiar with the "I'm not a robot" pattern.
2. **Simplicity**: Binary pass/fail eliminates the need for score threshold tuning and associated edge-case handling (Principle V: Simplicity).
3. **Explicit feedback**: Users know exactly when spam verification fails and can retry, fulfilling SC-004 (clear error messages).
4. **Server-side verification**: Simple HTTP POST to Google's `siteverify` endpoint. A `RecaptchaService` class makes the call and checks `success: true`.
5. **Existing keys**: reCAPTCHA v2 site key `6LeQhGEaAAAAAI8TtlXo0p6gQi2r6x3lvql4OWYq` is already provisioned. A new key pair should be generated for the new domain setup, but the infrastructure is in place.

**Implementation notes**:
- The frontend sends the reCAPTCHA token in the request body (field: `recaptchaToken`)
- The backend verifies the token before processing the email
- If the reCAPTCHA service is unavailable, the submission is rejected with a 503 (fail closed -- security over availability)

---

### 3. Form Validation Approach

#### Client-Side Validation (React)

**Options evaluated**:
- **Formik + Yup**: Mature, well-documented. Formik handles form state; Yup handles schema validation. Previously used in simonrowe.dev (Formik with inline validation).
- **React Hook Form + Zod**: More performant (fewer re-renders), modern API. Zod provides TypeScript-first schema validation.
- **Native HTML5 validation**: Zero dependency but limited error message customization and UX control.

**Decision: React Hook Form + Zod**

**Rationale**:
1. React Hook Form is the current community standard for React form handling with minimal re-renders
2. Zod provides TypeScript-native schema validation with type inference, reducing duplication between types and validation rules
3. Smaller bundle size than Formik
4. The previous site used Formik, but this is a ground-up rebuild where adopting current best practices is appropriate

**Validation rules**:
| Field | Type | Rules |
|-------|------|-------|
| firstName | text | Required, max 100 chars |
| lastName | text | Required, max 100 chars |
| email | email | Required, valid email format |
| subject | text | Required, max 200 chars |
| message | textarea | Required, max 5000 chars |
| recaptchaToken | hidden | Required (populated by reCAPTCHA widget) |

#### Server-Side Validation (Spring Boot)

**Approach**: Jakarta Bean Validation annotations on the `ContactRequest` record.

- `@NotBlank` on all required text fields
- `@Email` on the email field
- `@Size(max=...)` to enforce length limits matching the frontend
- Custom validation for the reCAPTCHA token (verified via `RecaptchaService`, not a Bean Validation annotation)

**Error response format**: Spring Boot's default `MethodArgumentNotValidException` handler returns a 400 with field-level error details. A custom `@RestControllerAdvice` can format this into a consistent error response body:

```json
{
  "errors": [
    { "field": "email", "message": "must be a well-formed email address" },
    { "field": "firstName", "message": "must not be blank" }
  ]
}
```

---

### 4. Contact Information Display

**Approach**: Static configuration, not database-backed.

The contact information (location, phone, primary email, secondary email) is tied to the site owner's profile. Rather than creating a separate API endpoint, this data is served as part of the existing profile response (defined in spec 002-profile-homepage). The frontend `ContactInfo` component receives profile data as props.

If the profile API is not yet implemented, the contact info values can be hardcoded in the frontend as constants until the profile feature is available. This follows Principle V (Simplicity & Incremental Delivery).

**Contact information values** (from reference site):
- Location: from profile
- Phone: clickable `tel:` link
- Primary email: clickable `mailto:` link
- Secondary email: clickable `mailto:` link (conditionally rendered)

---

## Dependencies and Configuration

### New Backend Dependencies (build.gradle.kts additions)

```kotlin
implementation("com.sendgrid:sendgrid-java:4.+")         // SendGrid SDK
implementation("org.springframework.boot:spring-boot-starter-validation")  // Jakarta Bean Validation
```

### New Frontend Dependencies (package.json additions)

```json
{
  "react-google-recaptcha": "^3.1.0",
  "@types/react-google-recaptcha": "^2.1.9",
  "react-hook-form": "^7.54.0",
  "@hookform/resolvers": "^3.9.0",
  "zod": "^3.24.0"
}
```

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `SENDGRID_API_KEY` | SendGrid API key for email dispatch | Yes (backend) |
| `RECAPTCHA_SECRET_KEY` | Google reCAPTCHA v2 secret key | Yes (backend) |
| `VITE_RECAPTCHA_SITE_KEY` | Google reCAPTCHA v2 site key | Yes (frontend) |

### Application Configuration (additions to application.yml)

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
