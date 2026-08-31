# Runbook: Dependency-Track (prod)

Owner-executed. These steps touch the production deploy dir
(`~/workspace/simonjamesrowe/simonrowe-dev-monorepo`) and are intentionally **not**
automated in the app workspace.

## Access

SSH access to the Pi now exists: connect as the usual admin user. The host address and
credentials are in the usual env store, not in this file or any other tracked file (this
repo is public, so no LAN topology is committed); the Pi is reachable on the LAN only.
Where a command below needs to run on the Pi, it is written as a bare command under a
"Run on the Pi" heading — paste it into that SSH session, or hand it over as a copy-paste
block if you are working from a machine without the key configured.

## Deploy checklist

Work through this in order. **Step 0 is a hard prerequisite** — skip it and everything else
looks like it succeeded while the hostname simply does not resolve.

- [ ] **Step 0: the `dependency-track.simonrowe.dev` DNS record must exist before deploying.**
      Nothing in this repo creates it. In Cloudflare, `dependency-track.simonrowe.dev` needs a
      record pointing at the same target as the other public hostnames
      (`simonrowe.dev`, `api`, `console`, `langfuse`) — copy whatever those use rather than
      inventing a new target.

      **Confirm, do not assume, that the pinggy custom-domain mapping covers a new subdomain
      label.** The `PINGGY_TOKEN` maps to the `*.simonrowe.dev` custom domain, but whether an
      unseen label is served automatically or must be registered with pinggy has not been
      verified for a *new* label — check it before concluding the deploy is healthy. Verify
      resolution and reachability end to end from a machine off the LAN:

      ```bash
      dig +short dependency-track.simonrowe.dev
      curl -s -o /dev/null -w "dt=%{http_code}\n" https://dependency-track.simonrowe.dev/
      ```

      An empty `dig` result, or `dt=000`/`530`, means DNS or the tunnel mapping is missing.
      Fix that first: until it resolves, CI's five SBOM uploads fail **invisibly**, because the
      `sbom` job is `continue-on-error: true` (see "The `continue-on-error: true` trap").

- [ ] **Step 1: put the required variables in the deploy-dir `.env`.**
      `DEPENDENCYTRACK_DB_PASSWORD`, `DEPENDENCYTRACK_KEK`, `DEPENDENCYTRACK_OIDC_ISSUER` and
      `DEPENDENCYTRACK_OIDC_CLIENT_ID` are declared with compose's required-variable syntax
      (`${VAR:?...}`). If any is missing **or empty**, every `docker compose` command against
      `docker-compose.prod.yml` fails immediately with a named error — including unrelated ones
      like restarting the backend. That is deliberate (see "The passwordless-role trap"), but it
      means the `.env` must be updated *before* the first deploy that includes these services.

- [ ] **Step 2: complete the Auth0 setup** — see the Dependency-Track SSO section in
      `docs/auth0-setup.md`. This is human-gated and cannot be automated.

- [ ] **Step 3: deploy.** Run on the Pi, from the deploy directory:

      ```bash
      cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
      git pull
      docker compose -f docker-compose.prod.yml up -d
      docker compose -f docker-compose.prod.yml ps
      ```

      Expect every container `running`, with `dependencytrack-db-init` `exited (0)`.

- [ ] **Step 4: verify.**

      ```bash
      curl -s -o /dev/null -w "dt=%{http_code}\n" https://dependency-track.simonrowe.dev/
      curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available
      ```

      Expect `dt=200` and `true`. The API server's first boot runs schema migrations and starts
      mirroring vulnerability data — expect sustained high CPU on ARM and a slower-feeling site
      while it runs, so deploy at a quiet time.

- [ ] **Step 5: change the local `admin` password — do this before anything else in the UI.**
      Dependency-Track seeds `admin`/`admin` with a forced password change, and this instance is
      public. That account bypasses Auth0 completely. Confirm the default is dead:

      ```bash
      curl -s -o /dev/null -w '%{http_code}\n' -X POST \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode 'username=admin' --data-urlencode 'password=admin' \
        https://dependency-track.simonrowe.dev/api/v1/user/login
      ```

      `401` with an empty body is correct; `401` with the body `FORCE_PASSWORD_CHANGE` means the
      default password is still live.

- [ ] **Step 6: create the OIDC group, the team, and the mapping between them** — all three, per
      step 13 of `docs/auth0-setup.md`. A team on its own is **not** enough: Dependency-Track
      does not match claim values to team names. Then log in via Auth0 and confirm you can see
      the portfolio.

- [ ] **Step 7: create the `CI Upload` team** with `BOM_UPLOAD`, `PROJECT_CREATION_UPLOAD` and
      `VIEW_PORTFOLIO`, generate its API key, and store it as the `DEPENDENCYTRACK_API_KEY`
      GitHub secret — otherwise the publish workflow's SBOM uploads fail invisibly.

- [ ] **Step 8: enable the OSV vulnerability source.** A fresh install only enables NVD, which
      matches by CPE and therefore cannot produce a single finding for a Maven or npm dependency.
      See "Zero vulnerabilities on the dependency SBOMs" below — without this step the whole
      deployment looks healthy and reports nothing.

- [ ] **Step 9: enable the Trivy analyzer.** Step 8 covers Maven/npm/Go; **nothing** covers the
      container images' OS packages until this is done, and the symptom is `Risk Score 0` on a
      project that is not clean. It cannot be deployed — it is runtime config in Postgres, so no
      `DT_*` variable sets it and no deploy reconciles it. Procedure and expected finding counts
      are in "OS packages: why a container project's `0` did not mean clean" below. Check
      `df -h /` first: `trivy-server` caches a 1.27 GiB database.

## What `simonrowe-dev/backend` actually covers (runtimeClasspath only)

`./gradlew cyclonedxBom` runs on the **root** project and, left unconfigured, resolves
**every resolvable configuration of every Gradle module** — `checkstyle`,
`compileClasspath`, `testCompileClasspath` and `testRuntimeClasspath` included. The root
`build.gradle.kts` therefore pins it:

```kotlin
tasks.cyclonedxBom {
    setIncludeConfigs(listOf("runtimeClasspath"))
}
```

**This is load-bearing, and it is not tuning.** Without it the SBOM reported build- and
test-time tooling as production vulnerabilities. SIM-9 is the worked example: of 19 backend
findings, **6 were never in the deployed jar** —

| Component | Really came from | Ships? |
|:----------|:-----------------|:-------|
| `commons-beanutils` 1.10.1 (1 HIGH) | Checkstyle's own tool classpath | no |
| `plexus-utils` 3.3.0 (1 HIGH) | Checkstyle, via `plexus-component-metadata` | no |
| `netty-codec` 4.1.135.Final (1 HIGH) | `testRuntimeClasspath`, Testcontainers' docker-java | no |
| `commons-compress` 1.24.0 (2 MEDIUM) | `testCompileClasspath`/`testRuntimeClasspath`, same | no |

