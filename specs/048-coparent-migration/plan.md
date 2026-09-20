# Implementation Plan: CoParent Migration

**Branch**: `simonrowe/plan-coparent-migration` | **Date**: 2026-09-19 | **Spec**:
[spec.md](./spec.md)

**Input**: [spec.md](./spec.md)

## Summary

Move the implemented CoParent product into the existing simonrowe.dev backend and frontend images,
then retire the separate NestJS API and React deployment after contract, data, security, browser,
backup, and production checks pass.

The backend becomes an isolated `com.simonrowe.coparent` product module behind
`/api/coparent/**`, using a dedicated `coparent` database in the existing Mongo container. The
frontend becomes a third Vite entry under `frontend/src/coparent`, served from
`coparents.simonrowe.dev` by the shared frontend container. It keeps the current product design and
PWA experience while using the destination's React/toolchain versions. No new runtime container is
introduced.

## Technical Context

**Language/Version**: Java 25 (`backend`), TypeScript 5.7 / React 19 (`frontend`)

**Primary Dependencies**: Existing Spring Boot 4.1.1 Web MVC, Security OAuth2 resource server,
Validation, Spring Data MongoDB, Mongock, Spring Mail, Micrometer/OpenTelemetry; existing frontend
Auth0, React Router 7, Lucide, Vite 6 and Vitest 3; new frontend-only TanStack Query, Axios, Vaul,
`idb`, `workbox-window`, `vite-plugin-pwa`, and test-only MSW. No new Gradle dependency.

**Storage**: Existing MongoDB container with a separate `coparent` database and ten legacy-compatible
collections: `families`, `parents`, `children`, `invitations`, `onboardingstates`, `events`,
`eventcategories`, `schedulechangerequests`, `conversations`, and `audits`. A named Spring
`MongoTemplate` routes the product repositories. Indexes and any guarded source-data copy are
Mongock-managed; platform backup/restore includes both databases.

**Testing**: JUnit 6, MockMvc, Spring Security test JWTs, Testcontainers through
`AbstractIntegrationTest`, source-contract fixtures, Vitest/Testing Library/MSW, Playwright, nginx
configuration tests, backup/restore round-trip, and visual review against source-app screenshots.

**Target Platform**: Existing Raspberry Pi ARM64 Docker Compose deployment; modern desktop/mobile
browsers and installable PWA; same backend and frontend images as simonrowe.dev and Term Time.

**Project Type**: Multi-entry web application over a modular monolith.

**Performance Goals**: Authenticated dashboard/calendar reads complete within two seconds at the
initial expected scale; ordinary Mongo-backed API calls target sub-500ms p95 excluding email
delivery; static assets retain immutable caching; no additional always-running process or container.

**Constraints**: Private child, medical, schedule, and communication data; every access must be
family-scoped from the JWT subject. The backend may not launch host processes. Mongo is standalone,
so transactions cannot be assumed. Automatic index creation is off. CSS remains plain BEM in the
single `styles.css`; Tailwind is not introduced. The PWA worker must not control or cache the
portfolio or Term Time. Auth0/Brevo credentials remain backend/build configuration and are never
logged. All feature flags default off.

**Scale/Scope**: The currently implemented MVP: one or more family profiles per Auth0 subject, two
parents per family in normal use, multiple children, events/categories/schedule changes,
conversations/messages/permissions, invitations, onboarding, and audit. Existing source totals are
unknown until the read-only cutover inventory. Roadmap-only expenses, documents, timeline/photos,
notifications, AI, search, uploads, and external calendar/bank integrations are excluded.

## Constitution Check

*GATE: passed before research and re-evaluated after design.*

