# 055 Amplitude analytics, session replay and consent — plan

Status: approved 2026-10-19 and built. One pull request. See "Changes made while building" at the end.

## Goal

See how people use all four apps (main site, Term Time, CoParent, Meals): play sessions back,
find where journeys drop off, and count the things that matter. Do it on Amplitude's free Starter
plan, in its EU data centre, and only after a visitor has opted in.

## What exists today

- **Google Analytics 4 is live** (`G-SXNTT3TVN2`, via `react-ga4` in
  `frontend/src/services/analytics.ts`). Only the main site loads it, and it starts on page load
  **with no consent**. Eleven pages call `trackPageView` by hand. Term Time, CoParent and Meals
  carry no analytics at all.
- No consent banner and no CSP header anywhere, so a CSP needs no changes.
- Four Vite entries share one `package.json`, one `styles.css` and one image. Each has its own
  `main.tsx`, and the frontend container serves all four hostnames.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Vendor | Amplitude Starter (free), **EU data centre** | Analytics and replay in one product, and the same tool as work. |
| SDK | `@amplitude/unified` (`initAll`): analytics, autocapture and the session replay plugin | One init call, and `serverZone: 'EU'` covers every part. |
| Projects | **One** Amplitude project for all four apps, with an `app` property on every event | Cross-app journeys (main site to Term Time) stay visible. Amplitude's device cookie sits on `.simonrowe.dev`, so a visitor is one device across subdomains. |
| Consent | `vanilla-cookieconsent` v3 (MIT, self-hosted, no third-party service, TypeScript) | Packaged and themeable, with categories and auto-clearing of cookies on withdrawal. SaaS banners (CookieYes, Cookiebot) add another third party and their own tracking. |
| Consent scope | One `analytics` category. Its cookie is set on `.simonrowe.dev` | One choice covers all four apps. "Necessary" (Auth0 tokens, theme) never needs consent. |
| Loading | The SDK is **dynamically imported only after consent** | Nothing is sent and no bytes are downloaded before consent, and the main bundle stays light. |
| GA4 | **Remove** `react-ga4` and the GA secret and build argument | It runs without consent today. Amplitude autocapture replaces its page views. |
| Ad blockers | Proxy Amplitude through first-party paths in the frontend container's `nginx.conf` | Ad blockers block `*.amplitude.com`, and this audience runs them. Putting the proxy in the image (not `config/nginx/`) means it deploys with the image and avoids the bind-mount inode trap. |
| Owner traffic | **Tagged, not excluded**: a user property `is_owner=true` when the admin role is present | Meals is owner-only, so excluding the owner would leave it empty. Charts filter it out instead. |
| Identity | Signed-in users get `setUserId(<Auth0 sub>)`; Term Time stays anonymous | Lets one person be followed across sessions and devices. The Auth0 sub is pseudonymous: no email or name is sent. |

## Privacy rules (the part a review must check)

1. **Event properties never carry free text or money.** No question text, recipe names, amounts,
   child names or email addresses: only ids, enums, counts and durations. The event names and
   properties are a typed union (`analyticsEvents.ts`), so an ad-hoc string cannot be added.
2. **Replay masking per app**:

   | App | Mask level | Extra |
   |---|---|---|
   | Main site | `medium` (all inputs masked) | Chat user bubbles, the search field, the contact form and snippet notes are masked by selector. **No replay on `/admin/*`.** |
   | Term Time | `medium` | The parent's own chat bubbles are masked by selector. Answers are public school content. |
   | CoParent | **`conservative` (all text masked)** | Receipt and avatar images are blocked. Analytics events still flow; replay shows layout and clicks only. |
   | Meals | `medium` | Owner-only and low-sensitivity. |

   One shared `data-amp-mask` attribute is used in place of per-component CSS selectors, so masking
   is a visible decision in the markup.
3. **Withdrawing consent** calls `setOptOut(true)` and clears the `AMP_*` cookies and local
   storage. Tests pin it.
4. **The consent banner itself**: Accept all and Reject all are equally prominent, with
   "Manage" for the category. A "Cookie settings" link in each app's footer or menu re-opens it.

## Architecture