Scoping to `runtimeClasspath` took the SBOM from 480 components to 352 and left **zero**
entries tagged `cdx:maven:package:test=true`. Both modules are still covered — the task is
on the root project, so `backend` and `software-factory` each contribute their own
`runtimeClasspath`. That second point matters: `opentelemetry-api` was reported at both
1.49.0 and 1.64.0 because `backend` already pinned 1.64.0 while `software-factory` still
resolved the vulnerable 1.49.0 through `temporal-spring-boot-starter`. A fix applied to one
module only leaves the finding alive with two versions listed.

Diagnose a suspected phantom finding by checking which configuration actually carries the
component before bumping anything:

```bash
./gradlew :backend:dependencyInsight --configuration runtimeClasspath \
  --dependency commons-beanutils:commons-beanutils
# "No dependencies matching given input were found" => it does not ship.
```

Build- and test-time dependencies are not going unwatched: the three container-image SBOMs
that `publish.yml` generates with **trivy** (`aquasecurity/trivy-action`, since 2026-08-31 —
see "OS packages" below for why it is not `anchore/sbom-action` any more) cover what is
actually installed in the shipped images.

## Accepted finding: GHSA-8jxr-pr72-r468 on `mcp-core` (no upgrade path, not applicable)

`io.modelcontextprotocol.sdk:mcp-core` sits at **0.18.3**, reached through
`spring-ai-starter-mcp-server-webmvc`. GHSA-8jxr-pr72-r468 (HIGH, DNS rebinding) is fixed in
**1.0.0**. Do not force that bump, and expect this finding to keep reappearing on every scan.

**There is no upgrade path at this Spring AI version.** `mcp-core` has 1.x and 2.x releases,
but the Spring transport module the backend actually depends on,
`io.modelcontextprotocol.sdk:mcp-spring-webmvc`, **has never been released above 0.18.4** —
and 0.18.4 is still affected. Forcing `mcp-core` to 1.0.1 would leave `mcp-spring-webmvc`
0.18.x, compiled against the 0.18 core API, on the same classpath. Spring AI 1.1.8 is the
current release; nothing upstream carries the fix yet.

**It is also not applicable here**, on two independent grounds:

1. The advisory itself names the exemption — *"Some default server configurations and
   frameworks come with embedded `Origin` header validation. MCP servers built using those
   are NOT vulnerable to this issue. For example: **Spring AI**."* The backend's MCP server
   is `spring-ai-starter-mcp-server-webmvc`.
2. The stated precondition is *"when the web server serving HTTP traffic to the MCP server
   does not perform standard CORS checks"*. This one does: `WebConfig.corsConfigurationSource()`
   registers an explicit origin allowlist against `/**` (so `/mcp` too), `SecurityConfig`
   enables it with `.cors(Customizer.withDefaults())`, and production sets
   `CORS_ALLOWED_ORIGINS: https://simonrowe.dev,https://www.simonrowe.dev` — an allowlist,
   never `*`. The attack needs a victim's browser to reach the server cross-origin, which is
   precisely what that blocks.

Suppress it in Dependency-Track (Audit → the finding → analysis **NOT_AFFECTED**, with the
reasoning above) rather than leaving it to be re-triaged every scan. Re-open the question
when `mcp-spring-webmvc` ships a 1.x release, or when Spring AI moves to the 1.x MCP SDK.

## Zero vulnerabilities on the dependency SBOMs (out-of-the-box configuration)

**Symptom.** All five projects import cleanly with sensible component counts, but only the
container-image projects show any findings. `simonrowe-dev/backend` and `simonrowe-dev/frontend`
sit at 0 vulnerabilities forever, and the portfolio dashboard looks reassuringly quiet.

**This is not an SBOM or upload problem.** Confirm that first — the component counts prove the
BOMs arrived intact:

```bash
# Any API key with VIEW_PORTFOLIO works; the CI Upload key already has it
curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  'https://dependency-track.simonrowe.dev/api/v1/project?pageSize=100' \
  | jq -r '.[] | "\(.name) components=\(.metrics.components) vulns=\(.metrics.vulnerabilities)"'
```

**Root cause.** A default install enables exactly one vulnerability source, NVD, and one
analyzer, `internal`. NVD describes affected products as **CPEs**, and — in Dependency-Track's
own words — *"the internal analyzer skips components that lack a valid CPE when evaluating NVD
data."* The matching identifier per source is:

| Source | Matches on | Enabled by default |
|:-------|:-----------|:-------------------|
| NVD | CPE | yes |
| OSV | PURL | **no** |
| GitHub advisories | PURL | **no** (also needs a PAT) |

The CycloneDX Gradle plugin and `@cyclonedx/cyclonedx-npm` both emit **PURLs only, no CPEs**
(verified: 476/476 Maven components and 493/493 npm components had `cpe: null`). So with only
NVD mirrored, those two projects are *structurally* unmatchable — nothing is broken, there is
simply no data source that speaks their identifier.

The image projects appeared to work only because `syft` stamps CPEs on `deb`, `apk` and `golang`
packages. Every finding they had came back `source: NVD`, `analyzer: internal`, and all of it on
`pkg:deb/ubuntu/openssl` and `pkg:golang/stdlib`. Note that `syft` *also* synthesises CPEs for
jars inside an image, but they are vendor-guessed from the artifact name
(`cpe:2.3:a:a2a-java-sdk-common:a2a-java-sdk-common:...`), so they almost never match a real NVD
CPE — the image projects were not covering the Java dependencies either.

**Fix — enable OSV** (Administration → Vulnerability Sources → Osv): tick **Enabled**, keep the
default ecosystems (`npm`, `Go`, `Maven`, `NuGet`, `PyPI`), **Save**, then **Mirror now**. OSV
needs no credentials. Because OSV re-publishes GHSA records under their GHSA IDs, the findings
land attributed `source: GITHUB` — **enabling the GitHub source separately is largely redundant**
and only buys alias/CVSS detail in exchange for having to manage a PAT.

Measured on the production Pi, 2026-07-30:

- The mirror took **~31 minutes** and grew the vulnerability database from **371,149 → 636,750**
  records. Much of that bulk is `MAL-*` malicious-package advisories, which OSV ships inside the
  npm and PyPI ecosystems. Budget for the disk growth — see "Disk and database size" below.
