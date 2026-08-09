# Model usage audit

Last verified: 2026-08-08

Every place this application calls a model, what it is used for, and where the
model name is set. Model ids were checked against `GET /v1/models` on the
production OpenAI account on 2026-08-08.

## Inventory

| # | Purpose | Model | Set in | Provider path |
| --- | --- | --- | --- | --- |
| 1 | Portfolio chatbot (tool-enabled, streaming) | `gpt-5.4-nano` | `application.yml:114`, env `OPENAI_CHAT_MODEL` | Spring AI `openai-sdk` |
| 2 | Chat topic guardrail — classifies whether an incoming question is on-topic before it reaches the main chat | `gpt-4o-mini` | hardcoded, `chat/GuardrailAdvisor.java:101` | Spring AI `openai-sdk`, per-call override |
| 3 | Content aggregation classifier — article vs event, 2-3 sentence summary, event date/venue/location, published date | `gpt-4o-mini` | hardcoded, `agents/ContentAggregationAgent.java:337` | Embabel `Ai.withLlm` |
| 4 | Weekly digest body — per-article summaries and synthesis | `gpt-5.6-luna` | `aggregation.digest.model` | Embabel `Ai.withLlm`, explicitly registered |
| 5 | Weekly digest metadata — post title and short description | `gpt-5.6-luna` | `aggregation.digest.model` | Embabel `Ai.withLlm`, explicitly registered |
| 6 | Retrospective digest metadata fix — Mongock change unit `V006FixAiBlogTitles`, regenerating titles/images for already-published generic-sounding digests | `gpt-4o-mini` | hardcoded, `migration/changeunits/V006FixAiBlogTitles.java:46` | Embabel `Ai.withLlm`, pinned model overload |
| 7 | Embeddings for RAG retrieval (chat context) and site search | `text-embedding-3-small` | `application.yml:97` | Spring AI `openai` |
| 8 | Featured image generation — digests, and aggregated articles with no `og:image` | `gpt-image-1` | `application.yml:100` **and** hardcoded, `media/BlogImageGenerationService.java:25` | Spring AI `openai` |
| 9 | Langfuse LLM-as-a-judge evaluators scoring chat traces | `gpt-4o-mini` | `JUDGE_MODEL` env, default at `scripts/bootstrap-langfuse-evaluators.sh:108` | Langfuse, outside the app |
| 10 | Automated PR code review (`software-factory` Temporal worker) | Claude `sonnet` | `CLAUDE_MODEL` env, `docker-compose.prod.yml:168` | Claude Code binary |

Web search (`websearch/SearxngClient.java`) hits a self-hosted SearxNG
instance and involves no model. Web fetch (`webfetch/UrlFetcher.java`) and the
content scrapers (`agents/scrapers/`) are plain HTTP plus Jsoup.

## Things worth knowing

**Two Spring AI autoconfigurations are in play, deliberately.** Chat runs on
`spring.ai.openai-sdk` while embeddings and images run on `spring.ai.openai`,
with `OpenAiChatAutoConfiguration`, `OpenAiSdkEmbeddingAutoConfiguration` and
`OpenAiSdkImageAutoConfiguration` excluded at `application.yml:89-91` to stop
the two colliding. A model name set under the wrong prefix is silently ignored.

**Do not add `reasoning-effort` to the shared chat defaults.** Spring AI merges
those defaults into every per-call `OpenAiChatOptions`, and it 400s twice over:
OpenAI rejects function tools combined with `reasoning_effort` for
`gpt-5.4-nano` (breaking the tool-enabled chat), and `gpt-4o-mini` rejects the
argument outright, which silently disables the topic guardrail at row 2 rather
than erroring. Full context in `docs/openai-api-setup.md`.

**Embabel carries its own model registry, and it lags — in every release.**
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
accepted; anything else is a 400. Row 2 currently sets `.temperature(0.0)`
explicitly, which is fine for `gpt-4o-mini` but would break that call the
moment the guardrail moves to a 5.x model.

**The image model is set in two places.** `application.yml:100` and the
`IMAGE_MODEL` constant at `media/BlogImageGenerationService.java:25` both say
`gpt-image-1`; the constant is what the call actually uses. Changing only the
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
