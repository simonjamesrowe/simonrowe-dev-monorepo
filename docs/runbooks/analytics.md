# Analytics: Amplitude, session replay and the consent banner

All four apps (main site, Term Time, CoParent, Meals) send product analytics and session replays
to one Amplitude project in Amplitude's **EU data centre**, on the free Starter plan. Nothing is
loaded or sent until a visitor accepts the consent banner. Spec: `specs/055-amplitude-analytics/`.

## What is where

| Piece | File |
|---|---|
| Public API (`initAnalytics`, `track`, `identifyUser`, `openCookieSettings`) | `frontend/src/analytics/analytics.ts` |
| Event schema: every event and the only properties it may carry | `frontend/src/analytics/events.ts` |
| SDK setup: EU zone, proxy paths, autocapture, replay | `frontend/src/analytics/amplitude.ts` |
| URL scrubbing and the `app`/`page_path` tags | `frontend/src/analytics/enrichment.ts` |
| Replay masking per app | `frontend/src/analytics/policy.ts` |
| Consent banner (vanilla-cookieconsent) | `frontend/src/analytics/consent.ts` |
| First-party proxy to Amplitude | `frontend/nginx.conf` (`/amp/e`, `/amp/sr/`, `/amp/cfg/`) |
| Signed-in identity (Auth0 subject + `is_owner`) | `frontend/src/analytics/AnalyticsIdentity.tsx` |

The browser key is `VITE_AMPLITUDE_API_KEY`, a build argument in `Dockerfile.frontend` from the
`VITE_AMPLITUDE_API_KEY` GitHub secret. Amplitude browser keys are public by design: they can send
events, never read them. **With no key, analytics is off and the banner is not shown**, which is
the local default.

## How consent works

- `vanilla-cookieconsent` is self-hosted, so asking sends nothing anywhere. It is a full-width bar
  at the bottom with Accept, Reject and Choose given equal weight.
- The choice is one cookie, `sr_consent`, on `.simonrowe.dev`, so it covers all four apps.
- Before consent the Amplitude SDK is **not downloaded**. `amplitude.ts` is reached only by a
  dynamic import after Accept (about 105 KB gzipped). An event from before consent is dropped,
  never queued.
- "Cookie settings" re-opens the choice: in the main site's footer, Term Time's footer,
  CoParent's user menu and Meals' More page. Withdrawing opts the SDK out and deletes its
  cookies, local storage and IndexedDB, then reloads. Every later page load with analytics
  declined purges again, because the SDK writes its opt-out flag and unsent queue as the
  withdrawing page unloads.

## Privacy rules

1. **No free text or money in an event.** `events.ts` is a runtime schema; `track` drops any
   property it does not declare, and `id` values must match `^[A-Za-z0-9_:.-]{1,128}$`, so a
   sentence or an email address cannot pass for one. `events.test.ts` fails on a property named
   like `question`, `email` or `amount`.