- Mirroring alone does **not** re-evaluate existing projects. Trigger re-analysis, then refresh
  metrics, or the UI keeps showing the old zeros:

  ```bash
  for uuid in $(curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
      'https://dependency-track.simonrowe.dev/api/v1/project?pageSize=100' | jq -r '.[].uuid'); do
    curl -s -X POST -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
      "https://dependency-track.simonrowe.dev/api/v1/finding/project/${uuid}/analyze"
    curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
      "https://dependency-track.simonrowe.dev/api/v1/metrics/project/${uuid}/refresh"
  done
  curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
    https://dependency-track.simonrowe.dev/api/v1/metrics/portfolio/refresh
  ```

- Result: portfolio findings went from 49 to 189, projects at risk from 2 to 4, vulnerable
  components from 3 to 32. Per project: `backend` 0 → 13, `frontend` 0 → 36,
  `backend-image` 25 → 76, `reviewer-image` 24 → 64.

**This left the container images blind for a month.** Resolved by the Trivy analyzer — see the
next section, which supersedes the "add `Alpine` under Add Ecosystem" advice that used to be
here. Adding the distro OSV ecosystems is *not* the fix; the reasoning is below.

## OS packages: why a container project's `0` did not mean "clean" (2026-08-31)

`simonrowe-dev/frontend-image` read **Risk Score 0** while carrying **20 fixable Alpine
findings, 2 of them HIGH**, on `openssl 3.5.7-r0` (fixed in `3.5.8-r0`). `backend-image`
showed 25 findings on the single Ubuntu package NVD happened to match (`openssl`) where a
distro-aware scan finds 242. Neither had an error anywhere.

**The live configuration, read from the v5 API** (see "Reading the analyzer config" below):

| Extension | State on 2026-08-31 | Can it match `pkg:apk/*` or `pkg:deb/*`? |
|:---|:---|:---|
| `osv` source | enabled; ecosystems `npm, Go, Maven, NuGet, PyPI` | no — no distro ecosystem mirrored |
| `github` source | enabled, alias sync on | no — GHSA covers language ecosystems only |
| `nvd` source | **disabled**; data frozen at 2026-08-25 | in principle, via CPE — in practice almost never |
| `internal` analyzer | enabled | only against the above |
| `trivy` analyzer | **disabled**, no server, `scanOs: false` | yes — this is the one that can |

So nothing mirrored spoke the identifier the OS packages carry, and the projects reported
`0` exactly as they would have if they were clean. Same silent, inverted failure as the
CPE-vs-PURL problem in the section above — one layer down.

**Why NVD "sort of" worked and that made it worse.** `openssl` and `perl` were the only two
distro packages ever reported, because they are among the few whose Ubuntu *source* package
name coincides with a real NVD `vendor:product` pair. And CPE matching cannot model
Canonical's backports, so it flags every CVE ever filed against `openssl 3.0.13` regardless
of whether the fix is already in `3.0.13-0ubuntu3`. Two packages, over-reported, standing in
for the ~242 that were never looked at.

**Why adding `Alpine`/`Ubuntu` to the OSV ecosystem list is NOT the fix.** OSV's distro
records are keyed on the **source** package (their `purl` carries `arch=source`), while an
SBOM lists **binary** packages. Measured on `backend-image`: 69 of 99 debs have a source name
that differs from the binary name — `libssl3t64`→openssl, `libc6`→glibc,
`libgnutls30`→gnutls28, `libsystemd0`→systemd — and 33 of those are vulnerable *only* via the
source name (340 advisory rows). Name-only matching misses all of them, and mirroring three
distro ecosystems is a large, permanent addition to a database already at 662k rows on a Pi.
The Trivy analyzer resolves source packages properly, so it gets the whole set for none of
that cost.

**The chain, and the one thing that will silently break it.** Dependency-Track reads an OS
package's source name from the `aquasecurity:trivy:SrcName` **component property**, and falls
back to the purl's binary name when absent
(`TrivyVulnAnalyzer.processOsPackage`, verified in 5.0.3). Only Trivy emits that property;
syft records the same fact as an `upstream=` purl qualifier, which Dependency-Track does not
read. Measured against one trivy server, same image, same 71 apk packages:

```text
trivy-generated SBOM -> 20 findings (2 HIGH)
syft-generated  SBOM ->  0 findings   (correctly classed os-pkgs/alpine, matching nothing)
```

That is why `publish.yml` generates the three image SBOMs with **trivy**, not
`anchore/sbom-action`. Reverting that one step turns OS coverage back off, and the symptom is
`0`, not an error. The OS itself is resolved by a separate route that works with either tool:
DT keys the blob on `<PkgType>-<distro>` from the purl qualifiers and matches it against the
`operating-system` component (`alpine-3.24.1`, `ubuntu-24.04`), so do not "tidy" the
`operating-system` component out of the BOM either.

Bonus, not the point: trivy's SBOM has **1/14th** the components (72 vs syft's 1283 for the
frontend image — 1211 of syft's were purl-less `type: file` entries no analyzer can use)
while finding *more* real packages (601 vs 301 jars on `backend-image`).

### Enabling it (one-time, and it is not reconcilable by deploy)

The compose side (`trivy-server` + `trivy-cache` volume) ships in the repo. The
Dependency-Track side is **runtime config in Postgres**, not deployment config — the Trivy
analyzer reads it via `getRuntimeConfig`, so there is no `DT_*` environment variable that can
set it and a deploy cannot reconcile it. It must be done once, by hand, after the deploy:

1. **Administration → Secrets → Create**, name `TRIVY_TOKEN`, value = `TRIVY_SERVER_TOKEN`
   from `.env` (default `dependency-track` if unset). The `apiToken` field is
   `x-secret-ref: true` — it holds the *name* of a secret, not the value, the same way the
   GitHub source's field holds `GH_TOKEN`.
2. **Administration → Analyzers → Trivy**: tick **Enabled**, API URL
   `http://trivy-server:4954`, API Token `TRIVY_TOKEN`, tick **Scan OS**, leave **Scan
   Library** ticked. **Ignore Unfixed** is a judgement call — it drops 67 of
   `software-factory-image`'s 94 findings (Ubuntu ships many advisories it will not fix) and
   almost none of `backend-image`'s. Start with it **off** so the numbers below match, and
   turn it on if the noise is not worth it.
3. **Administration → Vulnerability Sources → Osv**: tick **Alias Synchronization Enabled**.
   Unrelated to Trivy, one click, and it stops the same advisory being counted twice under
   its GHSA and `GO-*` ids — `x/crypto` currently shows 9 `GITHUB` + 10 `OSV` findings for
   the same set.
4. Re-analyse, or the pages keep showing the old zeros (mirroring and config changes never
   backfill on their own) — the `analyze` + `metrics/refresh` loop in the section above.

**Expect the portfolio to get much noisier, and that is the correct outcome.** Trivy's own
scan of the three images on 2026-08-31, which is roughly what should appear:

