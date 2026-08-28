# Quickstart: Software Factory Console

## Automated verification

```bash
./gradlew :software-factory:test
./gradlew :backend:test
cd frontend && npm test
```

Required focused scenarios:

1. Status distinguishes configuration, workflow pollers, activity pollers, and paused schedule.
2. Missing Temporal/factory/deployer status degrades one module/source without failing the page.
3. A CVE scan with two advisories on one PURL files one Linear occurrence and performs no Git or
   GitHub mutation.
4. A second unchanged CVE scan updates rather than duplicates the issue; cancelled/completed
   states follow suppression/regression policy.
5. Feedback files its Linear issue before distillation; created PR bodies reference it and every
   PR URL is attached or left in a repairable pending state.
6. Feedback disabled rejects webhook and manual paths.
7. A fresh schedule is active; an existing paused schedule remains paused after initializer update.
8. Deploy rejects unknown, mismatched, stale, and wrongly confirmed commits.
9. Signed-out and non-admin callers cannot read status or start work.
10. Browser code and responses contain no factory trigger token.

## Local UI verification

Use the repository's local environment workflow, then open `/admin/software-factory` as an admin.
Verify desktop and mobile layouts, keyboard focus, loading/empty/error states, and reduced motion.
The module ledger must show the readiness path in order rather than presenting unrelated metric
cards.

Do not execute a real deploy or platform backup against production during local verification.
Use mocked/fake downstream responses or the platform-backup dry run.

## Production rollout

1. Confirm the Linear team, Triage state, `factory:feedback` and `factory:cvefix` labels, and scoped
   API credential exist.
2. Confirm Dependency-Track and Google Drive backup credentials are present without printing them.
3. Deploy the image and manually recreate `deployer`, because it never self-updates.
4. Open Software Factory and verify all six module rows plus activity pollers for `linear`,
   `deploy`, and `platform-backup` as appropriate.
5. Confirm `cve-fix-daily` and `platform-backup-nightly` are active, with the latter's next action at
   02:00 Europe/London. Preserve any schedule an operator had already paused.
6. Run feedback manually against a known closed PR and verify the Linear issue/PR cross-links.
7. Run vulnerability scan and confirm tickets only—no branch or pull request.
8. Run platform backup dry-run, then a real capture, and confirm the archive appears in Data Ops.
9. Exercise deploy only against the currently running commit and only after all status checks pass.

Rollback is configuration-first: set the relevant feature flag false and recreate only the
owning factory container. Do not restart nginx and do not add Docker access to the backend.
