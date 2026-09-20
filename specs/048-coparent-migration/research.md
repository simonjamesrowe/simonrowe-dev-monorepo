# Research: CoParent Migration

**Source snapshot**: `/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo` at
`377890c527ceff0d3d743428fad4019a8a92297b` (`main`, 2026-02-21).

This research treats running source and tests as authoritative. Roadmap-only expenses, documents,
timeline/photos, integrations, and AI features are not implemented behaviour and are excluded.

## Decision 1: One deployable backend, one isolated product module

**Decision**: Port the Nest modules into `com.simonrowe.coparent` inside the existing Spring Boot
backend. Expose one authenticated external interface below `/api/coparent/**`. Keep all CoParent
domain types, application logic, persistence adapters, configuration, and tests inside that package
tree; do not import portfolio or Term Time domain modules.

**Rationale**: The source already divides the product into family, parent, child, invitation,
onboarding, calendar, schedule-change, messaging, and audit modules
([source AppModule](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/src/app.module.ts:21)).
The destination backend already provides the runtime concerns CoParent needs: MongoDB, resource
server authentication, validation, mail, tracing, metrics, health, and production packaging. A
single package-level seam gives callers one small interface while keeping the implementation local.

**Alternatives considered**:

- A new Gradle module and container preserves maximum physical separation but keeps the deployment
  duplication this migration is meant to remove.
- Mixing controllers and repositories into existing portfolio packages saves directories but makes
  product ownership and later extraction substantially harder.

## Decision 2: Prefix the HTTP contract and pin parity with tests

**Decision**: Prefix every migrated route with `/api/coparent` and retain the current payload,
validation, and status semantics unless this plan calls out a security correction. Port the nine
source API E2E suites into Spring Boot integration tests before removing the Nest implementation.

**Rationale**: The source has no global API prefix and registers literal controller paths
([bootstrap](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/src/main.ts:11)).
Those names would collide easily inside a shared backend. The current controllers also mix explicit
DTOs with persistence-document responses, so parity cannot be assumed from similarly named Java
records ([family controller](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/src/families/families.controller.ts:31),
[event controller](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/src/events/events.controller.ts:24)).

**Alternatives considered**:

- Preserve unprefixed routes. Rejected because generic paths such as `/families`, `/children`, and
  `/me` do not belong at the root of the shared backend.
- Redesign the contract during the rewrite. Rejected because it combines two independent sources
  of regression and prevents the existing E2E tests from serving as an executable oracle.

## Decision 3: Dedicated database on the shared Mongo server

**Decision**: Keep CoParent in a dedicated `coparent` database on the existing Mongo container and
route its repositories through a named `coparentMongoTemplate`. Retain the source collection names
so a restored legacy database remains directly usable. Create every collection and index through
one idempotent Mongock change unit. A second guarded change unit can copy from a temporary
`coparent_legacy` rehearsal database while preserving `_id`, timestamps, embedded IDs, and
relationships and aborting on conflicting target documents.

**Rationale**: The source connects to a separate `coparent` database
([source environment](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/.env.example:17))
and relies on Mongoose schema index creation. No schema sets a collection name, so physical names
are inferred Mongoose plurals and must be verified against any real database before migration
([representative registration](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/src/families/families.module.ts:12)).
The destination disables automatic index creation; Term Time demonstrates explicit Mongock index
creation ([precedent](../../backend/src/main/java/com/simonrowe/migration/changeunits/V040CreateSchoolCollections.java)).
The product boundary is materially clearer at database level while the container, client,
credentials, resource limits, and operational ownership remain shared. Backup and restore treat
the two databases as one platform backup.

**Alternatives considered**:

- Use `coparent_*` collections in `simonrowe`. Rejected in favour of the user's requested database
  boundary; generic legacy collection names are safe inside their own database.
- Run a second Mongo container. Rejected because database-level separation supplies the required
  boundary without another process, volume, health dependency, or memory reservation.

## Decision 4: Reuse the API audience; keep a dedicated browser client