```
frontend/src/analytics/
  consent.ts            vanilla-cookieconsent config: category, .simonrowe.dev cookie, autoClear, site theme
  amplitude.ts          lazy initAll(apiKey, { serverZone: 'EU', serverUrl, autocapture, sessionReplay })
  analytics.ts          public API: initAnalytics(app, replayPolicy), track(event), identify(sub, isOwner), reset()
  analyticsEvents.ts    typed event taxonomy (union of { name, props })
  replayPolicy.ts       per-app mask level, blocked routes and selectors
  CookieSettingsLink.tsx
```

- `analytics.ts` is the only module the apps import. Before consent and without an API key it is
  a no-op that queues nothing. Tests mock this one module, as `services/analytics` is mocked today.
- Each `main.tsx` calls `initAnalytics('main' | 'term-time' | 'coparent' | 'meals', policy)`.
- An `AnalyticsIdentity` component inside each `Auth0Provider` calls `identify` or `reset` as the
  auth state changes.
- `frontend/src/services/analytics.ts` and the eleven `trackPageView` calls are deleted, because
  autocapture records page views, including SPA route changes.

### Proxy (`frontend/nginx.conf`, every server block it serves)

```
location /amp/2/httpapi      -> https://api.eu.amplitude.com/2/httpapi
location /amp/sr/            -> https://api-sr.eu.amplitude.com/sessions/v2/track
location /amp/sr-cfg/        -> https://sr-client-cfg.eu.amplitude.com/config
(+ any further host the SDK is seen calling: remote config for autocapture)
```

- Every Amplitude host the SDK contacts is listed by reading the browser's network tab against a
  real init, not from the docs alone, and each one is proxied. Anything left unproxied gets blocked.
- The proxy sets a `resolver` and passes `proxy_pass` through a variable (same pattern as
  `nginx-proxy.conf`), with `proxy_ssl_server_name on`. It forwards the body untouched and does
  not buffer it, as Amplitude's proxy guide requires.
- **Known risk: geolocation.** Through a proxy, Amplitude may see the Pi's IP for every visitor
  (Cloudflare → pinggy → nginx). We pass the client IP (`CF-Connecting-IP` →
  `X-Forwarded-For`) and check in Amplitude whether location data is right. If it isn't, we accept
  city-level data as lost and record that in the runbook.

## Event taxonomy (first cut)

All events carry `app`. Names follow Amplitude's "Object Action" Title Case convention.

**Main site**
- Chat: `Chat Opened` (`source`: hero, pill, header, tour), `Chat Question Sent`,
  `Chat Answer Received` (`toolCount`, `latencyMs`, `guardrailBlocked`)
- Search: `Search Performed` (`resultCount`, `surface`: header or news)
- Blog and news: `Blog Viewed`, `Share Clicked` (`contentType`), `Summary Requested`,
  `Listen Pressed` (`contentType`)
- Playlist: `Playlist Played`, `Playlist Track Completed`
- Tour: `Tour Started`, `Tour Step Viewed` (`step`), `Tour Completed`, `Tour Dismissed`
- Other: `Contact Form Submitted`, `Portfolio Project Viewed` (`slug`), `Favourite Toggled`

**Term Time**: `Question Asked`, `Answer Received` (`toolCount`, `latencyMs`),
`Calendar Event Clicked`, `Year Group Selected` (`yearGroups`), `Connection Lost`

**CoParent**: `Expense Created` (`source`: manual, quick add, statement), `Expense Agreed`,
`Expense Disputed`, `Settlement Recorded`, `Statement Uploaded` (`bank`), `Quick Add Used`,
`Invite Sent`, `Message Sent`

**Meals**: `Recipe Viewed`, `Recipe Cooked`, `Recipe Imported` (`via`: link, photo, paste),
`Quick Add Used`, `Shopping List Viewed`, `Up Next Viewed`

Autocapture also records page views, sessions, clicks, form starts and form submits, file
downloads, and rage and dead clicks, so the named events cover only the moments a selector cannot
see (for example, "answer received").

## Build and deploy plumbing

- `VITE_AMPLITUDE_API_KEY` goes in `vite-env.d.ts`, `.env.example`, `Dockerfile.frontend`
  (`ARG`/`ENV`), the `publish.yml` build arguments and a GitHub secret. Amplitude browser keys are
  public by design. Locally the key is unset, so nothing is sent unless you opt in.
- `VITE_GA_MEASUREMENT_ID` is removed from all of the same places, and the GitHub secret is
  deleted once the PR merges.

