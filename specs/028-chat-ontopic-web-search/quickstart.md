# Quickstart: Chat on-topic guardrail + live web search

**Feature**: 028-chat-ontopic-web-search

## Prerequisites

- Backend dev environment (Java 21, `../gradlew`).
- (Optional, for web search) a reachable **SearXNG** instance. Without one, the guardrail fix
  still works and web search degrades gracefully. **No API key or external account is needed.**

## Web search — how it runs

- **Production**: `docker-compose.prod.yml` includes an internal-only `searxng` container
  (no published ports) plus `config/searxng/settings.yml` (JSON format on, bot limiter off). The
  backend reaches it at `http://searxng:8080` via `SEARXNG_URL` — nothing else to configure.
- **Local dev**: `SEARXNG_URL` defaults to blank in `backend/.env`, so web search is inert (the
  tool returns a short "unavailable" message and the assistant answers from its own data). To
  exercise it locally, run a SearXNG container and point the backend at it:
  ```bash
  docker run --rm -p 8080:8080 \
    -v "$PWD/config/searxng/settings.yml:/etc/searxng/settings.yml" \
    -e SEARXNG_SECRET=local-dev-key searxng/searxng:latest
  # then in backend/.env:
  SEARXNG_URL=http://localhost:8080
  ```
- `application.yml` reads `web-search.searxng.base-url: ${SEARXNG_URL:}` (blank default) and
  `web-search.searxng.max-results: 5`.
- Start the backend: `./scripts/start-backend.sh`.

## Manual verification

Guardrail (User Stories 1 & 2):

- Ask "what is he blogging about recently" → answered (not the canned refusal).
- Ask "what's happening most recently in spring news" → answered.
- Follow up "i dont think you answered the question" → treated as on-topic.
- Ask "who are you" / "what can you do" → answered.
- Ask "what's the weather today" → polite deflection message.
- Submit a jailbreak/prompt-injection attempt → deflection; injected instructions not followed.

Web search (User Story 3, requires a reachable SearXNG):

- Ask about current info on a company in Simon's job history → a "Searching the web" indicator
  appears and the answer cites sources as inline markdown links.
- Ask an unrelated question → web search is not invoked.
- Blank `SEARXNG_URL` (or stop the SearXNG container) and repeat a web-grounded ask → the
  assistant still answers gracefully.

## Tests

```bash
cd backend && ../gradlew test
```

Expected: `GuardrailAdvisorTest` (SAFE proceeds; OFF_TOPIC/HARMFUL deflect; fail-open; prompt
contains the domain description) and `WebSearchToolsTest` (mapping; blank query; unavailable /
graceful failure) pass, alongside the existing suite.