| Project | OS findings | Language findings | Fix available |
|:---|---:|---:|---:|
| `frontend-image` | 20 (2 HIGH) | 0 | 20 of 20 |
| `backend-image` | 242 (3 HIGH) | 5 (2 HIGH) | 228 of 247 |
| `software-factory-image` | 94 (0 HIGH) | 8 (8 HIGH) | 35 of 102 |

No CRITICALs anywhere. Note where the HIGHs actually sit: `software-factory-image`'s eight are
all Go (`stdlib`), and its 94 OS findings are entirely MEDIUM/LOW — so "94 new findings" and
"what to act on" are very different lists.

Most of that is "the base images are behind", not per-CVE work: it is discharged by
rebuilding on a current base, not by 300 individual triages. Do not read the jump from ~107
to ~500 findings as a regression — the old number was the measurement failing, not the risk
being low.

### Not fixed here: Go pseudo-version false positives

The `internal` analyzer reports **every** historical Go advisory whose fixed version is a
pseudo-version (`0.0.0-20190125091013-…`) against current modules, because it compares
`0.58.0` as *older* than `0.0.0-2019…`. Correct semver puts a prerelease below `0.0.0`, so
this is a comparator defect. On 2026-08-31 that was 21 findings on
`golang.org/x/net@v0.58.0` (OSV: **0**) and 19 on `x/crypto@v0.55.0` (OSV: **1**) — ~40 of
`backend-image`'s 69 findings and 9 of `software-factory-image`'s 37, and most of why their
risk scores read 325 and 190.

No configuration works around it, and enabling Trivy does not remove them — it adds a second,
correct opinion alongside the wrong one (trivy finds 1 gobinary finding on `backend-image`
where `internal` finds 42). Wants an upstream issue. Until then, verify any `pkg:golang/*`
finding before acting on it; the tell is a `0.0.0-` prefix on the fixed version:

```bash
curl -s https://api.osv.dev/v1/querybatch -d '{"queries":[
  {"package":{"ecosystem":"Go","name":"golang.org/x/net"},"version":"0.58.0"}]}' | jq .
```

### Reading the analyzer config (v5 moved it)

`/api/v1/configProperty` no longer carries any of it — it returns 45 rows with no `scanner`
group at all, which reads as "nothing is configured" and is a trap. In 5.x:

```bash
# Lists: internal, oss-index, snyk, trivy, vuln-db / github, nvd, osv
curl -s -H "Authorization: Bearer ${DT_TOKEN}" \
  "https://dependency-track.simonrowe.dev/api/v2/extension-points/vuln-analyzer/extensions"
# The actual on/off state and settings
curl -s -H "Authorization: Bearer ${DT_TOKEN}" \
  "https://dependency-track.simonrowe.dev/api/v2/extension-points/vuln-analyzer/extensions/trivy/config"
curl -s -H "Authorization: Bearer ${DT_TOKEN}" \
  "https://dependency-track.simonrowe.dev/api/v2/extension-points/vuln-data-source/extensions/osv/config"
```

`DT_TOKEN` here is **not** an API key: log in through Auth0 in a browser and read
`sessionStorage.getItem('token')` (an opaque ~43-char string, not a JWT). Useful because the
shared env's `DEPENDENCYTRACK_API_KEY` is currently the KEK, not a key. Note
`/api/v1/user/self` reports `permissions: []` for an OIDC user even with full access — the
permissions are on the `DEV_PORTAL_ADMIN` team.

### Open question: who disabled NVD, and on purpose?

The `nvd` source is `enabled: false` with its newest record `published`/`updated`
**2026-08-25**, while OSV and GitHub refresh hourly. Nothing in git or this runbook records
the change. It is not urgent — its CPE matching on distro packages is the false-positive
engine described above, and Trivy replaces what it was contributing — but the frozen NVD rows
are still matching, so the stale `openssl`/`perl` deb findings will linger until a
re-analysis after Trivy is on. Note also that the feed URL it is configured with
(`https://nvd.nist.gov/feeds`, "JSON 2.0 feed files") is the retired legacy feed format, so
re-enabling it as configured may simply fail.

## The passwordless-role trap (fixed — do not undo it)

`dependencytrack-db-init` creates the `dtrack` role, and its `CREATE ROLE` is guarded by a
`SELECT 1 FROM pg_roles` check so it only runs once. That guard was originally the whole
story, which made an unrecoverable state reachable: if the service ever ran with
`DEPENDENCYTRACK_DB_PASSWORD` empty, Postgres logged
`NOTICE: empty string is not a valid password, clearing password`, **`CREATE ROLE` still
succeeded**, and the service printed "dependency-track database ready" and exited 0. The
apiserver then failed authentication forever — and because the role now existed, the guard
skipped the `CREATE` on every subsequent run, so adding the variable to `.env` and re-running
did **not** repair it.

Two changes close this, and both matter:

1. An **unconditional** `ALTER ROLE dtrack LOGIN PASSWORD '...'` runs after the guarded
   `CREATE`. It is idempotent, it repairs the passwordless state described above, and it
   re-syncs the role after a deliberate password rotation in `.env`.
2. The variables use compose's required-variable syntax, so an empty or missing value fails
   the command outright instead of producing a broken role.

If you ever suspect the role is wrong, check it on the Pi rather than guessing:

```bash
docker exec simonrowe-dev-monorepo-langfuse-db-1 psql -U postgres -tAc \
  "SELECT rolname, rolcanlogin, rolpassword IS NULL AS no_password FROM pg_authid WHERE rolname='dtrack'"
```

`no_password = t` means the role has no password set; re-running
`docker compose -f docker-compose.prod.yml up dependencytrack-db-init` with a correct `.env`
now fixes it.

**Password charset constraint:** the password is interpolated into the SQL as a single-quoted
literal, so a value containing a single quote (`'`) breaks the statement with a syntax error
and the init service exits non-zero. Generate `DEPENDENCYTRACK_DB_PASSWORD` without single
quotes (e.g. `openssl rand -base64 32`).

## Architecture

- **`dependencytrack-apiserver`** (`dependencytrack/apiserver:5.0.3`, pinned — never `latest`,
  which resolves to the unrelated 4.14.3 line) and **`dependencytrack-frontend`**
  (`dependencytrack/frontend:5.0.3`) are two containers in `docker-compose.prod.yml`.
- Both share the existing `langfuse-db` Postgres container — a new `dtrack` database and
  `dtrack` role, created idempotently by the one-shot `dependencytrack-db-init` service.
  **`langfuse-db` is now a shared dependency of two tools** (Langfuse and Dependency-Track):
  stopping it takes both down.
