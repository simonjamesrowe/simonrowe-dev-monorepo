# Implementation Plan: On-Demand Blog Narration

**Branch**: `simonrowe/feat/audio-on-demand` | **Date**: 2026-08-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/032-on-demand-narration/spec.md`

## Summary

Add public, on-demand narration to published blog detail pages. A first request creates one Mongo-backed narration record and publishes an idempotent Kafka job. The worker submits speech-safe blog prose to Google Cloud Text-to-Speech Long Audio with a deterministic temporary Cloud Storage destination, persists the provider operation ID, downloads and validates one MP3 under the existing uploads volume, and exposes durable status through bounded long polling. A unique source/configuration fingerprint, atomic work claiming, operation resumption, and conservative handling of ambiguous provider calls prevent concurrent duplication. Narration metadata and files join the existing full backup/restore lifecycle.

## Technical Context

**Language/Version**: Java 21; TypeScript 5.7; React 19

**Primary Dependencies**: Spring Boot 3.5.x, Spring Kafka, Spring Data MongoDB, existing Google Auth Library OAuth2 HTTP client, Spring `RestClient`, CommonMark, Bucket4j, Micrometer, React Testing Library, Vitest

**Storage**: MongoDB `narrations` collection, final MP3 files below the existing configurable uploads directory, and a private lifecycle-managed Cloud Storage bucket for temporary Long Audio output

**Testing**: JUnit 5, Mockito, Testcontainers-backed Spring integration tests, Gradle check/checkstyle/JaCoCo; Vitest and React Testing Library for the critical frontend journey

**Target Platform**: JVM backend and Nginx-served React frontend in separate Linux containers; production is ARM64 Raspberry Pi

**Project Type**: Full-stack web application in the existing monorepo

**Performance Goals**: Ready playback visible within 2 seconds for 95% of requests; uncached request acknowledged within 2 seconds; blogs up to 20,000 spoken characters ready within 5 minutes when Google is healthy; clear delayed state after 100 seconds; long-poll waits capped at 25 seconds each

**Constraints**: Anonymous ID-only trigger; Google billing and a private temporary output bucket must be configured; Long Audio input is capped at 1,000,000 UTF-8 bytes while the application defaults to 50,000 spoken characters; one authoritative asset per script/voice version; no automatic retry after an ambiguous provider start; 1,000,000-character default monthly application budget; Cloud Storage is temporary only; generated files must support HTTP range seeking and full backup/restore

**Scale/Scope**: Published blog detail pages only; low-volume personal site; one Kafka narration consumer; no news, events, bulk generation, playlists, multilingual voices, or admin narration UI in v1

## Constitution Check

*GATE: Passed before research and re-checked after design.*

- **Separate containers**: No container boundary changes. React calls the existing backend and audio remains under backend-served `/uploads/**`.
- **Modern Java & React**: Uses Java 21, React 19, MongoDB for required durable state, Kafka for asynchronous generation, Bucket4j for public endpoint rate limits, Lucide icons, BEM CSS, and the existing uploads resource handler.
- **AI provider restriction**: Google Cloud Text-to-Speech is a specialized speech synthesizer for a new non-chat capability. It does not replace or alter the constitution-mandated OpenAI chat and embedding stack.
- **Native image**: The repository currently runs the backend in JVM mode because the Graal plugin is disabled for Embabel. Google Cloud Java libraries include native reachability metadata; client construction is isolated in configuration and the setup guide records the native verification requirement if native builds are re-enabled.
- **Quality gates**: Backend unit and integration coverage plus frontend critical-journey tests are included. External Google calls are behind a provider interface and are never used by ordinary automated tests.
- **Observability**: Structured state-transition logs and Micrometer counters/timers cover generated, reused, failed, uncertain, and character totals without visitor identity.
- **Simplicity**: One provider and voice, one Mongo collection, one Kafka topic, one public component, and existing uploads/backup infrastructure. Cloud Storage is used only as the provider-required temporary destination and is lifecycle-cleaned.
- **Backup & restore**: `narrations` joins the backup collection set; audio is already captured beneath uploads; restore validates record/file consistency and remains compatible with older archives.

## Project Structure

### Documentation (this feature)

```text
specs/032-on-demand-narration/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── narration-api.yaml
└── tasks.md

docs/setup/
└── google-cloud-tts.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/simonrowe/
├── narration/
│   ├── BlogNarrationController.java
│   ├── BlogNarrationService.java
│   ├── BlogNarrationScriptBuilder.java
│   ├── GoogleTextToSpeechConfig.java
│   ├── GoogleTextToSpeechProvider.java
│   ├── Narration.java
│   ├── NarrationProperties.java
│   ├── NarrationRepository.java
│   ├── NarrationResponse.java
│   ├── NarrationStatus.java
│   ├── NarrationProvider.java
│   ├── NarrationRequestPublisher.java
│   ├── NarrationRequestConsumer.java
│   ├── NarrationRecoveryScheduler.java
│   └── NarrationRestoreValidator.java
├── events/
│   └── NarrationRequestEvent.java
├── dataops/
│   ├── BackupService.java
│   ├── RestoreService.java
│   └── ClearService.java
├── ratelimit/
│   ├── RateLimitConfig.java
│   └── RateLimitInterceptor.java
└── WebConfig.java

backend/src/test/java/com/simonrowe/narration/
├── BlogNarrationControllerTest.java
├── BlogNarrationServiceTest.java
├── BlogNarrationScriptBuilderTest.java
├── GoogleTextToSpeechProviderTest.java
├── NarrationRequestConsumerTest.java
└── NarrationRestoreValidatorTest.java

frontend/src/
├── components/blog/
│   ├── BlogDetail.tsx
│   └── BlogNarration.tsx
├── services/blogApi.ts
├── types/blog.ts
└── styles.css

frontend/src/components/blog/
└── BlogNarration.test.tsx
```

**Structure Decision**: Extend the existing backend and frontend modules. Narration has its own backend package because it owns public API, durable state, Kafka work, provider integration, media assembly, and restore validation. Frontend behavior stays in a dedicated blog component while reusing the existing blog API module and global BEM stylesheet.

## Complexity Tracking

No constitution violations require an exception. Durable narration state is justified by deduplication, restart recovery, cost accounting, and backup/restore requirements. Kafka is mandated by both the user and constitution. The provider interface is justified by isolating chargeable network calls in tests, not speculative multi-provider support.
