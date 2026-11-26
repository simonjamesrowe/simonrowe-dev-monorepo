# Admin dashboard (`/admin/dashboard`)

One page for the owner: uptime of every public hostname, the reading pipeline (articles in,
articles summarised), and Dependency-Track's findings. Each panel has its own endpoint under
`/api/admin/dashboard` (admin role) and loads and fails on its own, so one slow source holds up
nothing. Code: `backend/src/main/java/com/simonrowe/dashboard/`,
`frontend/src/pages/admin/DashboardAdmin.tsx` and `frontend/src/pages/admin/dashboard/`.

| Panel | Endpoint | Source |
|---|---|---|
| Public hostnames | `GET /uptime?days=` (1 to 120) | `uptime_hours`, written by the in-stack prober; outage issues from GitHub |
| Articles per week | `GET /content-flow?weeks=` (1 to 52) | `aggregated_articles.fetchedAt`, `article_summaries.completedAt` |
| Dependencies and vulnerabilities | `GET /vulnerabilities` | Dependency-Track 5 API |

Still to come: what was listened to (needs a listening log, because the playlist deletes a
listened item after 30 days), article themes, endpoint latency and Amplitude visitors.

## Uptime: why the backend probes

The off-Pi heartbeat (`prod-heartbeat.yml`) is set to run every 15 minutes, but GitHub runs
scheduled workflows when it has capacity: 292 runs in total by 26 November 2026, three to five
a day. That works as a last alarm and not as an uptime figure. So `UptimeProber` requests each
hostname in `dashboard.uptime.targets` once a minute, out through the internet and back in
through Cloudflare, pinggy and nginx, and a pass means the whole way in works. The URLs match
`scripts/prod-heartbeat.sh`. A probe passes on a `200` only; redirects are not followed.

- **Results are hourly counts**, one `uptime_hours` document per hostname per UTC hour, updated
  with `$inc`. A TTL index (`V068CreateUptimeHourIndexes`) expires them after 120 days. The
  collection is not backed up: losing it costs history only.
- **Minutes the prober missed are unknown, not up.** When the backend or the Pi is down nothing
  probes. A day with over an hour unprobed and no failures is drawn hatched ("not fully probed");
  an hour or more of failed probes is red; any failure is amber.
- **Days are cut in `Europe/London`.** London's offsets are whole hours, so UTC hours never
  straddle a London day. The October clock change makes one 25-hour day, which is counted as such.
- **On by default only in production**: `DASHBOARD_UPTIME_ENABLED` is `false` in
  `application.yml` and `true` in `docker-compose.prod.yml`, so a laptop's backend does not add
  its own requests to the production nginx logs.
- **Cost**: nine requests a minute through the tunnel, about 13,000 nginx log lines a day, roughly
  80 MB a month of the Grafana Cloud logs allowance (under 0.2%).
- It uses the JDK `HttpClient`, not Spring's `RestClient`, so the probes create no traces.

The **outage list** is the `prod-down` issues the heartbeat opens and closes, read anonymously
from GitHub and cached for five minutes (with an ETag, so an unchanged list costs nothing
against the 60-an-hour anonymous limit it shares with the `/status` changelog). If GitHub cannot
be read, the last list is served; with nothing cached, the panel says the list is unavailable.

## Articles per week

Arrived counts every aggregated article by `fetchedAt`, hidden ones included. Summarised counts
each **article** once, in the week its **first** `READY` summary finished: raising
`SUMMARY_FORMAT_VERSION` writes a second summary for the same article, and counting summaries
would count it twice.

## Vulnerabilities

The backend reads Dependency-Track with the same `DEPENDENCYTRACK_API_KEY` software-factory's
CVE scan uses. The backend loads the whole `.env`, so the key needs no compose change; compose
sets only `DASHBOARD_DEPENDENCYTRACK_BASE_URL` to the API server on the compose network. Two
requests per view: `/api/v1/project?excludeInactive=true` (each project's current metrics are
inline) and `/api/v1/metrics/portfolio/90/days` (the latest row of each day is kept).

A failure is shown on the panel, never thrown: "not configured" when there is no key, and
"answered 401" (or whatever status came back) when there is one Dependency-Track refuses. A 401
here means the CVE scan's key is wrong too. On 26 November 2026 the shared env file's
`DEPENDENCYTRACK_API_KEY` was identical to `DEPENDENCYTRACK_KEK` and got a 401, so the panel will
not work locally until that line holds a real key.

Image projects include about 40 phantom Go pseudo-version findings (a Dependency-Track analyzer
bug; see `docs/runbooks/dependency-track.md`), so their high counts read higher than the truth.