- nginx (`config/nginx/nginx-proxy.conf`) serves a single hostname,
  `dependency-track.simonrowe.dev`, split by path: `/api/` → `dependencytrack-apiserver:8080`,
  everything else → `dependencytrack-frontend:8080`. `/static/oidc-callback.html` deliberately
  falls through to the frontend location — it is a static SPA asset, not an API route, and must
  not be routed to the apiserver.
- The apiserver's `/data` (which is its `HOME`, holding the Lucene search indexes and analyzer
  working state) is a named volume, `dependencytrack-data`. Without it, every container
  recreation would wipe the indexes and force a full rebuild on four ARM cores.
- The apiserver's connection pool is capped at `DT_DATASOURCE_POOL_MAX_SIZE: "10"` (the v5
  default is 30) because `langfuse-db` is stock `postgres:15` at `max_connections=100` and is
  now shared with the Langfuse app and worker. Exhausting the server's connection slots would
  show up as **Langfuse** failing, which is a misleading place to start debugging.
- Auth is Dependency-Track's native OIDC against the existing Auth0 tenant, reusing the
  `DEV_PORTAL_ADMIN` role/claim that Langfuse already uses. See the Dependency-Track SSO
  section of `docs/auth0-setup.md` for the Auth0 side.
- **`trivy-server`** (`aquasec/trivy:0.74.0`) is the third container: the only vulnerability
  source in the stack that can match OS packages (`pkg:apk/*`, `pkg:deb/*`). No published
  port and no nginx route — it is reachable only on the compose network, over Twirp on
  `:4954`. Deliberately **not** a `depends_on` of the apiserver: an unreachable analyzer
  fails one scan and retries, whereas a startup gate would fail the one container in the file
  that is excluded from `FACTORY_DEPLOY_RECREATABLE`. Its `trivy-cache` volume holds a
  1.27 GiB vulnerability DB that it refreshes in place hourly. Unlike the apiserver, its
  `/healthz` is a true readiness signal: trivy loads the DB *before* binding the listener.
  See "OS packages" above; enabling the analyzer is a one-time manual step, not deployable
  config.

## The KEK gotcha (read this before touching the container)

Dependency-Track v5 encrypts secrets it stores in Postgres (OIDC client secrets, API keys, etc.)
with a **key encryption key (KEK)**. By default the KEK is generated and written to a keyset file
under `${user.home}/.dependency-track/keys/` — and the image sets `HOME=/data/`. When this
service was first added, nothing was mounted at `/data`, so every container recreation (image
pull, version bump, `docker compose up --force-recreate`) generated a **new** KEK and the app
refused to start against the *existing* database:

```
java.lang.IllegalStateException: KEK keyset mismatch. The loaded keyset does not contain all keys
previously registered in the database ...
```

Two things now protect against this, in order of importance:

1. The KEK is **pinned explicitly** via `.env`, which is what actually guarantees stability:

   ```yaml
   DT_SECRET_MANAGEMENT_DATABASE_KEK: ${DEPENDENCYTRACK_KEK:?set DEPENDENCYTRACK_KEK in .env}
   ```

   The `:?` means an empty or missing value fails the compose command outright, rather than
   letting the container fall back to generating its own keyset.

2. `/data` is now a **named volume** (`dependencytrack-data`), so even the fallback keyset file
   would survive container recreation. That volume exists primarily to persist the Lucene
   indexes, but it closes this hole too. Do not read it as a licence to unset the KEK: the
   pinned value in `.env` is the backed-up copy, a Docker volume is not.

**Rules:**

1. **`DEPENDENCYTRACK_KEK` must NEVER change once the `dtrack` database holds data.** Changing
   it is equivalent to losing the key: every secret already encrypted with the old KEK becomes
   permanently undecryptable, and the apiserver will crash-loop with the error above.
2. Treat it exactly like a database password: back it up in the same env store as the rest of
   the `.env` file, never rotate it casually, never regenerate it as part of routine maintenance.
3. **If it is genuinely lost** (not backed up, or corrupted), there is no way to recover the
   existing encrypted secrets. The only path forward is to drop and recreate the `dtrack`
   database (see "Restoring after data loss" below) and re-supply a fresh
   `DEPENDENCYTRACK_KEK`. This is an acceptable last resort because all Dependency-Track state
   is a **derived cache** — projects, findings and metrics can be rebuilt by re-running the
   `publish` workflow, which re-uploads all five SBOMs.

Verify the KEK is actually being honoured (already done once during implementation, evidence in
`.superpowers/sdd/2026-07-26-dependency-track/kek-verification.md` — repeat only if you suspect
regression):

```bash
docker logs simonrowe-dev-monorepo-dependencytrack-apiserver-1 2>&1 | grep -i "kek\|keyset\|secret manager"
# Expect: "Loading KEK from config" and no IllegalStateException.
```

## Memory limit is currently NOT enforced — read before trusting `docker stats`

`docker-compose.prod.yml` sets `mem_limit: 2g` on `dependencytrack-apiserver` (and limits on 16
other services). On this Pi, every one of those limits is **decorative until the memory cgroup
is enabled** — there is now a script for that, see the end of this section. The kernel boots with the memory cgroup controller disabled:

Run on the Pi:

```bash
cat /proc/cmdline
# Contains: cgroup_disable=memory
ls /sys/fs/cgroup/ | grep -i mem
# No memory.max / memory.current — the controller is off
docker stats --no-stream
# MEM USAGE / LIMIT column reads 0B / 0B for every container, including this one
```

Consequences:

- Docker accepts `mem_limit: 2g` in the compose file but the **kernel does not enforce it**. A
  runaway apiserver process could exceed 2 GB with no per-container throttling or OOM kill —
  only the host-wide OOM killer would eventually step in if total memory ran out.
- `docker stats` memory figures are **useless for this container** (and every other container on
  this host) until the fix below ships. Don't use them to conclude "it's fine" or "it's not."
- The real protection today is **headroom**, not the cap. Measured 2026-07-26: Pi 5, 4 cores,
  16.2 GB RAM, 8.6 GB genuinely available (`free -m`'s `available` column, which already
  accounts for reclaimable page cache), load average 0.5–0.8, no OOM history in `dmesg` or
  `journalctl -k`. A 2 GB apiserver fits comfortably inside that.

To make the limit real, use `scripts/enable-memory-cgroup.sh` (see
`docs/runbooks/prod-monitoring.md` for the full write-up):

```bash
./scripts/enable-memory-cgroup.sh --verify   # report current state
./scripts/enable-memory-cgroup.sh --apply    # backs cmdline.txt up first
# ... reboot at a planned time ...
./scripts/enable-memory-cgroup.sh --verify   # docker stats must not read 0B / 0B
```

