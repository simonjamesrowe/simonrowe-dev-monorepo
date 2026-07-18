# Chat: read a pasted job link → fit assessment → cover letter + CV

**Date:** 2026-07-18
**Status:** Approved (design)
**Area:** `backend/` chat assistant (Spring Boot + Spring AI, OpenAI SDK starter)

## Problem

The portfolio chat assistant now handles recruiter/employer questions and can search UK job
platforms for comparable roles (feature 028 + follow-up). The natural next step a recruiter
wants: **paste the job link they're advertising for** and get an honest read on how suitable
Simon is — ideally with a tailored cover letter and a link to his CV.

Today the assistant can *search* the web (SearXNG) but cannot **read a specific pasted URL**.
The 028 design explicitly deferred a page-fetch tool ("A page-fetch (`fetchUrl`) tool for deep
reading — possible fast follow-up"). This is that follow-up.

## Goals

1. When a recruiter pastes a job posting URL, the assistant **reads the spec** and gives an
   honest fit assessment grounded in Simon's real experience.
2. After the assessment, the assistant **offers** a tailored cover letter and Simon's CV, and
   produces them **on request** (not automatically).
3. Stay on topic and safe: the fetcher must not become an SSRF hole or a general web reader.

## Decisions (from brainstorming)

- **Default behaviour (C):** fetch the spec → fit assessment → then *offer* cover letter / CV;
  generate those only on confirmation.
- **Fetch scope (A):** fetch **any public** http/https URL (company career pages, ATS such as
  Greenhouse/Lever/Workable, Reed, Totaljobs, …) **with SSRF guards** — not an allowlist, because
  the pages that actually fetch successfully are largely company/ATS domains an allowlist would
  reject.
- **Cover letter (A):** delivered **inline** in the chat as markdown (first-person as Simon,
  concise, tailored to the fetched role, ending with a CV link). No downloadable file, no
  frontend changes.
- **Tool shape:** a general-but-description-gated `fetchUrl` page reader (not a narrow
  `readJobPosting`), so it can also read a source the visitor references — but its description
  bounds it to Simon-grounded / fit-assessment use, consistent with boundary A.

### Reality check (documented, not a blocker)

Large job platforms (LinkedIn, Indeed, Glassdoor) frequently **block server-side fetching**
(login walls, HTTP 999, bot detection). A pasted LinkedIn link will often not be readable.
Reed, Totaljobs, and company/ATS career pages usually work. The design therefore treats a
failed/blocked fetch as a normal path with a graceful fallback (search for the role, or ask the
recruiter to paste the text) — never an error.

## Existing building blocks (reused, no new plumbing)

- **CV:** `getProfile()` already returns a `cvUrl`; a `ResumeController` serves the rendered PDF
  at `/api/resume` (`simon-rowe-cv.pdf`). The cover letter links to the CV via `cvUrl`, falling
  back to `/api/resume`.
- **HTML fetch/parse:** **jsoup is already on the classpath** — `RssScraper` and
  `SitemapHtmlScraper` use `Jsoup.connect(url).get()` and `Jsoup.parse(html).text()`. The new
  fetcher follows the same proven pattern.
- **Tool UX:** `ChatStreamPublisher.toolStart/toolEnd` labels, `ToolContext` sessionId, and
  `@Tool` registration in `ChatConfig` — identical to `ProfileMcpTools` / `WebSearchTools`.

## Design

### 1. `UrlFetcher` — new bean (`com.simonrowe.webfetch`)

`WebPageContent fetch(String url)` returning `{title, text, url}` (a record), backed by jsoup.

- **SSRF guard (runs before any fetch):**
  - Require `http`/`https` scheme; reject anything else (`file:`, `gopher:`, `ftp:`, …).
  - Resolve the host to `InetAddress` and **reject** if it is loopback, any-local, link-local,
    site-local (private), or multicast — covers `127.0.0.0/8`, `10/8`, `172.16/12`,
    `192.168/16`, `169.254/16` (incl. the `169.254.169.254` cloud-metadata address), `::1`,
    `fc00::/7`. Also reject bare internal names (`localhost`, container names like `searxng`,
    `portainer`, `mongodb`).
  - Re-validate the **final** host after redirects (jsoup redirect handling is bounded; the
    resolved-address check is repeated on the effective URL).
- **Fetch bounds:** custom user-agent, ~8s timeout, capped body size, limited redirects.
- **Extraction:** `title` from `<title>`/`og:title`; `text` from the document body via jsoup
  `.text()`, truncated to a configured max (~8000 chars) to bound prompt tokens.
- **Never throws** to the tool: on block/failure/oversize it signals failure (empty/exception
  caught by the tool), so the assistant degrades gracefully.

### 2. `FetchUrlTools` — new `@Tool` component (`com.simonrowe.chat`)

`Object fetchUrl(url, toolContext)` (returns `WebPageContent` on success, or a short
"couldn't read that page" string on block/failure — mirroring `WebSearchTools`).

- **`@Tool` description (boundary-gated):** *"Read the contents of a specific web page the
  visitor references — most often a job posting they paste — to assess Simon's fit for a role
  or to enrich an answer grounded in his profile/experience. Not a general web reader; do not
  use it for unrelated pages."*
- Guards: blank/invalid URL → short message, no fetch. Publishes a **"Reading the job posting"**
  start/end label via `ChatStreamPublisher` (null-safe sessionId, like the other tools).
  `@WithSpan` for tracing.
- Registered in `ChatConfig`: `.defaultTools(profileMcpTools, webSearchTools, fetchUrlTools)`.

### 3. Guardrail — `GuardrailAdvisor`

Add "a pasted job-posting URL to assess Simon's fit" to the SAFE description so a message that
is essentially just a URL is classified SAFE (still biased to SAFE overall).

### 4. System prompt — `application.yml` (`chat.system-prompt`)

Extend the existing "Recruiters & Employers" block:
- If the visitor pastes a job link (or references a specific posting), call **fetchUrl** to read
  it. If it can't be read, say so and either **searchNews/webSearch** for the role or ask them
  to paste the text.
- Give an **honest fit assessment** against **getJobs** / **getSkills** — strengths and genuine
  gaps, not overselling.
- Then **offer** a tailored cover letter and/or Simon's CV; produce them only on request.
- On request, draft a concise **first-person cover letter** tailored to the fetched role and
  include the **CV link** (`getProfile().cvUrl`, falling back to `/api/resume`). Add
  **fetchUrl** to the tools list with a one-line usage rule.

### 5. Config & env

- Small `web-fetch` block in `application.yml`: `web-fetch.max-chars: 8000`,
  `web-fetch.timeout-seconds: 8`. No API key, no external service, no new container.

### 6. Tests

- **`UrlFetcherTest`:** SSRF guard rejects loopback/private/link-local/metadata hosts and
  non-http schemes; happy-path HTML → title/text extraction and truncation against a static
  HTML string (no network).
- **`FetchUrlToolsTest`:** mock `UrlFetcher` → success mapping, blank/invalid URL handling,
  graceful "couldn't read that page" on failure, tool-label publishing, missing-session skip.
- **`GuardrailAdvisorTest`:** assert the classification prompt now includes the job-posting-URL
  wording.
- `cd backend && ../gradlew test` must pass.

## Trade-offs / risks

- Server-side fetching of user-supplied URLs is inherently sensitive; the SSRF guard (scheme +
  resolved-address checks, re-validated after redirects) is the primary control. Fetches are
  bounded (timeout, body size, redirects) to limit abuse and cost.
- Many big job boards block fetching; the assistant's graceful fallback (search / ask for pasted
  text) keeps the experience useful rather than erroring.
- The cover letter is model-generated; the prompt steers it to stay honest and grounded in the
  real profile/skills, but it remains a draft the recruiter should sanity-check.

## Out of scope

- A general-purpose web reader, recursive crawling, or JS-rendered scraping (headless browser).
- A downloadable cover-letter file or any frontend/widget change.
- Bypassing job-board bot protections (LinkedIn/Indeed/Glassdoor).
