# Quickstart: Chat Drawer Fix-Up

## Run the stack locally
```bash
./scripts/start.sh            # backend :8080, frontend :5173 (sources .env)
# or backend/frontend separately:
./scripts/start-backend.sh
./scripts/start-frontend.sh
```

## Tests
```bash
cd frontend && npm test                    # Vitest unit/component
cd backend && ../gradlew test              # JUnit + Mockito + Testcontainers

# Playwright e2e (new) — needs a running local full stack
cd frontend && npx playwright install       # one-time browser download
cd frontend && npm run e2e                   # runs frontend/e2e/chat.local.spec.ts
cd frontend && npm run e2e:prod-smoke        # read-only prod smoke (chat.prod-smoke.spec.ts)
```

## Manual reproduction (the original bug)
1. Open the "Ask me anything" drawer.
2. Ask: "what software development skills does he have on the front end and the back end?"
3. Verify:
   - Exactly **one** assistant bubble (no duplicate answer).
   - Text is clean and correctly ordered (no `Jasper Reports F Apache,OP` scramble).
   - Tool line reads "✓ Looking up Simon's skills" (not "Used 1 tool").
   - Skills widget renders.
   - Any in-answer link navigates in-site; a fabricated URL is plain text; images render.

## Key files by slice
- **Tool label**: `frontend/src/components/chat/ToolActivityBlock.tsx`
- **One answer**: `frontend/src/components/chat/chatStreamReducer.ts`,
  `ChatPanel.tsx`, backend `chat/ChatConfig.java` (remove band-aid last)
- **Safe rendering**: `frontend/src/components/chat/ChatMessage.tsx`,
  new `frontend/src/components/chat/linkPolicy.ts`
- **Deep links**: backend `chat/SkillsWidgetPayload.java`, `chat/EmploymentWidgetPayload.java`,
  `mcp/ProfileMcpTools.java`; frontend `chatTypes.ts`, `pages/ExperiencePage.tsx`,
  `pages/NewsEventsPage.tsx`, new `useScrollToHash`
- **E2e**: `frontend/e2e/`, `frontend/playwright.config.ts`, `frontend/package.json` scripts
- **Observability**: `docker-compose.prod.yml` (langfuse `LANGFUSE_INIT_*`),
  `backend/src/main/resources/application.yml` (content capture — verify props for Spring AI
  1.1.4), new `scripts/verify-langfuse-trace.sh`

## Langfuse (owner-executed runbook, prod)
1. Set `LANGFUSE_INIT_*` in deploy `.env`; ensure `LANGFUSE_PUBLIC_KEY`/`LANGFUSE_SECRET_KEY`
   match the init project keys.
2. Restart only `langfuse` + `alloy` (NOT nginx unless all four upstreams are up).
3. Log in as `admin@simonrowe.dev` → project visible.
4. Send a chat message → run `scripts/verify-langfuse-trace.sh` → trace visible within ~1 min.

## Definition of done
Maps to Success Criteria SC-001…SC-007 in `spec.md`.