⚠️ **Earlier revisions of this runbook said to "remove `cgroup_disable=memory` from
`/boot/firmware/cmdline.txt`". That instruction cannot work, and is probably why this was
never fixed.** The parameter is **not in `cmdline.txt`, `config.txt`, or any other file
under `/boot`** — the Raspberry Pi *firmware* prepends it to the kernel command line, so it
appears in `/proc/cmdline` while `grep -r cgroup /boot/firmware/` returns nothing. There is
nothing to delete. The working approach is to *append* an explicit re-enable
(`cgroup_enable=memory cgroup_memory=1`); the kernel parses its command line left to right,
and the later parameter wins over the firmware's earlier disable.

Two things to know before scheduling that reboot:

- A reboot is the **riskiest event on this host**. The 2026-08-14 reboot is what left both
  Dependency-Track and Langfuse broken for 10 days (see "The false-healthy trap" below).
  Verify the stack afterwards rather than assuming a green `docker compose ps`.
- The reboot is also the natural moment for the `mem_limit`/`mem_reservation` values added
  across `docker-compose.prod.yml` to take effect, since applying them requires recreating
  ~17 containers and they do nothing until the controller is on. Note that recreating
  `langfuse-db` briefly takes down **both** Langfuse and Dependency-Track, which share that
  Postgres instance.

Until that reboot happens, monitor memory pressure at the host level instead of
per-container. Run on the Pi:

```bash
free -m
dmesg | grep -i "out of memory" | tail -20
journalctl -k | grep -i "out of memory" | tail -20
```

Any OOM-kill hit in the last two commands means the apiserver (or something else) is pushing the
host over budget and `mem_limit` on `dependencytrack-apiserver` needs to come down, or something
else needs to move off this host.

## The false-healthy trap — `healthy` does not mean the API is serving

**Symptom:** the UI loads at `https://dependency-track.simonrowe.dev/` but you cannot log in
with Auth0, and every `/api/` request returns **502**. `docker compose ps` shows
`dependencytrack-apiserver` as **`healthy`**, and it has been "healthy" for days.

This happened for real: on 2026-08-14 the Pi rebooted, and the apiserver came back with

```
Exception in thread "main" java.lang.NoClassDefFoundError: dev/cel/runtime/CelFunctionBinding
    at dev.cel.extensions.CelStringExtensions$Function.<clinit>(CelStringExtensions.java:51)
    ...
    at org.dependencytrack.policy.cel.CelPolicyEngine.<init>(CelPolicyEngine.java:109)
    at org.dependencytrack.dex.DexEngineInitializer.contextInitialized(...)
```

The Jetty API listener on **8080 never started**, so nginx had nothing to proxy to. But the
JVM did **not exit** — the management/health listener on 9000 and the Hikari connection pool
run on non-daemon threads, so the process stayed alive. Therefore:

- `restart: unless-stopped` never fired (the policy only acts on process *exit*).
- The old healthcheck probed **only** `curl -fsS http://localhost:9000/health/ready`, which
  reports datasource reachability and knows nothing about the API port. It returned
  `{"status":"UP","checks":[{"name":"dataSources","status":"UP"}]}` throughout.
- Docker therefore reported `healthy`, and **Docker never restarts an unhealthy container
  anyway** — so even a correct healthcheck would not have self-healed it.

It stayed broken for 10 days.

**This is not a bad image.** `dev.cel.runtime.CelFunctionBinding` *is* present, in
`/opt/owasp/dependency-track/lib/runtime-0.13.1.jar`, and the classpath is
`dependency-track-apiserver.jar:lib/*` which includes it. (Note `cel-0.13.1.jar` contains only
the *lite* runtime classes, so grepping just that jar is misleading.) The same image had been
running fine for two weeks. A plain restart fixed it with no image change — treat it as a
transient failure during a contended cold start.

**Fix / diagnosis:**

```bash
# Is the API actually serving? This is the real question.
curl -s -o /dev/null -w '%{http_code}\n' https://dependency-track.simonrowe.dev/api/version
curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available    # must be: true

# Confirm from inside: only :9000 listening and not :8080 means this exact failure.
docker exec simonrowe-dev-monorepo-dependencytrack-apiserver-1 sh -c \
  'curl -fsS -o /dev/null http://localhost:8080/api/version; echo "8080 exit=$?"'
docker logs simonrowe-dev-monorepo-dependencytrack-apiserver-1 2>&1 | grep -E 'ServerConnector|NoClassDefFound'

# Remedy: a plain restart, then confirm Jetty bound 8080.
docker restart simonrowe-dev-monorepo-dependencytrack-apiserver-1
docker logs --since 5m simonrowe-dev-monorepo-dependencytrack-apiserver-1 2>&1 | grep ServerConnector
# want: Started oejs.ServerConnector{HTTP/1.1, (http/1.1)}{0.0.0.0:8080}
```

**What now prevents a repeat:** the healthcheck probes both ports —
`curl -fsS localhost:9000/health/ready && curl -fsS localhost:8080/api/version` — so this
failure now shows as `unhealthy`; and `scripts/monitor-prod.sh` both restarts unhealthy
containers (Docker will not) and independently probes
`https://dependency-track.simonrowe.dev/api/version` every minute. See
`docs/runbooks/prod-monitoring.md`.

## Disk and database size — check this periodically, not just on day one

Dependency-Track's `DEPENDENCYMETRICS_*` tables use daily partitions and **grow unbounded by
default**. They live in the same `dtrack` Postgres database, on the same undifferentiated 117 GB
partition as everything else on the Pi (Mongo, Elasticsearch, Kafka, ClickHouse, MinIO, container
images/logs — there is no separate volume for Postgres data).

Run this monthly, or any time the site feels slow. On the Pi:

```bash
df -h /
docker exec simonrowe-dev-monorepo-langfuse-db-1 psql -U postgres -c "\l+"
```

Since 2026-07-30 the mirrored vulnerability data is itself a significant share of that size:
enabling OSV took the `VULNERABILITY` table from 371k to 637k rows (see "Zero vulnerabilities on
the dependency SBOMs" above). That part is bounded by what upstream publishes, unlike the metrics
partitions below, but it is a one-off step change worth knowing about when reading the numbers.

**`trivy-server` adds ~1.3 GB of its own, outside Postgres**, in the `trivy-cache` volume
(`trivy.db` measured at 1.27 GiB on 0.74.0). Check the headroom **before** deploying it, and
remember it lands on the same undifferentiated partition:

```bash
df -h /
docker system df -v | grep -E "trivy-cache|VOLUME NAME"
```

It is a working cache, not data: deleting the volume costs one re-download, nothing else. The
container refreshes it in place on a 1-hour ticker, so it does not grow unbounded — it is
replaced. Note the *CI* side pulls a second, unrelated ~1 GB (trivy's Java DB, for jar
identification), but that lives on GitHub's runners and never touches the Pi.

Watch the `dtrack` row's `Size` column over time. One operator reported unbounded growth from
~50 GB to ~500 GB in a month on a large portfolio; this deployment only tracks five projects so
growth should be far smaller, but it is not bounded by default and the disk is shared with
everything else. If `dtrack` grows into a real share of the 72 GB free (measured 2026-07-26),
either configure Dependency-Track's built-in metrics retention (`DT_METRICS_RETENTION_DAYS` or
equivalent for the running version) or prune old `DEPENDENCYMETRICS_*` partitions manually.

