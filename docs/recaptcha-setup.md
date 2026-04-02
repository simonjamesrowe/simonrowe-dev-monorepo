# reCAPTCHA Setup Guide

This guide covers setting up Google reCAPTCHA v2 ("I'm not a robot" checkbox) for the contact form and the chat verification gate.

## Steps

### 1. Register a reCAPTCHA Site

1. Go to the [reCAPTCHA Admin Console](https://www.google.com/recaptcha/admin)
2. Sign in with your Google account
3. Click **+** (Create) to register a new site
4. Fill in the form:
   - **Label**: e.g. `simonrowe.dev`
   - **reCAPTCHA type**: Select **reCAPTCHA v2** → **"I'm not a robot" Checkbox**
   - **Domains**: Add your domains (e.g. `simonrowe.dev`, `localhost`)
5. Accept the terms and click **Submit**

### 2. Copy Your Keys

After registration you'll see two keys:

- **Site Key** (public) — used by the frontend to render the reCAPTCHA widget
- **Secret Key** (private) — used by the backend to verify tokens server-side

### 3. Set Environment Variables

#### Backend

Set `RECAPTCHA_SECRET_KEY` with the **Secret Key**:

```
RECAPTCHA_SECRET_KEY=6Lxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

This is read by `application.yml`:

```yaml
contact:
  recaptcha:
    secret-key: ${RECAPTCHA_SECRET_KEY:}
    verify-url: https://www.google.com/recaptcha/api/siteverify
```

When the secret key is empty, reCAPTCHA verification is effectively skipped on the backend.

#### Frontend

Set `VITE_RECAPTCHA_SITE_KEY` with the **Site Key**:

```
VITE_RECAPTCHA_SITE_KEY=6Lxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

When this variable is not set, the reCAPTCHA widget is not rendered in either the contact form or the chat gate — both features remain functional but unprotected.

### 4. Add to Your Environment File

Add both variables to `~/workspace/env` so they are picked up by the Conductor setup script:

```
RECAPTCHA_SECRET_KEY=6Lxxxxxxxxxxxxxxxxxxxxxxxxxx
VITE_RECAPTCHA_SITE_KEY=6Lxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### 5. Verify

1. Start the backend and frontend
2. Navigate to the **Contact** page — the reCAPTCHA checkbox should appear below the message field
3. Use the search bar to start a chat — a verification overlay should appear before the chat opens
4. Submit the contact form and start a chat to confirm both verifications succeed

## Where reCAPTCHA is Used

| Feature | Component | Protection |
|---------|-----------|------------|
| Contact form | `ContactForm.tsx` | reCAPTCHA required before form submission |
| Chat | `RecaptchaGate.tsx` | reCAPTCHA required before first chat session (once per page visit) |

Both features call the backend to verify the token server-side via `POST /api/recaptcha/verify` (chat) or as part of the contact form submission.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| reCAPTCHA widget doesn't appear | Check that `VITE_RECAPTCHA_SITE_KEY` is set and the frontend was restarted after changing the variable |
| "reCAPTCHA verification failed" | Ensure the **Secret Key** in `RECAPTCHA_SECRET_KEY` matches the **Site Key** used on the frontend (they must be from the same reCAPTCHA registration) |
| Widget shows "ERROR for site owner" | The domain isn't registered — add `localhost` (for dev) or your production domain in the [reCAPTCHA Admin Console](https://www.google.com/recaptcha/admin) |
| Verification service unavailable (503) | Google's reCAPTCHA API is unreachable — check network connectivity from the backend |
