# CoParent cutover and rollback

Use this runbook when rehearsing or enabling the CoParent migration at
`coparents.simonrowe.dev`. It does not authorise a production cutover by itself.
Use `prod-backup-ops` before changing production and `prod-deploy` for the release.

## Safety model

CoParent is part of the existing backend and frontend images, but remains inert while
`COPARENT_ENABLED=false`. Disabling that flag is the application rollback: migrated records are
retained, and rollback never deletes or reverses family data.

The application uses the existing Mongo container and client, but a dedicated `coparent` database.
The `simonrowe` database never receives CoParent family records.

Only one implementation may accept writes during migration. Stop or make the legacy Node API
read-only before the final copy begins. Never reconcile production data with an ad-hoc script;
the id-preserving copy is Mongock change unit `V044MigrateCoparentData`.

## Production-only prerequisites

Complete these outside the repository before enabling the feature:

- Create `coparents.simonrowe.dev` in Cloudflare and route it through the existing tunnel.
- Create or select the dedicated Auth0 SPA client and allow exactly:
  `https://coparents.simonrowe.dev`, its `/auth/callback`, and its logout return URL.
- Add the `VITE_COPARENT_AUTH0_CLIENT_ID` GitHub Actions secret. Confirm the shared API audience
  is `https://api.simonrowe.dev`.
- Set `COPARENT_APP_URL=https://coparents.simonrowe.dev`. Configure SMTP and set
  `COPARENT_EMAIL_ENABLED=true` only after a real invitation has been verified end to end.
- Establish whether the legacy Mongo database contains live records. Record collection counts and
  indexes only; do not paste child, medical, message, invitation, or token data into logs.

## Rehearsal

1. Take a full-with-media production backup and verify its Google Drive upload.
2. Restore the legacy database into an isolated local environment.
3. Restore the legacy records into a temporary `coparent_legacy` database in the isolated
   environment. Start once with `COPARENT_DATABASE=coparent`,
   `COPARENT_SOURCE_DATABASE=coparent_legacy`, and CoParent disabled. Mongock creates the target
   collections, then copies records without changing `_id` values or timestamps.
4. Compare source and target counts for all ten mappings:

   | Temporary source | Dedicated target |
   |---|---|
   | `coparent_legacy.families` | `coparent.families` |
   | `coparent_legacy.parents` | `coparent.parents` |
   | `coparent_legacy.children` | `coparent.children` |
   | `coparent_legacy.invitations` | `coparent.invitations` |
   | `coparent_legacy.onboardingstates` | `coparent.onboardingstates` |
   | `coparent_legacy.events` | `coparent.events` |
   | `coparent_legacy.eventcategories` | `coparent.eventcategories` |
   | `coparent_legacy.schedulechangerequests` | `coparent.schedulechangerequests` |
   | `coparent_legacy.conversations` | `coparent.conversations` |
   | `coparent_legacy.audits` | `coparent.audits` |

5. Verify representative family-to-parent/child/invitation/event relationships and embedded
   conversation messages/permissions without exporting their contents.
6. Run the migration a second time. Counts and documents must remain unchanged.
7. Take a platform backup, restore it into another empty local environment, and verify CoParent
   counts plus the unique invitation, parent-membership, and onboarding indexes.
8. Run the focused backend, frontend, routing, and Playwright gates from
   `specs/048-coparent-migration/quickstart.md`.

Any source/target conflict aborts startup deliberately. Investigate the conflicting identifier;
do not overwrite either document.

## Cutover

1. Verify nginx and all upstreams are healthy, then take and verify a fresh full-with-media backup.
2. Freeze writes on the legacy API and record final per-collection counts.
3. Deploy the shared images with `COPARENT_ENABLED=false` and `COPARENT_DATABASE=coparent`, then verify the portfolio, Term Time,
   admin, and API remain healthy.
4. Run the guarded migration and reconcile final counts and sampled relationships.
5. Verify the Auth0, Cloudflare, build-secret, application URL, and invitation-mail prerequisites.
6. Set `COPARENT_ENABLED=true`, deploy through the normal production workflow, and verify:
   root and deep links, sign-in/callback/logout, existing-family access, child edits, calendar,
   messages, permission decisions, invitation acceptance, manifest, and service-worker update.
7. Confirm `scripts/monitor-prod.sh` reports the CoParent hostname healthy and inspect backend logs
   for controlled 4xx outcomes rather than identifiers, tokens, or family content.
8. Keep the legacy runtime stopped but recoverable for the agreed rollback window. Retire it only
   after a successful scheduled backup and restore rehearsal of the consolidated records.

## Rollback

1. Set `COPARENT_ENABLED=false` and redeploy. Confirm `/api/coparent/**` returns the controlled
   unavailable response while the other applications remain healthy.
2. If users must continue writing, point the CoParent hostname back to the stopped legacy runtime
   only after confirming the data ownership point and preventing split-brain writes.
3. Preserve the dedicated `coparent` database. If data itself is damaged, use the supported Data
   Operations restore flow and the pre-cutover full backup; do not run raw restore commands on the
   production database.
4. Reconcile any writes made after the ownership point before attempting another cutover.

## Expected rollback boundaries

The frontend and ingress can roll back with the normal image rollback. The data migration is
forward-only and idempotent: rollback disables access but does not delete records. Auth0 and DNS
changes may be left configured while disabled because neither grants backend access without a valid
JWT and an enabled CoParent feature flag.