## Break-glass access

If OIDC login is broken, use the local `admin` account (Dependency-Track's built-in break-glass
user, separate from Auth0/OIDC). The password lives in the usual env store, never in this repo.
Log in at `https://dependency-track.simonrowe.dev/` with username `admin` and use the
"local account" login option rather than "Login with Auth0". Change the default password on
first use if this is a fresh install (the UI will prompt for this).

**Never pull `dependencytrack/apiserver:latest` or `dependencytrack/frontend:latest`** — `latest`
currently resolves to `4.14.3`, a different major line with an incompatible config surface
(`ALPINE_*` vars instead of `DT_*`, different KEK handling). Always pin `5.0.3` (or a deliberately
chosen newer 5.x tag) in `docker-compose.prod.yml`.

## Diagnosing a missing login button

Silent OIDC failures are the norm here — nothing errors, the button just doesn't render. Check in
this order:

1. **Is OIDC even enabled/reachable from the frontend's point of view?**

   ```bash
   curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available
   ```

   Expect `true`. If this returns `false`, Dependency-Track's own config thinks OIDC isn't usable
   — go to step 2.

2. **Issuer trailing slash.** Dependency-Track does strict string equality between
   `DT_OIDC_ISSUER` and the `issuer` field in Auth0's discovery document, which always ends in
   `/`. `DEPENDENCYTRACK_OIDC_ISSUER` in `.env` must be `https://<tenant>.auth0.com/` — **with**
   the trailing slash. Confirm what Auth0 actually serves:

   ```bash
   curl -s "https://<tenant>.auth0.com/.well-known/openid-configuration" | grep -o '"issuer":"[^"]*"'
   ```

   The value in `.env` must match this byte-for-byte, trailing slash included.

3. **`OIDC_SCOPE` unset on the frontend container.** The frontend entrypoint assigns runtime
   config via `jq` unconditionally; an unset `OIDC_SCOPE` becomes `null`, which silently removes
   the login button with no error anywhere. Confirm it's set:

   ```bash
   docker exec simonrowe-dev-monorepo-dependencytrack-frontend-1 env | grep OIDC_SCOPE
   # Expect: OIDC_SCOPE=openid profile email
   ```

4. **Logs in but sees nothing.** There are two distinct causes, and both come back to
   `DT_OIDC_TEAM_SYNCHRONIZATION: "true"`. Check under **Administration → Access Management**,
   in both **OpenID Connect Groups** and **Teams** — the mapping between the two is the part
   most often missing.

   a. **The OIDC group or its team mapping is missing.** Dependency-Track does **not** match
      claim values against team names, so a team called `DEV_PORTAL_ADMIN` on its own grants
      nothing. Three objects are required — see step 13 of
      [the Auth0 setup guide](../auth0-setup.md#dependency-track-single-sign-on-sso):

      ```text
      claim value  →  OpenID Connect Group  →  mapping  →  Team  →  permissions
      ```

      The **group** name is the one that must equal the `https://simonrowe.dev/roles` claim
      byte-for-byte, including case; the team name is arbitrary. Check all three at once
      rather than guessing, from the deploy directory:

      ```bash
      PW=$(grep '^DEPENDENCYTRACK_DB_PASSWORD=' .env | cut -d= -f2-)
      docker exec -e PGPASSWORD="$PW" simonrowe-dev-monorepo-langfuse-db-1 \
        psql -h 127.0.0.1 -U dtrack -d dtrack -c \
        'SELECT g."NAME" AS oidc_group, t."NAME" AS mapped_team,
                (SELECT count(*) FROM "TEAMS_PERMISSIONS" tp WHERE tp."TEAM_ID"=t."ID") AS perms
         FROM "MAPPEDOIDCGROUP" m
         JOIN "OIDCGROUP" g ON g."ID"=m."GROUP_ID"
         JOIN "TEAM" t ON t."ID"=m."TEAM_ID";'
      ```

      Zero rows means the group, the team or the mapping between them is absent. Note that
      Dependency-Track never auto-creates groups from claims it observes, so an empty
      `OIDCGROUP` table says nothing about whether the claim is arriving — rule this cause out
      first, then move to (b).

   b. **The claim is missing from the ID token, so previously assigned teams get STRIPPED.**
      With team synchronisation enabled, Dependency-Track *reconciles* the user's team
      membership from the claim on **every login** — it does not merely add teams. If the
      `https://simonrowe.dev/roles` claim is absent or empty in the ID token (the
      `Add roles to tokens` Action not deployed, removed from the Login flow, or the user's
      role unassigned), then any team you assigned by hand in the UI is **removed** on their
      next login. Symptom: access that worked yesterday is gone today and re-assigning the
      team in the UI "fixes" it until the next login. Fix the claim, not the team assignment
      — decode the ID token and confirm the claim is present before touching Teams.

## Rotating the CI API key

1. In the UI: **Administration → Access Management → Teams → `CI Upload`** → regenerate the API
   key.
2. Update the GitHub secret:

   ```bash
   gh secret set DEPENDENCYTRACK_API_KEY --repo simonjamesrowe/simonrowe-dev-monorepo
   # paste the new key when prompted
   ```

3. Re-run the last `publish` workflow to confirm the new key works end to end:

   ```bash
   gh run list --repo simonjamesrowe/simonrowe-dev-monorepo --workflow publish.yml --limit 1
   gh run rerun --repo simonjamesrowe/simonrowe-dev-monorepo <run-id>
   ```

4. Confirm with the project check below — do not trust the workflow's green tick alone (see next
   section).

## The `continue-on-error: true` trap

The `sbom` job in `.github/workflows/publish.yml` is deliberately `continue-on-error: true` —
Dependency-Track running on a Pi behind a tunnel must never block a production deploy. That means
**the Publish workflow can show fully green while every SBOM upload silently failed** (expired
API key, DT down, network blip). The workflow's status is not evidence of anything. The only real
confirmation is checking the five projects directly:

Export the API key from your usual env store first (never paste it inline, never commit it):

