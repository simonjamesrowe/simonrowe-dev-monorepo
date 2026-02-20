# Data Model: Contact Form

**Feature**: 006-contact-form
**Date**: 2026-02-21
**Status**: Complete

## Overview

The contact form feature does not persist data to MongoDB. Contact submissions are validated, verified against reCAPTCHA, and forwarded as email notifications. The data model consists of request/response DTOs and an internal email model.

## Entities

### ContactRequest (Inbound DTO)

The HTTP request body submitted by the frontend contact form. Validated using Jakarta Bean Validation annotations on the backend and Zod schema on the frontend.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `firstName` | `String` | `@NotBlank`, `@Size(max=100)` | Visitor's first name |
| `lastName` | `String` | `@NotBlank`, `@Size(max=100)` | Visitor's last name |
| `email` | `String` | `@NotBlank`, `@Email`, `@Size(max=254)` | Visitor's email address (RFC 5321 max length) |
| `subject` | `String` | `@NotBlank`, `@Size(max=200)` | Message subject line |
| `message` | `String` | `@NotBlank`, `@Size(max=5000)` | Message content |
| `recaptchaToken` | `String` | `@NotBlank` | Google reCAPTCHA v2 verification token |

**Notes**:
- The `referrer` field is NOT part of the request body. It is extracted from the HTTP `Referer` header server-side (FR-014).
- The `recaptchaToken` is consumed server-side for verification and is not included in the email notification.
- Field name `email` is used in the API contract (not `emailAddress` as in the legacy implementation) for clarity and OpenAPI convention alignment.
- Field name `message` is used in the API contract (not `content` as in the legacy implementation) for clarity.

### Java Record Definition

```java
package com.simonrowe.contact;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactRequest(
    @NotBlank @Size(max = 100) String firstName,
    @NotBlank @Size(max = 100) String lastName,
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(max = 200) String subject,
    @NotBlank @Size(max = 5000) String message,
    @NotBlank String recaptchaToken
) {}
```

### TypeScript Type Definition

```typescript
interface ContactFormData {
  firstName: string;
  lastName: string;
  email: string;
  subject: string;
  message: string;
  recaptchaToken: string;
}
```

### Zod Validation Schema

```typescript
import { z } from "zod";

export const contactFormSchema = z.object({
  firstName: z.string().min(1, "First name is required").max(100),
  lastName: z.string().min(1, "Last name is required").max(100),
  email: z.string().min(1, "Email is required").email("Invalid email address").max(254),
  subject: z.string().min(1, "Subject is required").max(200),
  message: z.string().min(1, "Message is required").max(5000),
  recaptchaToken: z.string().min(1, "Please complete the reCAPTCHA verification"),
});

export type ContactFormData = z.infer<typeof contactFormSchema>;
```

---

### ContactSubmission (Internal Model)

An internal representation used by `ContactService` after validation and before email construction. Includes the referrer extracted from the HTTP header.

| Field | Type | Source | Description |
|-------|------|--------|-------------|
| `firstName` | `String` | Request body | Visitor's first name |
| `lastName` | `String` | Request body | Visitor's last name |
| `email` | `String` | Request body | Visitor's email address |
| `subject` | `String` | Request body | Message subject line |
| `message` | `String` | Request body | Message content |
| `referrer` | `String` (nullable) | HTTP `Referer` header | Page the visitor submitted from |

### Java Record Definition

```java
package com.simonrowe.contact;

public record ContactSubmission(
    String firstName,
    String lastName,
    String email,
    String subject,
    String message,
    String referrer
) {}
```

---

### Error Response (Outbound DTO)

Returned on validation failure (HTTP 400) or server errors (HTTP 500/503).

**Validation error (400)**:
```json
{
  "errors": [
    {
      "field": "email",
      "message": "must be a well-formed email address"
    },
    {
      "field": "firstName",
      "message": "must not be blank"
    }
  ]
}
```

**reCAPTCHA failure (400)**:
```json
{
  "errors": [
    {
      "field": "recaptchaToken",
      "message": "reCAPTCHA verification failed"
    }
  ]
}
```

**Email delivery failure (500)**:
```json
{
  "error": "Failed to send message. Please try again later."
}
```

**reCAPTCHA service unavailable (503)**:
```json
{
  "error": "Verification service temporarily unavailable. Please try again later."
}
```

### TypeScript Error Types

```typescript
interface ValidationError {
  field: string;
  message: string;
}

interface ApiErrorResponse {
  errors?: ValidationError[];
  error?: string;
}
```

---

## Email Template

The email sent to the site owner (simon.rowe@gmail.com) is plain text format:

```
Subject: {subject}
From: simon@simonjamesrowe.com
To: simon.rowe@gmail.com

A message has been sent from the site: {referrer}
Email Address: {email}
Name: {firstName} {lastName}
Content: {message}
```

This matches the format used in the previous simonrowe.dev backend implementation.

---

## Data Flow

```
Browser                    Backend                       External
  |                          |                              |
  |  POST /api/contact-us   |                              |
  |  {ContactRequest}       |                              |
  |------------------------->|                              |
  |                          |  Validate (Jakarta BV)       |
  |                          |  400 if invalid              |
  |                          |                              |
  |                          |  Verify reCAPTCHA token      |
  |                          |------------------------------>|  Google siteverify
  |                          |<------------------------------|  {success: true/false}
  |                          |  400/503 if failed            |
  |                          |                              |
  |                          |  Build ContactSubmission      |
  |                          |  (add Referer header)        |
  |                          |                              |
  |                          |  Send email via SendGrid     |
  |                          |------------------------------>|  SendGrid API
  |                          |<------------------------------|  202 Accepted
  |                          |  500 if failed               |
  |                          |                              |
  |  200 OK                  |                              |
  |<-------------------------|                              |
```

---

## Persistence

**None**. Contact form submissions are not stored in MongoDB or any other database. They are validated, verified, and forwarded via email in a single synchronous request cycle.

If analytics or submission history is needed in the future, a `contactSubmissions` MongoDB collection could be introduced, but per Principle V (Simplicity & YAGNI), this is deferred until explicitly requested.
