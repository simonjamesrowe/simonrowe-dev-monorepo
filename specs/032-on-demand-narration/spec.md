# Feature Specification: On-Demand Blog Narration

**Feature Branch**: `simonrowe/feat/audio-on-demand`

**Created**: 2026-08-11

**Status**: Draft

**Input**: User description: "Add Google-powered, on-demand text-to-speech for published blogs only. Unauthenticated visitors can request audio, generation happens asynchronously, concurrent requests are deduplicated so unchanged content is produced once, the frontend communicates when uncached audio is being prepared, and audio plus its records participate in backup and restore."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Listen to a Published Blog (Priority: P1)

As a visitor, I can listen to a published blog without signing in, so I can consume Simon's writing hands-free or with less visual effort.

**Why this priority**: This is the primary user value and accessibility benefit.

**Independent Test**: Open a published blog in a signed-out browser, request narration, and verify that accessible playback controls become available without an authentication prompt.

**Acceptance Scenarios**:

1. **Given** a published blog with ready audio, **When** a signed-out visitor selects "Listen", **Then** its player is available within 2 seconds and can play, pause, seek, change playback speed, and expose elapsed and total time.
2. **Given** playback controls are visible, **When** a keyboard-only or screen-reader visitor uses them, **Then** every control has an understandable name, focus indication, and operable keyboard behavior.
3. **Given** an unpublished, deleted, or unknown blog, **When** anyone attempts to request its narration, **Then** no blog content or audio is disclosed and a not-available response is shown.
4. **Given** one narration is playing, **When** the visitor starts another on the same page, **Then** the first narration pauses.

---

### User Story 2 - Prepare and Reuse Uncached Audio (Priority: P1)

As a visitor requesting narration for the first time, I receive clear feedback while the audio is prepared and the player appears automatically when ready, while all visitors share one preparation job for the unchanged blog.

**Why this priority**: On-demand generation controls cost and storage, but is acceptable only if the waiting experience is clear and anonymous requests cannot multiply charges.

**Independent Test**: Submit many simultaneous requests for one uncached blog and verify the preparing, delayed, ready, and failed states while only one authoritative job and resulting asset exist.

**Acceptance Scenarios**:

1. **Given** a published blog has no current narration, **When** a visitor selects "Listen", **Then** the action changes promptly to a non-blocking "Preparing audio" state that allows the visitor to keep reading.
2. **Given** preparation finishes while the visitor remains on the page, **When** readiness is reported, **Then** the preparing state is replaced by the player without a page reload.
3. **Given** preparation takes longer than normal, **When** the visitor is still waiting, **Then** the interface confirms that work is still in progress rather than presenting a false failure.
4. **Given** multiple visitors request the same unchanged blog and narration settings at the same time, **When** no current narration exists, **Then** they observe one preparation job and receive the same resulting asset.
5. **Given** the visitor leaves and later returns, **When** preparation is pending or complete, **Then** the current state is displayed without starting duplicate work.
6. **Given** preparation fails, **When** the failure is reported, **Then** the visitor sees a concise retryable message and retrying cannot create concurrent duplicate work.
7. **Given** the blog prose or narration settings materially change, **When** narration is next requested, **Then** stale audio is not served and exactly one replacement may be prepared.

---

### User Story 3 - Restore Narrations with Blogs (Priority: P2)

As the site owner, I want generated audio and its state included in full backups and restores, so restored blogs do not incur regeneration cost or present broken players.

**Why this priority**: Narrations are durable paid assets and must follow the site's full-with-media backup policy.

**Independent Test**: Back up ready, pending, and failed narration records, clear local data, restore the backup, and verify ready files play while incomplete records become safely recoverable.

**Acceptance Scenarios**:

1. **Given** ready narrations exist, **When** a full backup is created, **Then** audio files, blog relationships, and currency information are included and counted.
2. **Given** a valid full backup, **When** it is restored, **Then** every narration whose record and file are present is playable without regeneration.
3. **Given** a restored record claims audio is ready but its file is missing or invalid, **When** restore validation runs, **Then** it is marked recoverable and withheld from playback.
4. **Given** a backup contains in-progress work, **When** it is restored, **Then** that work moves to a safe retryable state.
5. **Given** an older backup contains no narration data, **When** it is restored, **Then** restore succeeds and narration can be generated afterward.