```bash
export DEPENDENCYTRACK_API_KEY="<value from env store>"

for project in "simonrowe-dev/backend" "simonrowe-dev/frontend" "simonrowe-dev/backend-image" "simonrowe-dev/frontend-image" "simonrowe-dev/reviewer-image"; do
  echo "=== $project ==="
  curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
    "https://dependency-track.simonrowe.dev/api/v1/project/lookup?name=${project}&version=main" \
    | grep -o '"lastBomImport":[^,]*'
done
```

Expect a recent (non-null) `lastBomImport` timestamp for all five. A missing project, a `null`
timestamp, or a stale timestamp older than the last merge to `main` means the upload failed —
check the `sbom` job's logs directly (`gh run view <run-id> --log`) rather than trusting the
overall workflow conclusion.

## Manual SBOM upload (when CI has silently failed)

Generate and upload each SBOM by hand, matching what the CI job does. Every path below is
relative to the repo root — start there.

The CycloneDX Gradle plugin is applied to the **root** project (`build.gradle.kts`), not to
`:backend`, so `cyclonedxBom` must be invoked from the repo root — from `backend/` it fails
with `Task 'cyclonedxBom' not found in project ':backend'`. The output path
`build/reports/bom.json` is likewise relative to the repo root.

```bash
# Backend — from the repo root
./gradlew cyclonedxBom
curl -X POST "https://dependency-track.simonrowe.dev/api/v1/bom" \
  -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  -F "autoCreate=true" \
  -F "projectName=simonrowe-dev/backend" \
  -F "projectVersion=main" \
  -F "bom=@build/reports/bom.json"

# Frontend — writes frontend/bom.json, so the upload runs from inside frontend/
cd frontend && npm run sbom
curl -X POST "https://dependency-track.simonrowe.dev/api/v1/bom" \
  -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  -F "autoCreate=true" \
  -F "projectName=simonrowe-dev/frontend" \
  -F "projectVersion=main" \
  -F "bom=@bom.json"
```

For the container image SBOMs, generate with **trivy** — the same tool CI uses, and not
interchangeable with `syft` here: a syft SBOM lacks the `aquasecurity:trivy:SrcName`
properties and silently yields **zero** OS findings (see "OS packages" above). Upload with
`projectName=simonrowe-dev/backend-image`, `simonrowe-dev/frontend-image` or
`simonrowe-dev/software-factory-image`:

```bash
cd ..  # back to the repo root if you ran the frontend block above

# ghcr is not necessarily public, so authenticate. Any token with read:packages works.
export TRIVY_USERNAME="$(gh api user --jq .login)" TRIVY_PASSWORD="$(gh auth token)"

for svc in backend frontend software-factory; do
  docker run --rm -v "$PWD:/out" -v trivy-cache:/root/.cache/trivy \
    -e TRIVY_USERNAME -e TRIVY_PASSWORD aquasec/trivy:0.74.0 \
    image --quiet --format cyclonedx --output "/out/${svc}-image-bom.json" \
    "ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-${svc}:latest"

  # Sanity-check before uploading: an empty BOM uploads fine and reads as "clean".
  jq '[.components[]? | select(.purl)] | length' "${svc}-image-bom.json"

  curl -X POST "https://dependency-track.simonrowe.dev/api/v1/bom" \
    -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
    -F "autoCreate=true" \
    -F "projectName=simonrowe-dev/${svc}-image" \
    -F "projectVersion=main" \
    -F "bom=@${svc}-image-bom.json"
done
```

(The first run downloads trivy's Java DB, ~1GB, to identify jars — hence the named cache
volume. Generating an SBOM needs no vulnerability DB.)

(`${DEPENDENCYTRACK_API_KEY}` must be exported from the usual env store first — never paste the
key inline into a command you might paste into a shared terminal or commit to a file.)

## Restoring after data loss

Dependency-Track's own state (projects, findings, metrics history) is a **derived cache**: it can
always be rebuilt from source (the five SBOMs) plus a re-run of the `publish` workflow. It is
**deliberately excluded from `scripts/backup.sh`**, which only backs up MongoDB and
`backend/uploads` — not the `langfuse-db` Postgres container at all.

If the `dtrack` database is corrupted, or the KEK is genuinely lost (see above):

```bash
# From the deploy directory on the Pi
docker compose -f docker-compose.prod.yml stop dependencytrack-apiserver

docker exec simonrowe-dev-monorepo-langfuse-db-1 \
  psql -U postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='dtrack';"
docker exec simonrowe-dev-monorepo-langfuse-db-1 psql -U postgres -c "DROP DATABASE IF EXISTS dtrack;"
docker exec simonrowe-dev-monorepo-langfuse-db-1 psql -U postgres -c "DROP ROLE IF EXISTS dtrack;"

# Recreate cleanly via the idempotent init service (recreates the role, resets its password,
# and recreates the database)
docker compose -f docker-compose.prod.yml up dependencytrack-db-init

# Optional: also discard the Lucene indexes, which now refer to rows that no longer exist.
# Dependency-Track rebuilds them, so this only costs CPU.
docker volume rm simonrowe-dev-monorepo_dependencytrack-data

# If the KEK was the problem, set a fresh DEPENDENCYTRACK_KEK in .env now, before starting
# the apiserver against the empty database, then:
docker compose -f docker-compose.prod.yml up -d dependencytrack-apiserver dependencytrack-frontend

# Re-populate by re-running the last publish workflow (re-uploads all five SBOMs)
gh run list --repo simonjamesrowe/simonrowe-dev-monorepo --workflow publish.yml --limit 1
gh run rerun --repo simonjamesrowe/simonrowe-dev-monorepo <run-id>
```

Note this drops `dtrack` only — `langfuse-db`'s `langfuse` database and the rest of the stack are
untouched (confirmed during implementation, see
`.superpowers/sdd/2026-07-26-dependency-track/kek-verification.md`).

## Notes

- `nginx` no longer needs all four upstreams running to restart safely. The old
  "nginx restart gotcha" was retired by the resolver fix in commit `62d26cc`; `CLAUDE.md` now
  documents the current behaviour under "nginx resolves upstreams at runtime, not just at boot".
  This applies to `dependency-track.simonrowe.dev` the same as every other hostname: nginx boots
  regardless, and 502s only the specific downed host.
- nginx's container healthcheck hits `/healthz` in a `default_server` block that proxies to
  **nothing**, so nginx's health reflects nginx alone. Do not point it back at `/`: with no
  `default_server`, `Host: localhost` fell through to the `simonrowe.dev` block and proxied to
  `frontend`, so a stopped frontend marked nginx unhealthy — and because `pinggy` waits on
  nginx being `service_healthy`, the tunnel never started and every public hostname, Portainer
  included, went offline.
- Never `docker compose up -d` with no service names on a developer machine that also has
  `PINGGY_TOKEN` configured — it starts every service including `pinggy`, which would hijack the
  single production tunnel. Always name services explicitly when testing locally.
