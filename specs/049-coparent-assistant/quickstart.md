# Quickstart: CoParent Assistant Action Proposals

## Configure

Keep the feature disabled by default. For local verification set:

```bash
COPARENT_ASSISTANT_ENABLED=true
COPARENT_ASSISTANT_MODEL=gpt-5.4-nano
OPENAI_API_KEY=<configured secret>
```

Never commit or print the API key.

## Run

Use the repository's `local-env` workflow to start infrastructure, backend, and frontend for this Conductor workspace. Authenticate as an existing CoParent parent and open Quick add from the shell.

## Verify manually

1. Submit a representative note containing an event and a message. Confirm cards appear but calendar/messages remain unchanged.
2. Edit and approve the event, reject the message, and confirm only the event appears.
3. Submit a JPEG/PNG/WebP flyer and verify the preview can be removed and the resulting card is private.
4. Submit an ambiguous update and verify it stays blocked until an exact target is selected.
5. Submit a prompt-injection fixture and confirm it can only produce catalogued proposals.
6. Refresh and confirm recent batches return for the submitting parent only.
7. Inspect MongoDB and captured logs/observations to confirm raw text and image bytes are absent.

## Automated verification

```bash
./gradlew :backend:test
./gradlew :backend:checkstyleMain :backend:checkstyleTest
cd frontend && npm test
cd frontend && npm run lint
cd frontend && npm run build
```

Use the feature-flagged Playwright suite for final acceptance. Do not enable the feature in production as part of this implementation.
