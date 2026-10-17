# Model usage audit

Last verified: 2026-10-17

Every place this application calls a model, what it is used for, and where the
model name is set. Model ids were checked against `GET /v1/models` on the
production OpenAI account, and prices against developers.openai.com/api/docs/pricing,
on 2026-10-17.

## Inventory

| # | Purpose | Model | Set in | Provider path |
| --- | --- | --- | --- | --- |
| 1 | Portfolio chatbot (tool-enabled, streaming) | `gpt-6-luna` | `spring.ai.openai.chat.model`, env `OPENAI_CHAT_MODEL`; `reasoning_effort: none` from `ChatConfig.chatOptions()` | Spring AI `openai`, client default options |
| 2 | Chat topic guardrail — classifies whether an incoming question is on-topic before it reaches the main chat | `gpt-4o-mini` | hardcoded, `chat/GuardrailAdvisor.java` | Spring AI `openai`, per-call override |
| 3 | Content aggregation classifier — article vs event, 2-3 sentence summary, event date/venue/location, published date | `gpt-6-luna` | `ContentAggregationAgent.CLASSIFIER_MODEL` | Embabel `Ai.withLlm`, explicitly registered |
| 4 | Weekly digest body and metadata | `gpt-6-luna` | `aggregation.digest.model`, env `DIGEST_MODEL` | Embabel `Ai.withLlm`, explicitly registered |
| 5 | Article summaries (on demand and on favourite) | `gpt-6-luna` | `aggregation.summary.model`, env `ARTICLE_SUMMARY_MODEL` | Embabel `Ai.withLlm`, explicitly registered |
| 6 | `/status` release notes | `gpt-6-luna` | `platform.releases.summaries.model`, env `PLATFORM_RELEASE_SUMMARY_MODEL` | Embabel `Ai.withLlm`, explicitly registered |
| 7 | Term Time chat (tool-driven) | `gpt-6-luna` | `school.chat-model`, env `SCHOOL_CHAT_MODEL`; `reasoning_effort: none` per call | Spring AI `openai`, per-call options |
| 8 | Term Time guardrail, tier classifier and event extraction | `gpt-5-nano` | `school.guardrail-model`, env `SCHOOL_GUARDRAIL_MODEL` | Spring AI / Embabel |
| 9 | Term Time photographed-note transcription (vision) | `gpt-6-luna` | `school.vision-model`, env `SCHOOL_VISION_MODEL` | Embabel `Ai.withLlm` |
| 10 | CoParent Quick add and statement suggestions | `gpt-6-luna` | `coparent.assistant.model` / `coparent.statements.model`; `reasoning_effort: none` per call | Spring AI `openai`, per-call options |
| 11 | Retrospective digest metadata fix — Mongock change unit `V006FixAiBlogTitles` | `gpt-4o-mini` | hardcoded, already applied | Embabel `Ai.withLlm`, pinned model overload |
| 12 | Embeddings for RAG retrieval (chat context) and site search | `text-embedding-3-small` | `spring.ai.openai.embedding.model` | Spring AI `openai` |
| 13 | Featured image generation — digests, and aggregated articles with no `og:image` | `gpt-image-2` | hardcoded, `media/BlogImageGenerationService.IMAGE_MODEL` (the yaml value only documents it) | Spring AI `openai` |
| 14 | Langfuse LLM-as-a-judge evaluators scoring chat traces | `gpt-4o-mini` | `JUDGE_MODEL` env, default in `scripts/bootstrap-langfuse-evaluators.sh` | Langfuse, outside the app |
| 15 | Software Factory: code review, feedback, deploy triage | Claude `sonnet` / `haiku` | `CLAUDE_MODEL`, `FACTORY_*_MODEL` env | Claude Code binary |

### The GPT-6 move (2026-10-17)

`gpt-6-luna` costs $0.10/$0.50 per 1M tokens against `gpt-5.6-luna`'s $0.20/$1.20
and `gpt-5.4-nano`'s $0.20/$1.25. Measured on a real 8,000-character article summary:
11.9s and $0.00075 against gpt-5.6-luna's 11.0s and $0.00161. It accepts the same
request shapes as gpt-5.6-luna (verified live): no `temperature` other than 1,
`max_completion_tokens` not `max_tokens`, `response_format: json_schema`, images in, and
function tools **only with `reasoning_effort: none`**. Unlike gpt-5.6-luna it reasons by
default (about 280 reasoning tokens on that summary), which is why the portfolio chat,
the one caller that sent tools with no effort at all, needed `ChatConfig.chatOptions()`.

Deliberately not moved:

- **Row 2, the chat guardrail.** It pins `temperature(0.0)`, which gpt-6-luna rejects, and
  it is the only gate in front of the chat: a 400 there fails open. Moving it needs the
  temperature dropped and the classifier re-checked against the evals, as its own change.
- **Row 8, `gpt-5-nano`.** Its $0.05 input price is half gpt-6-luna's, and it is a pure
  classifier.
- **Row 12.** OpenAI has no newer embedding model, and changing it re-embeds everything.

**A model named in the environment beats every default here.** The shared env file pinned
`ARTICLE_SUMMARY_MODEL=gpt-5.6-luna` on 2026-10-17. Wherever a `.env` sets one of the
variables above, the default does nothing until that line is changed or removed.