---

### User Story 4 - Control Cost and Diagnose Failures (Priority: P2)

As the site owner, I can observe narration usage and enforce protective limits, so anonymous access remains affordable and diagnosable.

**Why this priority**: Deduplication bounds repeated work, but operational visibility and hard limits protect against catalogue growth and provider failures.

**Independent Test**: Generate, reuse, fail, and retry narrations and verify operational records distinguish provider-bound work from reuse while enforcing the configured monthly ceiling.

**Acceptance Scenarios**:

1. **Given** narration requests occurred, **When** operational information is reviewed, **Then** generated, reused, pending, failed, character-count, and estimated-cost totals are distinguishable without storing visitor identities.
2. **Given** the monthly generation ceiling is reached, **When** uncached narration is requested, **Then** no chargeable work starts, cached audio remains playable, and the visitor sees a temporarily unavailable message.
3. **Given** the speech service is unavailable or rejects a request, **When** preparation fails, **Then** the failure is sanitized, recorded, and retried only when safe.

### Edge Cases

- The blog body is empty after formatting, contains only images or code, or exceeds the supported size.
- Markdown contains headings, links, inline code, fenced code, tables, image descriptions, HTML, or pronunciation-unfriendly symbols.
- A blog is edited, unpublished, or deleted while its narration is queued or playing.
- A provider returns empty, truncated, corrupt, or unsupported audio.
- Two application instances accept the same first request at nearly the same time.
- A completed job's state is saved but the audio file is not, or the file is saved but the state update fails.
- A client disconnects while waiting; generation must continue independently.
- A provider may have completed and charged for synthesis but its response is lost before the site stores it.
- A restored file is present but fails size, type, or integrity validation.
- The visitor's browser does not support the preferred audio format.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST make narration available without authentication for published blogs only.
- **FR-002**: The system MUST accept only an existing blog identifier and MUST NOT accept arbitrary visitor-supplied text for synthesis.
- **FR-003**: Narration MUST use the blog title and site-owned prose body while removing presentation markup, URLs, image syntax, navigation text, and other non-spoken formatting.
- **FR-004**: Narration MUST omit code blocks and complex tables while inserting a short natural indication that non-prose material was omitted when needed for comprehension.
- **FR-005**: The interface MUST label the result as generated narration.
- **FR-006**: Ready audio MUST expose accessible play, pause, seeking, playback speed, elapsed-time, and duration controls.
- **FR-007**: Only one narration MAY play at a time on a single page.
- **FR-008**: When current audio is absent, the system MUST acknowledge promptly, prepare asynchronously, and keep the page usable.
- **FR-009**: The interface MUST distinguish ready, preparing, delayed, failed, temporarily unavailable, and ineligible states.
- **FR-010**: A waiting visitor MUST learn when narration becomes ready or fails without manually refreshing.
- **FR-011**: Status waiting MUST be bounded, reconnectable, and MUST NOT create another generation job.
- **FR-012**: Generation MUST continue independently if the initiating visitor disconnects.
- **FR-013**: The system MUST maintain one authoritative state for each combination of blog prose version and narration settings.
- **FR-014**: Concurrent requests, redelivered work, retries, restarts, and multiple application instances MUST converge on one authoritative job and one current asset.
- **FR-015**: A ready, valid, current asset MUST be reused without invoking chargeable generation again.
- **FR-016**: A prose or narration-setting change MUST invalidate the old asset as current and permit one replacement on demand.
- **FR-017**: Generation MUST use bounded retries only for failures known to be safe to retry.
- **FR-018**: An ambiguous provider outcome MUST NOT be retried automatically unless the provider can deduplicate it.
- **FR-019**: Generated audio MUST pass type, non-zero size, and integrity validation before becoming ready.
- **FR-020**: Ready audio MUST use a broadly supported format and support seeking without a complete initial download.
- **FR-021**: Public request and status operations MUST be rate-limited per client while ready audio remains efficiently cacheable.
- **FR-022**: Configurable per-blog size and monthly chargeable-generation limits MUST be enforced before provider work begins.
- **FR-023**: Reaching a limit MUST NOT prevent playback of ready narration.
- **FR-024**: Provider credentials, internal errors, and sensitive configuration MUST never reach public clients or audio URLs.
- **FR-025**: State transitions, attempts, spoken character counts, duration, reuse counts, sanitized failures, and estimated cost MUST be recorded without persistent visitor identity.
- **FR-026**: Full backups MUST include narration records and all current ready audio within the same self-contained backup as blogs and existing media.
- **FR-027**: Backup manifests and summaries MUST report narration records and audio files.
- **FR-028**: Restore MUST recover valid ready narration without regeneration and validate every record-file pair.
- **FR-029**: Restore MUST move incomplete or inconsistent narration records to a safe retryable state and remain compatible with older backups.
- **FR-030**: Unpublishing or deleting a blog MUST immediately make its narration unavailable and schedule unreferenced audio for cleanup.
- **FR-031**: Blog eligibility MUST be checked again before publishing newly generated audio.