| Principle | Status | Note |
| --- | --- | --- |
| I. Separate containers / monorepo | PASS | Backend and frontend remain separate runtime containers. CoParent adds a product module and Vite entry, not a mixed runtime or third application container. |
| II. Modern Java & React stack | PASS | Java 25/Spring Boot 4 and React 19/Vite are reused. Auth0 remains the only identity provider. Mongo remains the primary store. The source Tailwind markup is translated to scoped BEM in the single stylesheet; no CSS framework or second icon system is added. |
| III. Quality gates | PASS | Source API/browser suites become contract tests on the destination stack; family-isolation, asymmetric role combinations, migrations, restore, PWA, routing, and critical journeys receive automated coverage. Checkstyle, JaCoCo, frontend coverage, Sonar, and SBOM remain blocking. |
| IV. Observability & operability | PASS | The existing structured logs, Actuator, Prometheus, OTLP/Alloy, maintenance pages, monitoring, and production smoke checks are extended with CoParent-specific tags and hostname checks. Secrets and private content are redacted. |
| V. Simplicity & incremental delivery | PASS | One backend process, one frontend build, one Mongo container, one Auth0 audience, and a named database template provide separation without another runtime. Each domain slice is independently parity-tested before frontend/cutover work depends on it. |
| VI. Admin CMS UX | N/A | CoParent is a separate end-user product, not an admin CMS page. Existing admin conventions are unchanged. |
| VII. Interactive site tour | N/A | CoParent is not added to the portfolio tour. |
| VIII. Backup & restore | PASS | All ten collections are included in full backup/restore and restore-time index recreation in the same delivery; data migration is Mongock-first. |
| IX. Shell scripting | PASS | No new migration shell script is planned. Any supporting verification script will follow strict bash conventions; production data transformation remains in Mongock. |

Post-design re-evaluation: **PASS**. The main apparent conflict—the source UI's Tailwind usage—is
resolved by translating its visual design to destination-standard BEM rather than importing the
framework. No constitution exception is required.

## Project Structure

### Documentation (this feature)

```text
specs/048-coparent-migration/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── api.md
└── checklists/
    └── requirements.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/simonrowe/coparent/
├── CoparentConfiguration.java
├── CoparentProperties.java
├── auth/
│   ├── CoparentPrincipal.java
│   └── CoparentAccessPolicy.java
├── family/                         # family + parent application module
│   ├── CoparentFamilyController.java
│   ├── CoparentFamilyModule.java
│   ├── Family.java / Parent.java
│   ├── FamilyRepository.java / ParentRepository.java
│   └── *Request.java / *Response.java
├── child/                          # child + onboarding application module
├── invitation/                     # invitation state machine + email adapter
├── calendar/                       # events, categories, schedule-change workflow
├── messaging/                      # conversations, messages, permissions
├── audit/                          # append-only audit adapter
└── common/                         # error DTO/handler, ObjectId/date validation

backend/src/main/java/com/simonrowe/migration/changeunits/
├── V043CreateCoparentCollections.java
└── V044MigrateCoparentData.java    # included only when source data is confirmed

backend/src/test/java/com/simonrowe/coparent/
├── contract/                       # ported source API E2E behaviour
├── security/                       # cross-family and asymmetric role matrix
├── migration/                      # index + idempotent copy coverage
└── *ModuleTest.java                # tests through each module interface

frontend/
├── coparent/index.html
├── public/
│   └── coparent-icons/*
├── src/coparent/
│   ├── main.tsx / App.tsx
│   ├── auth/
│   ├── api/
│   ├── family/
│   ├── calendar/
│   ├── messaging/
│   ├── pwa/
│   ├── pages/
│   └── shell/
├── tests/coparent/
└── e2e/coparent.*.spec.ts

frontend/src/styles.css              # scoped coparent-* BEM section
frontend/vite.config.ts              # third entry + isolated PWA output + local proxy
frontend/nginx.conf                  # /coparent fallback inside image
config/nginx/nginx-proxy.conf        # coparents.simonrowe.dev host
docker-compose.prod.yml              # default-off backend configuration / frontend build args
.github/workflows/{ci,publish}.yml    # dependencies, tests, build args; no new image
docs/runbooks/coparent.md
scripts/monitor-prod.sh              # public-host check
```