Web search (`websearch/SearxngClient.java`) hits a self-hosted SearxNG
instance and involves no model. Web fetch (`webfetch/UrlFetcher.java`) and the
content scrapers (`agents/scrapers/`) are plain HTTP plus Jsoup.

## Things worth knowing

**One Spring AI OpenAI module since the Boot 4 upgrade.** Spring AI 2.0 folded the old
`openai-sdk` module into `spring-ai-openai`, which drives chat, embeddings and images
through the official openai-java SDK. The two-prefix arrangement this section used to
describe is gone (see the comment above `spring.ai.openai` in `application.yml`).

**Do not add `reasoning-effort` to the shared chat defaults.** Spring AI merges
those defaults into every per-call `OpenAiChatOptions`, and `gpt-4o-mini` (row 2) rejects
the argument outright, which silently disables the topic guardrail rather than erroring.
The callers that need `reasoning_effort: none` (rows 1, 7 and 10: tools on a luna model)
set it on their own client or call. `ChatRequestOptionsWireTest` reads the portfolio
chat's and the guardrail's request bodies off the wire to keep the two apart.

**Embabel carries its own model registry, and it lags — in every release.**
(Embabel 1.5.1's registry reaches GPT-5.6 but has no GPT-6 model, so `gpt-6-luna` is
registered in `AgentConfig.gpt6LunaLlm`.)
`embabel-agent-openai-autoconfigure` bundles
`classpath:models/openai-models.yml`, header-dated "Model IDs verified against
GET /v1/models on 2026-03-29", whose newest family is GPT-5.4. Embabel
registers one Spring bean per entry and `Ai.withLlm(...)` resolves against
those beans, so rows 3-6 can only name a model that registry knows unless the
model is registered explicitly in `agents/AgentConfig.java`. A model id being
valid at OpenAI is not sufficient. Checked against the current release (1.0.0)
on 2026-08-08: same header date, still stops at GPT-5.4, so upgrading Embabel
is not a route to newer models.

**`gpt-5.6` is an alias for Sol, not Luna.** Naming the family without the
suffix silently selects a different, more expensive model. Always use the full
id.

**Most GPT-5.x models reject `temperature`.** Only the default value of 1 is
accepted; anything else is a 400, and gpt-6-luna is no exception. Row 2 sets `.temperature(0.0)`
explicitly, which is fine for `gpt-4o-mini` but would break that call the
moment the guardrail moves to a 5.x model.

**The image model is set in two places.** `spring.ai.openai.image.model` and the
`IMAGE_MODEL` constant in `media/BlogImageGenerationService.java` both say
`gpt-image-2`; the constant is what the call actually uses. Changing only the
yaml will appear to work and change nothing.

**Row 2 is the only guardrail.** If its classification call fails, the advisor
returns null and the request proceeds to the main chat ungated — a model
change here is a security-relevant change, not just a cost one.

## Framework versions

Checked 2026-08-08.

| Component | Here | Latest | Notes |
| --- | --- | --- | --- |
| Spring Boot | 3.5.16 | 4.x | — |
| Spring AI | 1.1.8 | 2.0.0 | 1.1.8 is the **last release of the Boot 3 line**; 2.0.0 requires Boot 4.0 and cannot load in a 3.x context |
| Embabel Agent | 1.0.0 | 1.0.0 | We run 1.0.0, which targets Boot 3.5.14 / Spring AI 1.1.7 — compatible with what we run. Embabel 2.0.0 exists only as a branch (Boot 4.0.6, Spring AI 2.0.0-M8) and is not published to Maven Central |

**Spring AI 2.0 is a Boot 4 migration, not a version bump**, and it lands
squarely on things we use:

- `spring-ai-openai-sdk` — **removed**. That is the module the chatbot (row 1)
  and guardrail (row 2) run on; both would move to `spring-ai-openai`.
- `spring-ai-advisors-vector-store` — renamed to `spring-ai-vector-store-advisor`.
- Jackson 2 → 3, including the `com.fasterxml.jackson.*` → `tools.jackson.*`
  package rebrand and an immutable `ObjectMapper`.
- MCP Java SDK 1.1.x → 2.0.0, with annotation package renames and transport
  artifact relocations — we run an MCP server.
- The hand-rolled provider facades (`OpenAiApi`, `AnthropicApi`) are gone in
  favour of the vendor SDKs directly.

**Embabel 0.3.5 → 1.0.0 already happened, on this branch.** It was a
same-generation upgrade needing no Boot change, but crossed a 0.x → 1.0
boundary: deprecated methods removed, model registration moved toward
declarative YAML, the tool loop moved off Spring AI's `ToolCallback` onto
Embabel's own `Tool` interface, and `PromptRunner` sub-runners consolidated.
In the end none of that touched any `Ai.withLlm(...)` call site (rows 3-6) —
the only changes needed were to build files plus the new `gpt56LunaLlm` bean
in `agents/AgentConfig.java`. It did **not** deliver newer models — see the
registry note above.

## When adding or changing a model

1. Confirm the id exists on the account: `GET /v1/models`.
2. If it is an Embabel call site (rows 3-6), confirm the model is registered —
   bundled registry or `AgentConfig` — before assuming the name resolves.
3. Check parameter support. The whole GPT-5.4 family sets
   `supports_temperature: false` in Embabel's registry; newer families may
   differ, and unsupported parameters surface as 400s or, worse, as silently
   dropped behaviour.
4. Update this table.