### Key Entities

- **Narration**: The authoritative state for one blog prose version and narration configuration, including lifecycle state, audio reference, format, duration, character count, integrity information, attempts, timestamps, reuse count, and sanitized failure classification.
- **Narration Job**: Durable preparation work related to exactly one Narration, allowing redelivery and recovery without duplicate public output.
- **Blog**: Existing published content whose title and speech-safe prose determine eligibility and narration currency.
- **Narration Asset**: Validated audio related to a ready Narration, with stable public reference, size, format, duration, and integrity value.
- **Usage Budget**: Configured per-blog and monthly limits plus accumulated chargeable characters and estimated cost.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: At least 95% of requests for ready narration display playback controls within 2 seconds under normal load.
- **SC-002**: At least 95% of uncached requests display a preparing state within 2 seconds without blocking reading or navigation.
- **SC-003**: At least 95% of eligible blogs up to 20,000 spoken characters become playable within 5 minutes when the speech service is healthy, with a clear delayed state after 100 seconds.
- **SC-004**: In 100 simultaneous first requests for one unchanged blog, all visitors receive the same outcome and exactly one authoritative asset is produced.
- **SC-005**: Redelivered work produces zero duplicate current assets across restart and multi-instance tests.
- **SC-006**: 100% of ready narrations in a successful full backup are playable after clear-and-restore without regeneration.
- **SC-007**: 100% of restored missing, corrupt, or incomplete narration states are withheld and become safely retryable.
- **SC-008**: Public narration controls pass keyboard operation and automated accessible-name, focus, and state checks with no serious violations.
- **SC-009**: Ready narration remains available when generation limits or provider outages are simulated.
- **SC-010**: The owner can account for all provider-bound characters as completed, failed, uncertain, or pending and distinguish them from cache reuse.

## Assumptions

- Version 1 supports published blogs only, with one consistent English voice and one compressed browser-compatible audio format.
- The selected managed speech provider is configured by the operator; provider credentials and billing setup are external prerequisites documented in the repository.
- Blogs are site-owned and may be narrated in full after speech-safe formatting. The feature does not summarize, translate, or narrate news, events, profiles, jobs, skills, or code examples.
- Narration is generated only on demand; bulk pre-generation and automatic generation on publication are out of scope.
- A bounded wait-for-status interaction is used in the browser. Visitors who leave receive no external notification; returning reveals durable status.
- Audio is stored within the existing durable uploads boundary and narration records join the full backup data set.
- Only the latest current narration per blog and configuration is public; superseded audio may be retained briefly for safe replacement and then cleaned up.
- Download-specific UI, playlists, continuous play, synchronized highlighting, podcast feeds, custom voices, multiple languages, and native mobile playback are out of scope.
- The site guarantees one authoritative job and one current asset. Absolute exactly-once provider billing after an ambiguous network failure depends on provider support; uncertain calls are not automatically retried.