2. **No private query strings.** Every URL in every event is cut down to an allowlist
   (`KEPT_QUERY_PARAMS`: `utm_*` and the site's deep-link parameters). `?q=` search text and
   Auth0's `code`/`state` never leave the browser.
3. **Replay masks every form field everywhere.** On top of that:
   - `data-amp-mask` hides text (the visitor's own chat messages, snippet notes, Meals notes);
   - `data-amp-block` hides an element entirely (the whole `/admin` area, the Meals MCP token);
   - CoParent's root carries `data-amp-mask` and its policy blocks every image, so a CoParent
     replay shows layout and clicks only. The same attribute keeps clicked text out of CoParent's
     autocaptured events.

   **The mask *level* in code is only a fallback.** The project's masking setting in Amplitude
   (Settings → Session Replay & Heatmaps, currently **Medium**) overrides `defaultMaskLevel`, and
   the plugin passes no per-URL levels through. Selectors and the `data-amp-*` attributes are
   kept alongside whatever is set remotely, so anything that must stay hidden is hidden by an
   attribute, never by the level. CoParent depends on rendering nothing outside its masked root:
   a portal (a modal library, say) would put its text outside the mask.
4. **Signed-in visitors are identified by their Auth0 subject only.** The owner is tagged
   `is_owner=true`, never excluded (Meals has no other user). Filter it out in charts.

### Why plugin order matters (read before changing `amplitude.ts`)

`siteEnrichment` scrubs URLs, but Amplitude registers its own plugins during `init`, **after**
ours, and anything that edits events afterwards undoes the scrub. Two did, found only by reading a
real request body:

- page URL enrichment stamped the full address onto every event: now
  `pageUrlEnrichment: false`, and `page_path` (no query) is added instead;
- attribution copied the campaign, `referrer` included, onto every event: now
  `trackingMethod: 'userProperty'`, so it travels once on an identify event that is scrubbed.

`analyticsWire.test.ts` drives the **real** SDK with a secret in the address bar and in
`document.referrer` and fails if either reaches the wire. Run it after any Amplitude upgrade.

Analytics **remote config is off** (`fetchRemoteConfig: false`), so a toggle in Amplitude's UI
cannot turn on capture this code has ruled out (network request tracking, say). Replay still
fetches its own remote config for sampling.

**Accepted gap:** a replay records the page address as the visitor saw it, query string included.
The replay SDK has no hook to rewrite it short of taking over its uploads. Search boxes are
masked, but a replay of `/news-events?q=…` shows the search in its address bar.

## The proxy

Ad blockers drop `*.amplitude.com`, so the SDK sends to the page's own origin and
`frontend/nginx.conf` forwards:

| Path | Amplitude EU host |
|---|---|
| `/amp/e` | `api.eu.amplitude.com/2/httpapi` (events) |
| `/amp/sr/…` | `api-sr.eu.amplitude.com/…` (replay upload) |
| `/amp/cfg/…` | `sr-client-cfg.eu.amplitude.com/…` (replay sampling config) |

These are every host the SDK contacts once diagnostics are off. Each route verifies the upstream
certificate, strips the site's cookies and passes the visitor's address as `X-Forwarded-For` (and
nothing else from the chain that arrived), which Amplitude
geolocates by. `scripts/test/test-frontend-analytics-proxy.sh` ties the nginx routes to the SDK's
`PROXY_PATHS`. The Vite dev server mirrors the three routes.

To check the proxy by hand (each should return Amplitude's own "invalid API key" error):

```bash
curl -s -X POST https://www.simonrowe.dev/amp/e -H 'Content-Type: application/json' \
  -d '{"api_key":"0000000000000000000000000000000","events":[{"event_type":"x","device_id":"d"}]}'
curl -s https://www.simonrowe.dev/amp/cfg/config/0000000000000000000000000000000
```

## Things to watch after deploy

- **Geolocation: Country "-" on every event means no visitor address is reaching Amplitude.**
  Amplitude does honour `X-Forwarded-For` (verified 2026-10-19: a direct event carrying
  `8.8.8.8` geolocated to the United States). At launch every event showed "-" because pinggy
  sent no visitor address at all, so the whole stack saw pinggy's Docker address. pinggy now
  starts with `x:xff`; `scripts/test/test-client-ip-chain.sh` guards the rest of the chain.
  Check it in Loki: the outer nginx logs `xff="..."` on every line, and `xff="-"` means pinggy
  is still sending nothing.
- **Replay uploads fit the outer proxy's limits (measured).** The first replay batch of a visit
  carries the full page snapshot. On www's home page, the heaviest page, it was 171 KB gzipped
  (2026-10-19), against nginx's default 1 MB body limit on the Term Time, CoParent and Meals
  hostnames (www allows 12 MB). A `413` on `/amp/sr/` in the nginx logs would mean a page has
  outgrown it; the fix is `client_max_body_size 12m;` in those blocks, which needs nginx
  recreated on the host (the config is a single-file bind mount).
- **Replay quota.** The free plan includes 10,000 replay sessions a month, organisation-wide
  (usage is on Settings → Session Replay & Heatmaps). Lower the sample rate there (no deploy) if
  it runs short.

## Adding an event

1. Add it to `EVENTS` in `events.ts` with properties of kind enum, `id`, `count`, `duration`
   or `flag`. There is no free-text kind, on purpose.
2. Call `track('Name', { … })` at the moment it happens. It is a no-op without consent, so no
   guard is needed.
3. In tests, mock the module: `vi.mock('…/analytics/analytics', () => ({ track: vi.fn() }))`.

## Setup (done 2026-10-19)

- The organisation is on **app.eu.amplitude.com** (signed in with Google as simon.rowe@gmail.com),
  with one project, `Default project`. The region is fixed at signup.
- Its browser key is the `VITE_AMPLITUDE_API_KEY` repository secret; Publish bakes it into the
  frontend image, so nothing is set on the Pi.
- Session Replay capture is on for the project at 100%, masking Medium.
- Still to do: build the funnels (the starting set is in `specs/055-amplitude-analytics/plan.md`).

Google Analytics 4 (`react-ga4`, `VITE_GA_MEASUREMENT_ID`) was removed in the same change. It ran
without consent.
