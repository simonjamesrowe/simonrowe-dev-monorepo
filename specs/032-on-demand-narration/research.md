# Research: On-Demand Blog Narration

## Google speech model and API integration

**Decision**: Use Google Cloud Text-to-Speech Long Audio through its documented `v1beta1` REST API, authenticated with the Google Auth Library already present in the backend. Configure both the human-readable project ID and numeric project number required by the Long Audio resource path. Use a configurable `en-GB` Chirp 3 HD voice and `global` location by default, with one deterministic private Cloud Storage output URI per narration.

**Rationale**: Chirp 3 HD supports English UK voices and MP3 batch output and currently includes one million characters per month before paid usage. Long Audio accepts up to one million bytes, produces one seekable MP3 instead of several independently encoded streams, and returns a durable operation name that can be resumed after application failure. Direct REST avoids a second generated Google SDK because the repository already has OAuth credential support and Spring HTTP infrastructure.

**Alternatives considered**:

- OpenAI TTS: simplest existing credential path but rejected after the user chose Google and it has no comparable recurring free allowance.
- Amazon Polly: good neural/news voices, but introduces a second cloud account and no better long-term free allowance.
- ElevenLabs: expressive but materially more expensive for this low-volume accessibility feature.
- Unary Google synthesis: hard 5,000-byte input cap requires media joining and several chargeable network calls for one blog.

Sources: [Chirp 3 HD](https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd), [Long Audio](https://docs.cloud.google.com/text-to-speech/docs/create-audio-text-long-audio-synthesis), [pricing](https://cloud.google.com/text-to-speech/pricing)

## Long Audio output and final storage

**Decision**: Build deterministic speech-safe prose, submit it once to Long Audio with a deterministic `gs://` output object, persist the returned long-running operation name, poll that operation, download the completed MP3 to a local temporary file, validate it, and atomically move it under uploads. Configure a one-day bucket lifecycle so abandoned provider objects are removed without granting broad deletion rights.

**Rationale**: The provider creates one correctly framed MP3 with reliable browser duration and seeking, while the operation ID and deterministic destination make poll/download recovery safe without repeating synthesis. The completed asset is copied into the existing locally backed-up media boundary; Cloud Storage is not a public or permanent origin.

**Alternatives considered**:

- Concatenated unary MP3: avoids Cloud Storage but risks incorrect duration or seeking and repeats multiple provider calls.
- Bidirectional streaming: Chirp-compatible but adds a gRPC stream and local media assembly.
- Local PCM-to-MP3 encoding: adds an encoder or runtime binary to the production image.

Sources: [Long Audio](https://docs.cloud.google.com/text-to-speech/docs/create-audio-text-long-audio-synthesis), [audio encodings](https://docs.cloud.google.com/text-to-speech/docs/reference/rest/v1/AudioEncoding)

## Authentication and production credentials

**Decision**: Use Application Default Credentials. Local development uses `gcloud auth application-default login`; production mounts a dedicated service-account JSON file read-only and sets `GOOGLE_APPLICATION_CREDENTIALS` to its container path. Google Drive OAuth credentials are not reused.

**Rationale**: ADC is the official client-library path. Workload Identity Federation is preferable where a suitable external identity provider exists, but a narrowly scoped, dedicated service-account key is the practical documented fallback for an on-premises Raspberry Pi. Repository and Compose configuration store only the file path, never credential contents.

**Alternatives considered**:

- Put JSON in an environment variable: workable but harder to rotate and more easily exposed via process/config inspection.
- Reuse Drive OAuth refresh tokens: wrong principal and scope for server-to-server Cloud TTS.
- Commit a credential file: prohibited.

Sources: [authentication](https://docs.cloud.google.com/text-to-speech/docs/authentication), [on-premises ADC](https://docs.cloud.google.com/docs/authentication/set-up-adc-on-premises)

## Idempotent Kafka processing

**Decision**: Treat Kafka as at-least-once. Insert narration state under a unique source/config fingerprint before publishing; key the Kafka record by narration ID; atomically claim queued work; persist the Long Audio operation name immediately; and use a lease/recovery scan for abandoned work. A known operation is resumed without new synthesis. If provider start may have succeeded but neither an operation name nor output can be reconciled, mark it uncertain and do not auto-retry.

**Rationale**: Kafka delivery and application restarts cannot create exactly-once external side effects. Database uniqueness and conditional state transitions guarantee one authoritative asset. The provider operation and deterministic output URI eliminate ordinary repeat charges, while the uncertain state closes the unavoidable network ambiguity window conservatively.

**Alternatives considered**:

- Kafka exactly-once transactions: do not cover the external Google call or filesystem write.
- In-memory locks: fail across processes and restarts.
- Provider call in the public request: blocks visitors and cannot recover independently of the client.

## Browser status and playback

**Decision**: On mount, fetch status without generating. POST only after an explicit Listen or Retry. While queued/processing, issue sequential bounded long polls of up to 25 seconds using a monotonic response version; cap automatic waiting near 100 seconds and then offer Check status. Use native audio controls plus an explicit playback-speed selector and never autoplay newly completed audio.

**Rationale**: Long polling is simpler than webhooks or a permanent socket for a low-volume site, works across application instances through durable Mongo state, and clearly separates observation from generation. Native controls provide the strongest baseline keyboard and screen-reader support.

**Alternatives considered**:

- Webhooks: browsers cannot receive them directly.
- SSE/WebSocket: existing infrastructure exists but a permanent anonymous connection is unnecessary for one state transition.
- Fixed-interval short polling: creates more requests and slower feedback.

## Backup and restore

**Decision**: Store final audio beneath `UPLOADS_PATH/narrations/`, add `narrations` to database backup/restore/clear collections, and validate every restored narration after uploads are extracted. Ready records missing valid files and in-progress records become retryable; old archives without the collection remain valid. The provider bucket is temporary and never replaces the full local media backup.

**Rationale**: The current full backup already walks the entire uploads tree, so this keeps one self-contained disaster-recovery unit and avoids a second storage pipeline.

**Alternatives considered**:

- Google Cloud Storage as permanent media: would require a second backup source and public-serving policy.
- Regenerate after restore: wastes paid generation and fails the full-with-media policy.
