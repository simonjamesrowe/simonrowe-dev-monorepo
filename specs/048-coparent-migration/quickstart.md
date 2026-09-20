# Quickstart: CoParent Migration Verification

This document is the implementation and review path. It does not authorise a production cutover;
the production runbook and backup checks must be completed before that step.

## 1. Configuration

Add only variable names to checked-in examples. Values remain in the existing private environment
source and must never be printed.

Backend configuration:

```text
COPARENT_ENABLED=false
COPARENT_DATABASE=coparent
COPARENT_APP_URL=http://localhost:<frontend-port>
COPARENT_AUTH0_EMAIL_CLAIM=<verified-claim-name>
COPARENT_EMAIL_FROM=<configured-sender>
COPARENT_EMAIL_ENABLED=false
COPARENT_SOURCE_DATABASE=coparent_legacy # optional migration rehearsal only
```

Frontend build configuration:

```text
VITE_COPARENT_AUTH0_CLIENT_ID=<spa-client-id>
VITE_COPARENT_AUTH0_AUDIENCE=https://api.simonrowe.dev
VITE_COPARENT_CANONICAL_ORIGIN=https://coparents.simonrowe.dev
```

The Auth0 domain is shared with the existing frontend configuration. The production client must
allow the plural origin, `/auth/callback`, and logout return URL before the feature is enabled.

## 2. Local verification

Start infrastructure and the applications using the repository's `local-env` runbook. CoParent is
served locally from the third frontend entry while API requests use `/api/coparent` through Vite's
proxy.

Run the focused suites while developing:

```bash
./gradlew :backend:test --tests 'com.simonrowe.coparent.*'
cd frontend && npm test -- coparent
cd frontend && npm run build
```

Then run the blocking repository gates:

```bash
./gradlew check
cd frontend && npm run lint
cd frontend && npm run test:coverage
cd frontend && npm run e2e:coparent
```

## 3. Contract parity gate

Port the source API E2E cases feature-by-feature. The gate is complete when the Java suite covers:

- families, current user, parents, children, onboarding;
- invitation create/duplicate/resend/cancel/expiry/accept;
- events, categories, recurrence, schedule request decisions;
- conversations, messages, unread state, permission decisions;
- malformed IDs, validation, unauthenticated requests, wrong-family IDs, and asymmetric role/owner
  combinations.

Use Testcontainers through `AbstractIntegrationTest`; do not substitute mocked repositories for
the contract suite.

## 4. Visual and browser parity gate

Before changing markup, capture the source app's login, onboarding, dashboard, family setup,
calendar (day/week/month and editor), messages, permission decision, placeholders, and mobile shell.
Store review screenshots under `.context/`.

The migrated app passes when:

- the same routes and controls work at desktop and mobile widths;
- the BEM translation preserves the Calm Harbor palette, spacing, typography, dark mode, drawers,
  validation, and responsive navigation;
- all source component tests that describe active behaviour pass after adaptation;
- the copied app imports no Tailwind framework or utility stylesheet;
- `/calendar`, `/messages`, `/auth/callback`, and `/invitations/accept` survive direct navigation and
  refresh on the CoParent hostname.

## 5. Data migration rehearsal

Before deciding that source data is empty, inspect the actual source database without printing
records. Record collection names, counts, and indexes only.

For a non-empty source:

1. Take a recoverable full backup through the supported backup workflow.
2. Restore the source database into an isolated local environment.
3. Restore legacy records into a temporary `coparent_legacy` database, then run the
   `V044MigrateCoparentData` change unit with `COPARENT_SOURCE_DATABASE=coparent_legacy`.
4. Compare per-collection counts and validate sampled family-to-parent/child/invitation/event and
   conversation-to-message/permission relationships.
5. Rerun the migration and prove that counts and content do not change.
6. Restore the resulting platform backup into another empty local environment and rerun the
   contract tests.

Never transform production records with an ad-hoc script.

## 6. Production-like routing gate

With the production nginx configuration loaded against local containers, verify Host-header
routing without using the live tunnel:

```text
Host: coparents.simonrowe.dev /
Host: coparents.simonrowe.dev /calendar
Host: coparents.simonrowe.dev /auth/callback
Host: coparents.simonrowe.dev /assets/<coparent-chunk>
Host: coparents.simonrowe.dev /coparent.webmanifest
Host: coparents.simonrowe.dev /coparent-sw.js
Host: coparents.simonrowe.dev /api/coparent/me
```

Also prove that the same requests do not alter `simonrowe.dev`, `term-time.simonrowe.dev`,
`api.simonrowe.dev`, or `/healthz` behaviour.

## 7. Cutover and rollback outline

If no public CoParent runtime or live data exists, enable the consolidated feature only after all
gates above pass.

If a live runtime exists:

1. Verify nginx and upstream health, then take a full backup.
2. Stop writes on the old API.
3. Run the id-preserving `V044MigrateCoparentData` migration and reconcile counts/relationships.
4. Deploy the shared backend/frontend images with CoParent still disabled.
5. Configure Auth0 and DNS/Cloudflare, enable CoParent, and smoke-test both parents' critical flows.
6. Keep the old runtime stopped but recoverable for the bounded rollback window.
7. On failure, disable CoParent and restore routing to the old runtime; do not reverse or delete the
   migrated records.
8. Retire the Node/UI runtime only after the rollback window and backup-restore proof complete.

The final production execution must use `prod-backup-ops`, `prod-deploy`, and the resulting CoParent
runbook rather than improvising these steps.
