# Implementation Plan: Software Factory Console

**Branch**: `simonrowe/linear-agent-triggers` | **Date**: 2026-08-28 | **Spec**:
[spec.md](./spec.md)

**Input**: [spec.md](./spec.md)

## Summary

Add a role-protected Software Factory page to the existing admin console and a server-side proxy
from the backend to new token-protected factory action endpoints. A read-only internal status
endpoint reports local flags plus Temporal poller and schedule facts; the backend combines the
`software-factory` and `deployer` views without sending a factory credential to the browser.

Simplify `cvefix` into an issue-only vulnerability scanner: read and group Dependency-Track
findings, file every affected component through the existing Linear sink, and record the run. The
old agent, git, pull-request, and CI-repair path is removed. Extend feedback so it files a
source-PR Linear issue before distillation, includes that issue in any guidance PR, and attaches
the resulting PR URLs back to Linear. Feedback, scanning, Linear, and platform backup default on;
new CVE and platform-backup schedules start active while preserving an existing operator pause.
Code review remains status-only. Manual deploy is limited to the backend and frontend bundle's
matching current commit and retains the existing Temporal deploy boundary.

## Technical Context

**Language/Version**: Java 21 (`backend`, `software-factory`), TypeScript 5.7 / React 19
(`frontend`)

**Primary Dependencies**: Existing Spring Boot 3.5.16 web/security clients, Temporal Java SDK
1.36.0, Spring Data MongoDB, React Router 7, Lucide React. No new Gradle or npm dependency.

**Storage**: Existing MongoDB `software_factory` collections (`cve_fix_runs`,
`review_learnings`, `linear_issues`) with backward-compatible document evolution; Temporal
workflow and schedule history remains the operational source of truth.

**Testing**: JUnit 5, Temporal `TestWorkflowEnvironment`, MockMvc, mocked HTTP servers/clients for
adapter tests, existing Testcontainers Mongo pattern, Vitest/Testing Library, and browser-driven
admin verification.

**Target Platform**: Raspberry Pi ARM64 under Docker Compose; responsive administration UI in
current desktop and mobile browsers.

**Project Type**: Multi-module web application plus a Temporal-orchestrated modular monolith.

**Performance Goals**: Return a partially populated status page within 5 seconds even when one
downstream is unavailable; accept manual triggers within 5 seconds; use bounded one-second status
probes and frontend polling rather than holding action requests open.

**Constraints**: The backend may not launch host processes or hold Docker tooling. The deployer
keeps no public ingress and no Linear credential. Factory action tokens remain server-side. The
factory's public nginx surface remains the exact signed GitHub webhook only. Temporal workflow
changes must remain deterministic and compatible with histories already stored.

**Scale/Scope**: One administrator, six modules, two factory containers, two schedules, at most a
handful of manual runs per week, and Dependency-Track findings for the current backend/frontend
projects.

## Constitution Check

*GATE: passed before Phase 0 and re-evaluated after Phase 1.*

| Principle | Status | Note |
| --- | --- | --- |
| I. Separate containers | PASS | No deployable is added. Browser, backend, factory, deployer, Temporal, and Linear retain their existing boundaries. |
| II. Modern Java & React stack | PASS | Existing Java/React/CSS/icon choices are reused. Manual deploy still reaches the dedicated deployer over Temporal; the backend receives no Docker access and launches no process. Deploy flags remain default-off. |
| III. Quality gates | PASS | Workflow, controller/client, security, persistence, frontend, and configuration behavior get automated tests. No new coverage exclusion. |
| IV. Observability & operability | PASS | The feature exposes the previously-hidden difference between flags, workflow pollers, activity pollers, and schedule pause state. Partial failures are first-class. |
| V. Simplicity & incremental delivery | PASS | The backend reuses its existing sibling-service HTTP pattern; the factory remains the only Temporal client for actions. Existing audit collections are evolved rather than duplicated. |
| VI. Admin CMS UX | PASS | One route and nav item extend the existing admin shell, typography, icon system, buttons, and responsive patterns. |
| VIII. Backup & restore | PASS | Capture is triggered durably in the deployer; restore stays on the host and no backend Docker capability returns. |

