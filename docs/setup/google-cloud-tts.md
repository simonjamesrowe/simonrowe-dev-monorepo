# Google Cloud Text-to-Speech setup

This runbook configures on-demand narration for published blogs and for generated
article summaries, using a single British English Chirp 3 HD voice.

> **Updated 2026-08-25.** Synthesis now goes through the ordinary
> `v1/text:synthesize` endpoint, which returns MP3 directly, rather than `v1beta1`
> Long Audio. Long Audio rejects MP3 outright — "only LINEAR16 audio encodings are
> supported for Long Audio Synthesis" — and LINEAR16 would multiply stored audio
> roughly tenfold. The ordinary endpoint caps input at 5,000 UTF-8 bytes per
> request, so `NarrationScriptChunker` splits longer scripts at sentence
> boundaries and the resulting MP3 frames are concatenated. A 14,000-character
> blog is about three requests.
>
> Two consequences for this runbook:
> - **The Cloud Storage bucket is no longer on the synthesis path.** Sections 2 and
>   3 below still apply if you want the Long Audio route available, but narration
>   works without a bucket. `GOOGLE_CLOUD_TTS_OUTPUT_BUCKET` must still be set to a
>   non-blank value because `NarrationProperties.isProviderConfigured()` requires it.
> - **`x-goog-user-project` is now sent on every call.** Without it, user
>   Application Default Credentials are rejected with "the API requires a quota
>   project", and Google attributes the request to gcloud's own client project.

Narration is disabled by default. The rest of the site starts normally without a
Google project, bucket, or credential file.

## What this creates

- One Google Cloud project with billing and the Cloud Text-to-Speech API enabled.
- One private Cloud Storage bucket in the same project.
- A lifecycle rule that deletes temporary objects after one day.
- One dedicated service account with object-create and object-read access only on
  that bucket.
- For the Raspberry Pi, one service-account JSON key stored outside the repository
  and mounted read-only into the backend container.

Google Long Audio is a Preview feature. Its input limit is 1,000,000 UTF-8 bytes;
the application applies a lower default limit of 50,000 spoken characters.

## 1. Choose the project and enable billing

