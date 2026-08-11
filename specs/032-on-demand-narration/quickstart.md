# Quickstart: On-Demand Blog Narration

## Prerequisites

1. Follow `docs/setup/google-cloud-tts.md` to create/choose a Google Cloud project, enable billing and Cloud Text-to-Speech, configure ADC, and set the narration environment variables.
2. Ensure MongoDB and Kafka are available through the normal local environment.
3. Leave narration disabled when credentials are absent; the rest of the site must still start and operate normally.

## Local start

```bash
./scripts/start.sh
```

Open a published blog at `http://localhost:5173/blogs/{id}` in a signed-out browser.

## Primary verification

1. Confirm the page initially shows “Listen to this post” and does not generate on page load.
2. Select Listen once.
3. Confirm the control changes immediately to “Preparing audio. You can keep reading.”
4. In another signed-out window, select Listen for the same blog and confirm it observes the same pending job.
5. Wait for the native player to appear without reloading.
6. Play, pause, seek, and select 1.5× speed; confirm the page never autoplays.
7. Reload both pages and confirm ready audio appears without another Google generation.
8. Check that one final file exists under `backend/uploads/narrations/{narrationId}/narration.mp3`, no partial local file remains, and the temporary Cloud Storage object is covered by the bucket lifecycle rule.

## Content-version verification

1. Edit the blog prose as an administrator and publish the change.
2. Reload the public blog and confirm the old narration is not offered as current.
3. Request narration and confirm one replacement is produced.
4. Unpublish the blog and confirm both blog and narration endpoints return not found.

## Failure and budget verification

1. Set the monthly character limit below the test blog's speech-safe character count.
2. Request uncached narration and confirm no Google call begins while cached narrations remain playable.
3. Restore the normal limit and temporarily provide an invalid voice name.
4. Confirm the public response is sanitized and retry is offered only for an explicitly safe provider failure.
5. Simulate a provider timeout after request dispatch and confirm the record becomes non-auto-retryable rather than issuing a second call.

## Backup/restore verification

1. Create narration for a published blog.
2. Run a full backup through the Data Operations UI.
3. Confirm the manifest includes the `narrations` collection and counts the MP3 among media files.
4. Clear local data and restore that backup through the Data Operations UI.
5. Confirm the restored narration plays without Google generation.
6. Remove a narration MP3 from a test archive, restore it, and confirm its record is withheld and safely retryable.
7. Restore an older archive without `narrations` and confirm the restore succeeds.

## Automated validation

```bash
cd backend && ../gradlew test check
cd ../frontend && npm test && npm run lint && npm run build
```

Google calls are mocked behind the provider boundary in automated tests. A real credentialed smoke test is manual and must not run in CI.