**Structure Decision**: CoParent is a tier-spanning product module. Its external seams are the
`/api/coparent` HTTP interface and the third frontend entry. Inside the backend, five deep
application modules own workflows and repositories; controllers do not orchestrate multi-document
writes. Shared runtime infrastructure is injected as adapters. CoParent never imports portfolio or
school domain code, so deleting the package and Vite entry removes the product rather than leaving
its rules spread through unrelated callers.

## Module Interfaces and Seams

| Module | External interface | Behaviour hidden behind it | Adapters / test strategy |
| --- | --- | --- | --- |
| Identity and access | `CoparentAccessPolicy.forSubject(subject)` plus authenticated HTTP matcher | Parent-profile lookup, family membership, primary/requester/responder rules, non-enumerating failures | Spring Security JWT production adapter; security-test JWT adapter |
| Family | Family/current-user/parent HTTP contract | Initial profile, family creation, membership links, role invariants, soft deletion, audit | Mongo repositories; Testcontainers contract tests |
| Child/onboarding | Child and onboarding HTTP contract | Family reference checks, date normalisation, onboarding transitions, soft deletion | Mongo repositories; Testcontainers contract tests |
| Invitation | Invite HTTP contract | Duplicate detection, token lifecycle, verified-email match, idempotent acceptance, audit, delivery outcome | Spring Mail adapter plus recording test adapter |
| Calendar | Event/category/schedule HTTP contract | Reference validation, recurrence/date rules, request ownership/decision state, audit | Mongo repositories; clock adapter for deterministic time |
| Messaging | Conversation/permission HTTP contract | Participant selection, embedded-message updates, unread counts, decision state, formatting | Mongo repository with atomic document updates |
| Frontend data layer | Typed hooks below one QueryClient | Token injection, relative base URL, cache keys, invalidation, common errors | Axios production adapter; MSW test adapter |
| PWA | `registerCoparentPwa()` called by CoParent entry only | Canonical-origin check, worker update lifecycle, static-shell cache, logout cleanup | Browser worker production adapter; disabled/controlled test adapter |

These are real seams with at least production and test adapters. Repository details and partial-write
recovery remain internal and are tested through the module interface rather than exposed to
controllers.

## Key Design Decisions

### 1. Contract first, rewrite second

Before implementing each Java slice, capture source requests/responses as fixtures and port its API
E2E scenarios. The first Java result is allowed to fail those tests; implementation continues until
the slice reaches parity. This prevents controller DTO differences—especially source `_id` versus
`id` inconsistencies—from becoming accidental browser breakage.

The target contract deliberately improves four security details: a global prefix, consistent `id`
responses, non-enumerating `404`s, and invitation secrets in URL fragments/request bodies rather
than server-visible query strings. These changes land with matching frontend changes in the same
slice.

### 2. Separate database, shared Mongo container

The named `coparentMongoTemplate` gives the module a real persistence boundary while reusing the
same Mongo process and credentials. Source collection names remain unchanged inside that database.
All indexes are declared in a Mongock change unit and recreated by restore. If a temporary legacy
database is needed during rehearsal, its copy is a second idempotent change unit after a full
backup—not an operator script.

### 3. One audience, two browser clients

The CoParent SPA retains its dedicated Auth0 client for callback/origin separation but requests the
existing backend audience. The resource server stays simple. A configured verified email claim is
mandatory for invitation acceptance; missing/unverified email is a controlled failure, never a
fabricated address.

### 4. Same-origin production routing

The public hostname terminates both UI and `/api/coparent/**`. Browser code never embeds
`api.simonrowe.dev`. The outer proxy gives `/assets/**`, manifest, worker, and icons explicit static
routes, then rewrites every other non-API route to `/coparent/index.html` at the inner frontend.
This supports BrowserRouter deep links while retaining the multi-entry build's shared `/assets/`.

### 5. Visual parity without Tailwind

The source UI is the visual reference, not its utility framework. Before conversion, capture its
key routes at desktop and mobile widths. Replace utilities with scoped BEM classes under a
`.coparent-app` root and add one organised section to the existing stylesheet. Components may be
split while translating, but behaviour and appearance are reviewed against the reference before
any intentional redesign is considered.

### 6. PWA isolation is origin-based and static-only