**Decision**: The CoParent SPA will use its existing Auth0 tenant and dedicated SPA client but
request the destination backend's existing `https://api.simonrowe.dev` audience. The Java resource
server remains single-issuer/single-audience. `/api/coparent/**` is explicitly authenticated before
the destination chain's permissive fallback. A `CoparentAccessPolicy` resolves the Auth0 subject to
family memberships for every operation.

**Rationale**: The source tenant is already shared but currently requests the different audience
`https://coparent.simonrowe.dev`; the destination decoder accepts the API audience only
([source environment](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/docker/.env.example:1),
[target decoder](../../backend/src/main/java/com/simonrowe/auth/Auth0JwtDecoder.java)). The target
security chain permits unmatched routes, so merely adding controllers would make the entire
CoParent interface public ([security chain](../../backend/src/main/java/com/simonrowe/auth/SecurityConfig.java)).
Reusing the API audience avoids a multi-decoder security branch while the dedicated SPA client keeps
callbacks and browser origins separate.

Invitation acceptance will compare against a configured, verified Auth0 email claim. It will not
preserve the source fallback that fabricates an address when `email` is absent
([source strategy](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/src/auth/jwt.strategy.ts:23)).

**Alternatives considered**:

- Accept both audiences. Viable, but it adds security configuration and asymmetric tests for no
  product benefit once the SPA is migrated.
- Reuse the portfolio SPA client. Rejected because callbacks, logout origins, and consent settings
  are product-specific.

## Decision 5: Migrate only the frontend dependencies that active code uses

**Decision**: Keep the destination's React 19, React Router 7, Vite 6, TypeScript 5.7, Vitest 3,
Auth0, Lucide, React Hook Form, and Zod versions. Add compatible current versions of TanStack Query,
Axios, Vaul, `idb`, `workbox-window`, `vite-plugin-pwa`, and MSW (test only). Do not add the source's
unused AI SDK, Framer Motion, react-responsive, Zustand, or duplicate form/validation packages.

**Rationale**: The source manifest is older and over-declared
([source package](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/ui/package.json:22));
active imports show TanStack Query/Axios in the data layer, Vaul in the event drawer, `idb` and
Workbox in the PWA layer, and MSW in tests
([API client](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/ui/src/lib/api/client.ts:1),
[drawer](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/ui/src/components/calendar/EventCreationDrawer.tsx:1),
[offline DB](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/ui/src/lib/pwa/db.ts:6)).
The destination versions are the constraint; dependencies are upgraded rather than the shared
frontend downgraded.

**Alternatives considered**:

- Copy the source lockfile or create a second npm project. Rejected because it duplicates install,
  lint, test, SBOM, and image-build work, contrary to the Term Time precedent.

## Decision 6: Preserve the design by translating Tailwind to scoped BEM

**Decision**: Do not add Tailwind to the destination. Translate the copied utility-class markup to
`coparent-*` BEM classes and place the styles in the existing `frontend/src/styles.css`. Capture the
source application at representative desktop/mobile routes and use those images as visual parity
references.

**Rationale**: The source UI is almost entirely Tailwind utilities and its stylesheet contains the
framework directives rather than the resulting design
([source stylesheet](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/ui/src/styles.css:1),
[Tailwind config](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/ui/tailwind.config.js:1)).
Copying TSX alone would produce an unstyled app. The destination constitution, however, explicitly
forbids CSS frameworks and requires BEM in the single stylesheet. The translation is mechanical
work, not a redesign, and keeps the constitution gate green without allowing Tailwind resets or
utility rules to affect the other two entry points.

**Alternatives considered**:

- Add Tailwind with preflight disabled and an important selector. Technically feasible, but it
  violates the binding constitution and expands the shared build surface.
- Paste generated utility CSS into the stylesheet. Rejected because future class changes would no
  longer produce styles and the result would be harder to maintain than intentional BEM.

## Decision 7: A third Vite entry with an origin-isolated PWA

**Decision**: Add `coparent/index.html` and `frontend/src/coparent/` as a third entry in the existing
frontend build. Generate a CoParent-named manifest and worker, register them only when running on the
canonical CoParent origin, and cache static shell assets only. Do not cache authenticated API
responses. Disable service-worker registration in shared-origin local development and tests unless
the test explicitly exercises PWA behaviour.