## Tests

- Unit tests in `analytics/`:
  - nothing loads or sends before consent
  - accepting loads it once (also under StrictMode double mount)
  - withdrawing opts out and clears storage
  - with no key, nothing happens
  - each app's replay policy has the right mask level and blocked routes
  - `/admin` never records
  - the owner is tagged, not dropped
- A taxonomy test: event properties are typed with no `string` free-text fields, and a few
  forbidden names (`question`, `email`, `amount`, `name`) fail compilation or the test.
- Page tests mock `analytics/analytics` (replacing today's `services/analytics` mocks).
- Playwright e2e on the main site:
  - no request to `/amp/` before consent
  - one after Accept
  - none after Reject, and after a reload
- Run the image locally. Check through the proxy that events reach the Amplitude EU project
  (Live events view) and that a replay plays back with inputs masked.

## Docs

- `docs/runbooks/analytics.md` covers:
  - what is captured and what is masked
  - how to add an event
  - the proxy and the hosts it covers
  - how to change sample rates (Amplitude's remote replay settings, no deploy needed)
  - the geolocation finding
  - the replay quota
- A CLAUDE.md "Recent Changes" entry.

## Manual steps (owner, outside the PR)

1. Sign up for Amplitude **in the EU data centre** (`app.eu.amplitude.com`). It is a separate
   region from US and can't be moved later. Create the project `simonrowe.dev`.
2. Add the browser API key as the `VITE_AMPLITUDE_API_KEY` GitHub secret.
3. In Amplitude, enable Session Replay and set the starting sample rate. Start at 100% and lower it
   if the free replay quota (about 1,000 a month, organisation-wide) runs short.
4. After deploy, build the starter funnels:
   - Landing → Chat Opened → Chat Question Sent → Chat Answer Received
   - News page → Summary Requested → Listen Pressed → Playlist Played
   - Tour Started → Tour Completed
   - Term Time visit → Question Asked → Answer Received
5. Delete the GA4 property and the `VITE_GA_MEASUREMENT_ID` secret.
6. Optional: authorise the Amplitude connector in claude.ai so Claude can read funnels. The
   connector configured today is the **US** one and may not reach an EU-region organisation.

## Size

Roughly 600–900 lines, most of it tests, the taxonomy and call sites. That is over the 400-line
guideline, which is accepted because it was asked for as one PR. Review order: `analytics/` →
`replayPolicy.ts` → `nginx.conf` → the four `main.tsx` → call sites.

## Out of scope

- Server-side events from the backend (summaries generated, narrations made). Possible later via
  Amplitude's HTTP API; LLM traces stay in Langfuse.
- Experiments, feature flags and guides/surveys, even though the unified SDK carries them.

## Changes made while building

- **SDK: `@amplitude/analytics-browser` plus `@amplitude/plugin-session-replay-browser`, not
  `@amplitude/unified`.** `initAll` always attaches the Experiment and Guides & Surveys plugins,
  which call more hosts and are out of scope.
- **The banner is a full-width bar at the bottom** (`bar inline`), on request, not a box.
- **Three proxy routes, not "every host seen".** The SDK's own constants list them: events, replay
  upload, and one config host shared by replay sampling and remote config. Diagnostics, a fourth
  host, are switched off.
- **Analytics remote config is off.** A toggle in Amplitude's UI could otherwise switch on capture
  this code rules out. Replay still fetches its sampling config.
- **Two built-in plugins are reconfigured, because they ran after the URL scrubber** and put
  `?q=` search text back on every event: page URL enrichment is off (a query-free `page_path` is
  added instead), and attribution records campaign data as user properties only. Found by reading
  a real request body. `analyticsWire.test.ts` now drives the real SDK and fails if a query string
  reaches the wire.
- **Withdrawal also deletes Amplitude's IndexedDB stores**, and any page load with analytics
  declined purges leftovers the SDK writes while the withdrawing page unloads.
- **Not done:** the outer proxy's 1 MB body limit on the Term Time, CoParent and Meals hostnames
  is unchanged (see the runbook); replay URLs keep their query string (no hook exists).
- Event names follow what each app can actually see. The final list is `EVENTS` in
  `frontend/src/analytics/events.ts`, for example `Narration Completed` rather than
  `Playlist Track Completed`, and `Summary Opened` alongside `Summary Requested`.