Post-design re-evaluation: PASS. The internal read-only status endpoint adds no public route, the
token-protected action API stays on `software-factory`, and the backend is only a role-protected
HTTP proxy. No constitutional violation requires complexity tracking.

## Project Structure

### Documentation (this feature)

```text
specs/040-software-factory-console/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── admin-api.md
│   └── factory-api.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
software-factory/src/main/java/com/simonrowe/factory/
├── admin/
│   ├── FactoryStatusController.java
│   ├── FactoryStatusService.java
│   ├── FactoryTokenAuthenticator.java
│   └── domain/*.java
├── cvefix/
│   ├── api/CveScanController.java
│   ├── api/CveScanWorkflowService.java
│   ├── domain/*.java                    # simplified scan/result/progress model
│   ├── schedule/CveFixScheduleInitializer.java
│   └── workflow/*.java                 # fetch -> Linear filing -> record only
├── deploy/api/
│   ├── DeployController.java
│   └── DeployWorkflowService.java
├── feedback/
│   ├── api/FeedbackController.java
│   ├── domain/*.java
│   ├── github/FeedbackPrGateway.java
│   └── workflow/*.java                 # issue first, PR links repaired afterwards
├── linear/
│   ├── config/LinearProperties.java
│   ├── domain/FiledIssue.java
│   ├── linear/LinearGateway.java
│   └── workflow/*.java
└── platformbackup/api/
    ├── PlatformBackupController.java
    └── PlatformBackupWorkflowService.java

backend/src/main/java/com/simonrowe/factoryadmin/
├── FactoryAdminController.java
├── FactoryAdminClient.java
├── FactoryAdminProperties.java
├── FactoryAdminService.java
└── *.java                               # wire/request/response records

frontend/src/
├── pages/admin/SoftwareFactoryAdmin.tsx
├── services/softwareFactoryApi.ts
├── types/softwareFactory.ts
├── components/admin/AdminLayout.tsx
├── App.tsx
└── styles.css

docker-compose.prod.yml                  # enable defaults and backend proxy configuration
software-factory/src/main/resources/application.yml
backend/src/main/resources/application.yml
docs/software-factory.md
docs/runbooks/{software-factory,cvefix,linear,platform-backup-restore}.md
CLAUDE.md
```

**Structure Decision**: Keep workflow ownership inside `software-factory`; add a narrow
`factoryadmin` backend adapter so the browser uses the existing Auth0 admin boundary. Status is
read-only and available from both factory containers over the internal network. Actions are
token-protected and accepted only by `software-factory`, which already holds the trigger token and
Temporal client. The deployer continues to receive work only from Temporal.

## Design Direction

**Subject**: a one-person production operations console. **Audience**: the authenticated owner.
**Single job**: prove whether each factory capability can execute, then make the selected safe
manual actions available.

- **Palette**: reuse the admin console's surface, border, accent, success, warning, and danger
  tokens. No parallel dashboard palette is introduced.
- **Type**: existing Space Grotesk headings, Inter body, and the established compact utility style
  for task queue names and commit SHAs.
- **Layout**: a vertical module ledger rather than generic metric cards. Each row reads left to
  right as `module -> configured -> worker -> trigger/schedule -> last run -> action`, encoding the
  actual operational path.
- **Signature**: a continuous “factory rail” joining the state checkpoints. It is structural: a
  break appears exactly where readiness stops, making “enabled but no activity poller” immediately
  legible.
- **Motion**: only the existing spinner for an accepted/running action and a restrained state
  transition when polling updates. Reduced-motion settings remain respected.

The initial design review rejected a tile-and-big-number dashboard because module readiness is a
sequence, not a set of unrelated metrics. The ledger keeps the existing admin identity and spends
its one distinctive device on the real queue path.

## Complexity Tracking

No constitution violations.