**Rationale**: Term Time already proves that multiple entry points can share one package, build,
asset directory, and frontend image ([target Vite config](../../frontend/vite.config.ts),
[frontend nginx](../../frontend/nginx.conf)). CoParent differs because it has a BrowserRouter and a
PWA. The source plugin emits a root-scoped worker and caches all API requests
([source Vite config](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/ui/vite.config.ts:5));
adding that configuration globally could register CoParent behaviour for the portfolio or cache
private family responses on a shared device.

**Alternatives considered**:

- Remove PWA support in the first release. Rejected because install/update/offline status is an
  existing visible behaviour.
- Reuse a root worker on every origin. Rejected because product caches and lifecycle must not be
  shared accidentally.

## Decision 8: Plural hostname, same-origin API, deep-link fallback

**Decision**: Make `coparents.simonrowe.dev` canonical. The outer nginx block will proxy
`/api/coparent/**` to the backend, shared `/assets/**` and CoParent PWA assets to the frontend, and
all remaining paths to the CoParent entry fallback so `/calendar`, `/messages`, `/auth/callback`,
and invitation links work on refresh. If the singular hostname is active, it redirects permanently
to the plural host after preserving path and query.

**Rationale**: The source consistently assumes singular `coparent.simonrowe.dev`
([API environment](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/.env.example:5)),
so the requested plural name is a coordinated rename. Term Time's exact-root mapping is insufficient
because CoParent has real client routes; all browser fallbacks must load the CoParent HTML while
leaving shared asset URLs unchanged ([Term Time proxy](../../config/nginx/nginx-proxy.conf)).
Using a relative `/api/coparent` base keeps production calls same-origin.

**Alternatives considered**:

- Keep the singular hostname. Rejected in favour of the hostname requested for this migration.
- Call `api.simonrowe.dev` directly. Rejected because it adds CORS and leaks an infrastructure
  choice into the browser when the reverse proxy can provide a same-origin interface.

## Decision 9: Backups and operability are release gates

**Decision**: Add all ten collections to `BackupService`, `RestoreService`, restore import ordering,
and post-restore index creation. Add the public hostname to monitoring and smoke tests, include it in
maintenance/unavailable branding, keep feature flags default-off, and add a runbook covering Auth0,
data rehearsal, cutover, rollback, and retirement of the old runtime.

**Rationale**: The target backup and restore paths are explicit allowlists
([backup](../../backend/src/main/java/com/simonrowe/dataops/BackupService.java),
[restore](../../backend/src/main/java/com/simonrowe/dataops/RestoreService.java)). Omitting a new
collection silently omits sensitive child, medical, and communication data from recovery. The
source has CI workflows but no working production deployment surface; its API Dockerfile also uses
a package-filter name that does not match the actual package
([Dockerfile](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/Dockerfile:17),
[package name](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/package.json:2)).
The destination production path replaces rather than ports it.

**Alternatives considered**:

- Ship first and add backup/monitoring later. Rejected because this product contains private family
  data and there is no acceptable window in which it is intentionally unrecoverable.

## Decision 10: No Mongo transactions on the standalone production node

**Decision**: Keep writes compatible with a standalone Mongo deployment. Encapsulate each
multi-document workflow behind one application-module interface, make steps idempotent, order them
so a retry can complete safely, and compensate where an externally visible side effect occurs.
Email is sent after the invitation is durably stored; a delivery failure leaves a resendable pending
invitation rather than rolling data back.

**Rationale**: Family creation and invitation acceptance currently perform sequential writes across
several documents without a transaction
([family workflow](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/src/families/families.service.ts:44),
[invitation workflow](/Users/simonrowe/workspace/simonjamesrowe/coparent-monorepo/apps/api/src/invitations/invitations.service.ts:257)).
The production Mongo topology is a single node, so planning around replica-set transactions would
silently introduce an undeployable assumption.

**Alternatives considered**:

- Convert production Mongo to a replica set solely for this migration. Rejected as disproportionate
  infrastructure work.
- Leave sequencing spread across controllers/repositories. Rejected because retries and partial
  failures would have to be understood at every caller rather than one deep module.
