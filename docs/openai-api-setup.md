# OpenAI API Setup Guide

This guide covers setting up the OpenAI API key used by the profile chat and vector embeddings features. The application uses OpenAI for both chat completions (GPT 5.4 Nano) and text embeddings (text-embedding-3-small) for RAG-powered semantic search.

## Steps

### 1. Create an OpenAI Account

1. Go to [platform.openai.com](https://platform.openai.com)
2. Sign up with Google, Microsoft, Apple, or email
3. Verify your email and phone number if prompted

### 2. Add Billing

1. Navigate to **Settings > Billing** (or go to [platform.openai.com/settings/organization/billing](https://platform.openai.com/settings/organization/billing))
2. Click **Add payment method** and enter your card details
3. Set a monthly usage limit if desired (recommended for cost control)

### 3. Generate an API Key

1. Navigate to **API Keys** in the left sidebar (or go to [platform.openai.com/api-keys](https://platform.openai.com/api-keys))
2. Click **Create new secret key**
3. Give it a name (e.g. `simonrowe-dev`)
4. Copy the key immediately — it won't be shown again

### 4. Set the Environment Variable

Add the key to your `backend/.env` file:

```
OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### 5. Verify

Start the backend and open the site. Use the chat to ask a question — if the chat responds, the API key is working. To verify embeddings, trigger a "Re-embed Content" operation from the admin Data Operations page.

## Configuration

The application connects to OpenAI via the Spring AI OpenAI starter with these settings in `application.yml`:

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:not-set}
      chat:
        options:
          model: gpt-5.4-nano
      embedding:
        options:
          model: text-embedding-3-small
    vectorstore:
      elasticsearch:
        index-name: content-embeddings
        dimensions: 1536
        similarity: cosine
```

No changes are needed — just set the `OPENAI_API_KEY` environment variable.

The chat model is overridable via the `OPENAI_CHAT_MODEL` environment variable (default `gpt-5.4-nano`). It is cheaper per token than the older `gpt-5-mini` ($0.20/$1.25 vs $0.25/$2.00 per 1M).

Do not configure `reasoning-effort` in the shared chat defaults. Spring AI merges these defaults into every per-call `OpenAiChatOptions`, and `reasoning_effort` breaks two calls: OpenAI rejects function tools combined with `reasoning_effort` for `gpt-5.4-nano` on `/v1/chat/completions` (the tool-enabled chat), and `gpt-4o-mini` (the guardrail classifier) rejects the argument entirely, silently disabling the topic gate.

## Models Used

| Purpose | Model | Cost (per 1M tokens) |
|---------|-------|---------------------|
| Chat completions | gpt-5.4-nano | $0.20 input / $1.25 output |
| Text embeddings | text-embedding-3-small | $0.02 input |

## Estimated Costs

For a low-traffic personal site:

- **Chat**: ~$0.01-0.05 per conversation (depending on length)
- **Embeddings**: One-time cost of ~$0.001 to embed all content (hundreds of items). Re-embedding is only needed when content changes or the model is updated.
- **Monthly estimate**: Under $1 for typical usage

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Chat returns errors | Check the backend logs for `401 Unauthorized` — the API key may be invalid or expired |
| `OPENAI_API_KEY` not picked up | Ensure the env var is set in `backend/.env` and the backend was restarted after the change |
| Rate limit errors (429) | Wait a minute and try again, or check your usage at [platform.openai.com/usage](https://platform.openai.com/usage) |
| Embeddings not working | Check that the `content-embeddings` index exists in Elasticsearch: `curl http://localhost:9200/content-embeddings` |
| Vector search returns no results | Trigger "Re-embed Content" from admin Data Operations to populate the vector store |
| Billing error | Ensure a payment method is added — OpenAI requires billing for API access |
