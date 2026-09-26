# Implementation Plan: CoParent Assistant Action Proposals

**Branch**: `simonrowe/feat/coparent-llm-actions` | **Date**: 2026-09-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/049-coparent-assistant/spec.md`

## Summary

Add a feature-gated CoParent Quick add drawer that submits bounded text and/or one validated image to a directly invoked Spring AI `ChatModel`. The model can emit only strict inert tool calls, which are normalized into seven-day private proposal batches. The submitting parent edits and approves or rejects every action separately. Approval revalidates all access and domain rules and uses persisted action markers plus preallocated identifiers for retry-safe execution. The frontend uses the existing React shell, React Query, React Hook Form, Zod, and CoParent design system.

## Technical Context

**Language/Version**: Java 25; TypeScript 5.7; React 19

**Primary Dependencies**: Spring Boot 4.1.1, Spring AI 2.0.1 OpenAI, Spring Data MongoDB, Jakarta Validation, React Query, React Hook Form, Zod, Vaul, Lucide React

**Storage**: Dedicated CoParent MongoDB database; proposal batches in an ephemeral TTL collection; raw submissions are never stored

**Testing**: JUnit 5, Mockito, Spring MockMvc, Testcontainers MongoDB, Vitest/Testing Library, Playwright

**Target Platform**: Existing ARM64/Linux containers and modern desktop/mobile browsers

**Project Type**: Monorepo web application with separate Spring API and React SPA containers

**Performance Goals**: One inference request per submission; bounded 500-event context; responsive review UI; no synchronous provider calls during approval

**Constraints**: Feature off by default; 20,000 text characters; one 10 MB JPEG/PNG/WebP; no raw source persistence/logging/telemetry; no model-triggered mutations; seven-day expiry; no Mongo transaction dependency

**Scale/Scope**: Eleven proposal types, one global drawer, five assistant endpoint patterns plus per-action decisions, current family data only

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Separate containers**: PASS. No deployment topology change.
- **Modern Java/React stack**: PASS. Reuses Spring AI/OpenAI, MongoDB, Auth0, React Hook Form, Zod, plain BEM CSS, and Lucide React.
- **No host process launch**: PASS. The assistant invokes only the configured model and in-process domain services.
- **Quality gates**: PASS. Unit, Testcontainers integration, frontend, and Playwright coverage are planned.
- **Observability/privacy**: PASS. Assistant observations explicitly suppress content while retaining operational measurements.
- **Simplicity**: PASS. One batch aggregate and a fixed proposal catalog; no autonomous execution, chat loop, quota system, or new provider SDK.

Post-design re-check: PASS. The contract and data model retain all gates; retry safety is localized to action execution rather than introducing distributed transactions.

## Project Structure

### Documentation (this feature)

```text
specs/049-coparent-assistant/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/openapi.yaml
└── tasks.md
```

### Source Code (repository root)

```text
backend/
└── src/
    ├── main/java/com/simonrowe/coparent/assistant/
    ├── main/java/com/simonrowe/migration/changeunits/V045CreateCoparentAssistantSchema.java
    └── test/java/com/simonrowe/coparent/assistant/

frontend/
└── src/coparent/
    ├── components/assistant/
    ├── hooks/api/useAssistant.ts
    └── types/assistant.ts
```

**Structure Decision**: Extend the existing CoParent backend bounded context with a cohesive `assistant` package and mount a feature component in the existing app shell. Keep assistant persistence, inference, normalization, and execution separate so model output never crosses directly into domain mutation code.

## Complexity Tracking

No constitution violations require justification.