Only the CoParent entry imports registration code, and it registers only on the canonical CoParent
origin. The worker precaches versioned shell assets; it does not cache authenticated API responses.
Local shared-origin development leaves it off by default. IndexedDB databases are CoParent-named,
partitioned by verified subject where they contain user state, and cleared on logout. Existing
non-functional offline mutation replay is not expanded in this migration.

### 7. Standalone-Mongo-safe workflows

No workflow relies on transactions. Each multi-document mutation is behind one module interface,
uses atomic state transitions where contention matters, and is safe to retry. Invitation delivery
is an after-commit effect: SMTP failure leaves an auditable pending invitation that can be resent.
Audit failure is surfaced and retried within the module rather than silently reporting a mutation
as fully complete.

## Delivery Sequence

Each phase ends with focused tests green and leaves unrelated products deployable.

| Phase | Delivers | Exit gate |
| --- | --- | --- |
| 0. Contract and evidence | Source API fixtures, source UI screenshots, verified dependency imports, read-only source DB inventory, canonical domain/audience decisions | No unknown collection names or unpinned implemented journey remains |
| 1. Foundation | Default-off properties, authenticated matcher, access policy, DTO/errors, explicit collections/indexes, backup/restore registration | Disabled returns controlled unavailable; every collection restores with indexes; cross-family harness fails closed |
| 2. Family foundation | Current user, families, parents, children, onboarding, audit | Ported family/parent/child/onboarding contracts green; two-family IDOR matrix green |
| 3. Invitations | Token state machine, verified email, Spring Mail template, accept/resend/cancel/expiry | Duplicate/concurrent/expired/wrong-email tests green; no token in logs, URL requests, or list responses |
| 4. Calendar | Events, categories, schedule-change requests | Source calendar contract green; reference ownership and asymmetric requester/responder tests green |
| 5. Messaging | Conversations, messages, unread state, permission decisions | Source messaging contract and two-parent browser fixtures green; atomic embedded updates verified |
| 6. Frontend | Third entry, dependency delta, React 19/Router 7 adaptation, BEM visual port, isolated PWA, component tests | Build/lint/coverage green; visual review accepted; source critical UI tests ported |
| 7. Routing and E2E | Plural hostname proxy, Auth0 callbacks, deep-link fallback, local/prod-like Playwright | Onboarding, children, calendar, messaging, permissions, refresh, callback, and install/update flows green |
| 8. Data and operations | Optional Mongock source copy, runbook, public monitoring, maintenance branding, smoke/rollback checks | Count/relationship reconciliation and backup/restore rehearsal green; old runtime remains recoverable |
| 9. Cutover | Default-off deployment, final backup, enablement, production smoke, bounded rollback window, old runtime retirement | Both-parent critical journey green in production; all other public hosts green; rollback window closes cleanly |

Tasks should preserve these gates rather than parallelise dependent backend and frontend behaviour
blindly. Within a phase, model/repository setup, frontend component translation, and test fixture
porting can proceed in parallel where their interfaces are already fixed.

## Data Migration and Cutover

1. **Prove whether data exists.** Source control does not prove a live deployment. Inspect only
   collection names, counts, indexes, and deployment references; do not print private documents.
2. **Empty source**: ship `V043` only. Seed nothing beyond what normal onboarding creates.
3. **Non-empty source**: take a full backup, restore the source database into an isolated rehearsal,
   verify inferred Mongoose collection names, and implement `V044` with count/equality assertions.
4. **Rehearse twice**: first against the restored source; then restore a complete post-migration
   platform backup into another empty environment and rerun contracts.
5. **Final cutover**: stop old writes, take the required full backup, deploy images with
   `COPARENT_ENABLED=false`, run `V044`, reconcile counts/relationships, configure Auth0/domain,
   enable, and smoke-test.
6. **Rollback**: disable new routes and restore traffic to the stopped old runtime. Do not delete or
   reverse copied records. If new writes occurred, reconcile them deliberately before reopening the
   old writer.
7. **Retire**: after the bounded rollback window, archive the old repo/runtime configuration and
   document this monorepo as the sole implementation source.

