# Specification Quality Checklist: Auto-deploy on merge

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-26
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- The source design document is an approved technical design, so the first draft
  of the spec inherited named components (the workflow engine, the phase script,
  the container names) and concrete mechanisms. Those were rewritten in
  capability terms: "a durable channel that survives the executor restarting"
  rather than the engine's name, "resolved in a way every other command on the
  host also resolves" rather than the re-tagging mechanism, "a flag readable but
  not writable by the proxy" rather than the volume mount. The design document
  remains the source for the *how* at `/speckit.plan` time.
- Three areas were resolved by informed guess rather than a clarification
  marker, and are recorded under Assumptions: single-node production (so no
  zero-downtime scheme), reuse of the existing workflow engine, and the scope of
  rollback excluding data migrations.
- FR-014 and SC-003 deliberately state the watchdog-interaction requirement in
  outcome terms ("must not change which version is running") rather than naming
  the pull-policy change, so the requirement stays testable if the mechanism
  changes.
- The deliberate non-goal in FR-004 (merge is not the trigger) is kept as a
  requirement rather than moved to Assumptions, because it is testable and it
  guards against the worst failure mode in the design: a deploy that reports
  success while shipping the previous build.

## Post-implementation

Implementation completed 2026-08-26; all 67 tasks in
[tasks.md](../tasks.md) are done and the full gate is green
(`:software-factory:check`, `:backend:check`, frontend 505 tests + 0 lint errors,
109 shell-test checks, `nginx -t`, `docker compose config`).

Three things found during implementation that the spec and design did not
anticipate, all now fixed and covered:

1. **`error_page 503 = @maintenance` (with `=`) rewrites the status to 200.** The
   design specified the `=` form. That would have served the maintenance page as
   a success — so `verify-public`, which treats 503 as a failure, would have
   PASSED while the page was still up, and a failed `maintenance-off` would have
   gone unnoticed. Fixed by dropping the `=`; asserted in
   `scripts/test/test-nginx-maintenance.sh`.
2. **A class-level `@ConditionalOnProperty` is ignored when the bean is declared
   through an explicit `@Bean` method.** The first version of
   `DeployWorkerRegistrationTest` did exactly that, so it "passed" without
   testing the gate that confines the Docker socket to the `deployer`. Rewritten
   to component-scan the real package.
3. **`DeployRunRecord` could not key on the workflow id** the way
   `CveFixRunRecord` does — the workflow id is the fixed constant `deploy-prod`,
   so every deploy in history would have collided on one document. Keys on the
   Temporal run id plus an attempt suffix instead (FR-044 / SC-010).

One pre-existing issue surfaced but not fixed, deliberately out of scope:
`software-factory`'s six HTTP-stub tests share an ephemeral-port pattern that
intermittently routes a request to the wrong stub server (a bare `404`). Observed
twice in ~8 full-suite runs, once in this feature's new
`DeployReportGatewayTest` (hardened: loopback bind, `Connection: close`, and a
catch-all context that reports the unmatched path) and once in the untouched
`CiStatusGatewayTest`. Adding a seventh stub server makes the latent flake
marginally more likely; fixing the other five files is a separate change.