Install the [Google Cloud CLI](https://cloud.google.com/sdk/docs/install), sign in,
and choose a project. Project IDs and bucket names are globally unique, so replace
the examples below.

```bash
gcloud auth login
gcloud projects create YOUR_PROJECT_ID --name="simonrowe.dev narration"
gcloud config set project YOUR_PROJECT_ID
```

If an existing project is used, omit the create command. In Google Cloud Console,
open **Billing > My projects** and link the project to a billing account. Billing
must be enabled even while usage remains inside the free allowance.

Set reusable shell values and enable the required services:

```bash
export TTS_PROJECT_ID=YOUR_PROJECT_ID
export TTS_PROJECT_NUMBER="$(gcloud projects describe "$TTS_PROJECT_ID" \
  --format='value(projectNumber)')"
export TTS_BUCKET=YOUR_GLOBALLY_UNIQUE_BUCKET_NAME
export TTS_SERVICE_ACCOUNT_NAME=simonrowe-tts
export TTS_SERVICE_ACCOUNT_EMAIL="${TTS_SERVICE_ACCOUNT_NAME}@${TTS_PROJECT_ID}.iam.gserviceaccount.com"

gcloud config set project "$TTS_PROJECT_ID"
gcloud services enable texttospeech.googleapis.com storage.googleapis.com
```

Keep both values: ordinary project administration uses `TTS_PROJECT_ID`, while
the Long Audio `v1beta1` resource path requires `TTS_PROJECT_NUMBER`.

Do not use the existing Google Drive OAuth client ID, client secret, or refresh
token. Text-to-Speech uses Application Default Credentials (ADC) and a separate
server identity.

## 2. Create the private temporary bucket

The application configuration expects a bare bucket name, not a `gs://` URI.
`EU` keeps the temporary object in Google's EU multi-region; Long Audio itself uses
the documented `global` location by default.

```bash
gcloud storage buckets create "gs://${TTS_BUCKET}" \
  --project="$TTS_PROJECT_ID" \
  --location=EU \
  --uniform-bucket-level-access \
  --public-access-prevention \
  --soft-delete-duration=0
```

Soft delete is disabled on this temporary exchange bucket so deleted output is not
retained and billed for another seven days. Apply the mandatory one-day deletion
rule:

```bash
export TTS_LIFECYCLE_FILE="$(mktemp)"
printf '%s\n' '{"rule":[{"action":{"type":"Delete"},"condition":{"age":1}}]}' > "$TTS_LIFECYCLE_FILE"
gcloud storage buckets update "gs://${TTS_BUCKET}" --lifecycle-file="$TTS_LIFECYCLE_FILE"
rm "$TTS_LIFECYCLE_FILE"
unset TTS_LIFECYCLE_FILE
```

Confirm that public access prevention, uniform access, and the lifecycle rule are
present before continuing:

```bash
gcloud storage buckets describe "gs://${TTS_BUCKET}"
```

The bucket must stay private. Do not grant `allUsers` or `allAuthenticatedUsers`
access and do not configure it as a website.

## 3. Create and authorize the runtime identity

Create a service account dedicated to narration:

```bash
gcloud iam service-accounts create "$TTS_SERVICE_ACCOUNT_NAME" \
  --project="$TTS_PROJECT_ID" \
  --display-name="simonrowe.dev blog narration"
```

Long Audio requires the caller to create the output object and later read it. Grant
the two roles Google documents, scoped to this bucket rather than the project:

```bash
gcloud storage buckets add-iam-policy-binding "gs://${TTS_BUCKET}" \
  --member="serviceAccount:${TTS_SERVICE_ACCOUNT_EMAIL}" \
  --role="roles/storage.objectCreator"

gcloud storage buckets add-iam-policy-binding "gs://${TTS_BUCKET}" \
  --member="serviceAccount:${TTS_SERVICE_ACCOUNT_EMAIL}" \
  --role="roles/storage.objectViewer"
```

Cloud Text-to-Speech does not currently expose a product-specific runtime IAM role
for ordinary synthesis. Do not invent or grant `roles/texttospeech.user`. API
enablement, an authenticated principal from the same project, and the bucket roles
above are the required runtime setup. In particular, do not grant Owner or Editor.

Review the resulting bucket policy:

```bash
gcloud storage buckets get-iam-policy "gs://${TTS_BUCKET}"
```

## 4. Configure cost controls

As of August 2026, Chirp 3 HD includes the first 1,000,000 characters each month,
then costs US$30 per million characters. Spaces and newlines count. Confirm current
pricing before enabling production because provider pricing can change:
[Cloud Text-to-Speech pricing](https://cloud.google.com/text-to-speech/pricing).

The application defaults `NARRATION_MONTHLY_CHARACTER_LIMIT` to `1000000` and
checks it before starting new provider work. Keep that limit at or below the amount
you are prepared to generate. Cached audio remains available after the limit is
reached.

Also configure Google-side warnings:

1. Open **Billing > Budgets & alerts** in Google Cloud Console.
2. Create a project-scoped monthly budget for the narration project.
3. Add actual-spend thresholds at 50%, 90%, and 100%, and confirm the notification
   email or Pub/Sub destination.
4. Open **APIs & Services > Cloud Text-to-Speech API > Quotas & System Limits**.
5. Review `LongAudioSynthesisRequestsPerMinutePerProject` and
   `LongAudioSynthesisQueryRequestsPerMinutePerProject`. Lower them for this
   low-volume site if Google permits the requested override.

A billing budget sends alerts; it does not stop charges. API request quotas also do
not cap generated characters. The application's monthly character budget is the
primary generation ceiling.

## 5. Local development

For local development, use user ADC instead of creating another service-account
key. The signed-in account must have access to the project and the temporary bucket.

```bash
gcloud auth application-default login
gcloud auth application-default set-quota-project "$TTS_PROJECT_ID"
```

Add the following to the workspace's `backend/.env`. Do not set
`GOOGLE_APPLICATION_CREDENTIALS` locally when using `gcloud` user ADC.

```dotenv
NARRATION_ENABLED=true
GOOGLE_CLOUD_TTS_PROJECT_ID=YOUR_PROJECT_ID
GOOGLE_CLOUD_TTS_PROJECT_NUMBER=YOUR_NUMERIC_PROJECT_NUMBER
GOOGLE_CLOUD_TTS_OUTPUT_BUCKET=YOUR_GLOBALLY_UNIQUE_BUCKET_NAME
GOOGLE_CLOUD_TTS_LOCATION=global
GOOGLE_CLOUD_TTS_LANGUAGE_CODE=en-GB
GOOGLE_CLOUD_TTS_VOICE_NAME=en-GB-Chirp3-HD-Charon
NARRATION_MAX_BLOG_CHARACTERS=50000
NARRATION_MONTHLY_CHARACTER_LIMIT=1000000
NARRATION_POLL_INTERVAL=5s
NARRATION_OPERATION_TIMEOUT=5m
NARRATION_LEASE_DURATION=2m
NARRATION_RECOVERY_DELAY=30s
NARRATION_RATE_LIMIT_REQUESTS_PER_MINUTE=10
```

Start the normal local environment:

```bash
./scripts/start.sh
```

Open a published blog signed out and select **Listen**. A successful first request
should create a temporary object below `gs://BUCKET/narrations/`, then a final file
below `backend/uploads/narrations/`. The browser should transition from preparation
to a seekable MP3 without a page reload. Ordinary automated tests keep narration
disabled and must never use local ADC.

To return to a zero-provider-call local setup, set `NARRATION_ENABLED=false` and
restart the backend.

## 6. Raspberry Pi production credentials

The Pi runs outside Google Cloud. Workload Identity Federation is preferred when a
suitable external identity provider is available. The current Pi deployment does
not have one, so use a dedicated service-account key as the documented fallback.
Treat the JSON as a production secret: never commit it, paste it into `.env`, or
place it inside the repository.

Create the key on an authenticated administration machine:

```bash
export TTS_KEY_DIRECTORY="$(mktemp -d)"
export TTS_KEY_FILE="${TTS_KEY_DIRECTORY}/google-cloud-tts.json"
gcloud iam service-accounts keys create "$TTS_KEY_FILE" \
  --iam-account="$TTS_SERVICE_ACCOUNT_EMAIL" \
  --project="$TTS_PROJECT_ID"
chmod 0600 "$TTS_KEY_FILE"
```

Transfer that file to the Pi using a secure channel. On the Pi, install it outside
the checkout with root-only permissions:

```bash
sudo install -d -m 0700 -o root -g root /opt/simonrowe/secrets
sudo install -m 0400 -o root -g root /PATH/TO/google-cloud-tts.json \
  /opt/simonrowe/secrets/google-cloud-tts.json
```

Delete the administration-machine copy after the Pi copy has been verified:

```bash
rm "$TTS_KEY_FILE"
rmdir "$TTS_KEY_DIRECTORY"
unset TTS_KEY_FILE TTS_KEY_DIRECTORY
```

Add these values to the production checkout's root `.env` on the Pi:

```dotenv
NARRATION_ENABLED=true
GOOGLE_CLOUD_TTS_CREDENTIALS_PATH=/opt/simonrowe/secrets/google-cloud-tts.json
GOOGLE_CLOUD_TTS_PROJECT_ID=YOUR_PROJECT_ID
GOOGLE_CLOUD_TTS_PROJECT_NUMBER=YOUR_NUMERIC_PROJECT_NUMBER
GOOGLE_CLOUD_TTS_OUTPUT_BUCKET=YOUR_GLOBALLY_UNIQUE_BUCKET_NAME
GOOGLE_CLOUD_TTS_LOCATION=global
GOOGLE_CLOUD_TTS_LANGUAGE_CODE=en-GB
GOOGLE_CLOUD_TTS_VOICE_NAME=en-GB-Chirp3-HD-Charon
NARRATION_MAX_BLOG_CHARACTERS=50000
NARRATION_MONTHLY_CHARACTER_LIMIT=1000000
NARRATION_POLL_INTERVAL=5s
NARRATION_OPERATION_TIMEOUT=5m
NARRATION_LEASE_DURATION=2m
NARRATION_RECOVERY_DELAY=30s
NARRATION_RATE_LIMIT_REQUESTS_PER_MINUTE=10
```

Compose mounts the host file read-only at
`/run/secrets/google-cloud-tts.json` and sets the standard
`GOOGLE_APPLICATION_CREDENTIALS` path inside the backend container. When narration
is disabled and no host path is configured, Compose mounts `/dev/null` so existing
deployments remain bootable.

Validate configuration and restart only the backend:

```bash
docker compose -f docker-compose.prod.yml config --quiet
docker compose -f docker-compose.prod.yml up -d --no-deps backend
docker compose -f docker-compose.prod.yml exec backend \
  sh -c 'test -r "$GOOGLE_APPLICATION_CREDENTIALS"'
docker compose -f docker-compose.prod.yml ps backend
```

Then request narration for one published blog through the public site. Confirm that
the player becomes ready, the resulting MP3 seeks correctly, and no credential path
or Google response body appears in the public response or logs.

## Disable, rollback, and rotate

To stop all new chargeable generation while retaining existing ready narration, set
`NARRATION_ENABLED=false` and restart the backend. Do not delete the local narration
files; they are durable media and participate in full backup and restore.

To rotate the service-account key:

1. Create a new key for the same dedicated service account.
2. Install it on the Pi under a new root-only filename.
3. Update `GOOGLE_CLOUD_TTS_CREDENTIALS_PATH` and restart the backend.
4. Verify a provider operation with the new key.
5. Delete the old key in **IAM & Admin > Service Accounts > Keys**, then remove the
   old file from the Pi.

If narration is permanently removed, disable it first, revoke and delete all keys
for the dedicated service account, remove the bucket IAM bindings, and delete the
temporary bucket only after confirming it contains no provider operation still in
progress.

## References

- [Get started with Cloud Text-to-Speech](https://docs.cloud.google.com/text-to-speech/docs/get-started)
- [Create long-form audio](https://docs.cloud.google.com/text-to-speech/docs/create-audio-text-long-audio-synthesis)
- [Chirp 3 HD voices](https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd)
- [Cloud Text-to-Speech quotas](https://docs.cloud.google.com/text-to-speech/quotas)
- [ADC for on-premises workloads](https://docs.cloud.google.com/docs/authentication/set-up-adc-on-premises)
- [Cloud Storage lifecycle rules](https://docs.cloud.google.com/storage/docs/lifecycle)
