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
| 4 | Weekly digest body — per-article summaries and synthesis | `gpt-5.6-luna` | `aggregation.digest.model` | Embabel `Ai.withLlm` |
| 5 | Weekly digest metadata — post title and short description | `gpt-5.6-luna` | `aggregation.digest.model` | Embabel `Ai.withLlm` |
| 6 | Embeddings for RAG retrieval (chat context) and site search | `text-embedding-3-small` | `application.yml:97` | Spring AI `openai` |
| 7 | Featured image generation — digests, and aggregated articles with no `og:image` | `gpt-image-1` | `application.yml:100` **and** hardcoded, `media/BlogImageGenerationService.java:25` | Spring AI `openai` |
| 8 | Langfuse LLM-as-a-judge evaluators scoring chat traces | `gpt-4o-mini` | `JUDGE_MODEL` env, default at `scripts/bootstrap-langfuse-evaluators.sh:108` | Langfuse, outside the app |
| 9 | Automated PR code review (`software-factory` Temporal worker) | Claude `sonnet` | `CLAUDE_MODEL` env, `docker-compose.prod.yml:168` | Claude Code binary |

Rows 4 and 5 describe the state after the favourites-digest change
(`docs/superpowers/specs/2026-08-08-favourites-digest-design.md`); both call
sites are on `gpt-4o-mini` until it ships.

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

**Embabel carries its own model registry, and it lags.**
`embabel-agent-openai-autoconfigure:0.3.5` bundles
`classpath:models/openai-models.yml`, header-dated "Model IDs verified against
GET /v1/models on 2026-03-29", whose newest family is GPT-5.4. Embabel
registers one Spring bean per entry and `Ai.withLlm(...)` resolves against
those beans, so rows 3-5 can only name a model that registry knows unless the
model is registered explicitly in `agents/AgentConfig.java`. A model id being
valid at OpenAI is not sufficient.

**The image model is set in two places.** `application.yml:100` and the
`IMAGE_MODEL` constant at `media/BlogImageGenerationService.java:25` both say
`gpt-image-1`; the constant is what the call actually uses. Changing only the
yaml will appear to work and change nothing.

**Row 2 is the only guardrail.** If its classification call fails, the advisor
returns null and the request proceeds to the main chat ungated — a model
change here is a security-relevant change, not just a cost one.

## When adding or changing a model

1. Confirm the id exists on the account: `GET /v1/models`.
2. If it is an Embabel call site (rows 3-5), confirm the model is registered —
   bundled registry or `AgentConfig` — before assuming the name resolves.
3. Check parameter support. The whole GPT-5.4 family sets
   `supports_temperature: false` in Embabel's registry; newer families may
   differ, and unsupported parameters surface as 400s or, worse, as silently
   dropped behaviour.
4. Update this table.