Actual production execution requires the repository's `prod-backup-ops`, `prod-deploy`, and
`pr-review-loop` workflows. This planning change performs none of those actions.

## Verification Strategy

### Backend

- Port all nine source API E2E suites to `AbstractIntegrationTest`/Testcontainers.
- Add a generated route inventory test asserting every `/api/coparent/**` controller is authenticated.
- Exercise every entity and nested reference with same-family, wrong-family, malformed, missing,
  soft-deleted, and wrong-role identities.
- Test layered `COPARENT_ENABLED` and authentication states asymmetrically.
- Test concurrent invitation acceptance and schedule/permission decisions.
- Assert structured logs and audit changes redact tokens, JWTs, medical notes, and message bodies.
- Run migration twice and compare counts/content; restore and verify every index.

### Frontend

- Adapt source component tests to React 19/Router 7 rather than downgrading the destination.
- Use MSW at `/api/coparent` to pin typed hook/cache/error behaviour.
- Check every route behind auth/onboarding guards, including invite/callback public entries.
- Compare source and migrated screenshots at representative desktop/mobile widths.
- Assert no CoParent class/style leaks to the main and Term Time entries.
- Assert only the canonical CoParent origin registers the worker and that authenticated API paths are
  absent from Cache Storage after normal use/logout.

### End to end and operations

- Port the five source Playwright specs and add invitation acceptance, sign-out/sign-in persistence,
  deep-link refresh, wrong-family denial, service-worker update, and disabled-feature cases.
- Parse both nginx configurations in tests to pin host, API, asset, manifest, worker, and SPA
  fallback routing. Specifically guard variable `proxy_pass` URI behaviour learned from Term Time.
- Add the hostname to the production public-host smoke matrix and maintenance/unavailable branding.
- Prove backup/restore before enablement and run existing portfolio/Term Time smoke suites after every
  production-like CoParent deploy.

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Rewritten Java responses subtly differ from Mongoose output | Contract fixtures and ported E2E tests precede each slice; explicit response DTOs own the interface |
| A caller reads another family's private data by guessed ID | One access-policy module, family-scoped repository methods, non-enumerating errors, exhaustive wrong-family matrix |
| Source collection names or live-data assumptions are wrong | Read-only inventory before writing `V044`; full backup; abort-on-conflict idempotent migration |
| Invitation secrets leak through URLs/logs | URL fragment in email/browser, body exchange, redaction, no token in list DTOs, log assertions |
| Authentication redirect loses an invitation fragment | Copy once to CoParent-scoped session storage, clear the URL, return with Auth0 app state, submit and delete after callback |
| Auth0 audience/callback mismatch makes login loop | Reuse existing API audience; dedicated SPA client; verify callbacks/origins before enablement |
| Tailwind port changes appearance or pollutes other apps | Reference screenshots, scoped BEM root, single stylesheet, cross-entry visual/style tests |
| Root-scoped worker controls another product or caches private API data | Register on canonical origin only; named assets; static-only cache; shared-origin dev disabled; logout cleanup |
| Standalone Mongo leaves partial multi-document state | Deep workflow modules, idempotency, atomic state transitions, compensation/retry tests; no transaction assumption |
| New private collections are omitted from recovery | Backup/restore allowlists and index recreation are Phase 1 release gates |
| CoParent breaks Raspberry Pi cold start or shared ingress | No new container, existing health dependency, prod-like nginx tests, host-specific smoke and rollback flag |

## Complexity Tracking

No constitution violations. Namespaced collections and a third Vite entry are isolation inside the
two existing deployables, not new deployables. The source Tailwind and root PWA configuration are
deliberately not imported because doing so would create both a constitutional violation and a broad
shared-build interface.

## Process Deviation

Speckit's `before_specify` git hook normally creates or switches a feature branch. Conductor owns
this workspace's branch and explicitly prohibits renaming it, so the feature directory and
`.specify/feature.json` were created on the existing Conductor-managed branch. This is the same
documented exception used by feature 047; the specification, research, design, and plan workflow is
otherwise unchanged.
