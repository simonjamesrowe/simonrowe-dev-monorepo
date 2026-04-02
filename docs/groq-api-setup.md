# Groq API Setup Guide

This guide covers setting up the Groq API key used by the profile chat feature. The application uses Groq's OpenAI-compatible API to run the Llama 3.3 70B model.

## Steps

### 1. Create a Groq Account

1. Go to [console.groq.com](https://console.groq.com)
2. Sign up with Google, GitHub, or email
3. Verify your email if prompted

### 2. Generate an API Key

1. Navigate to **API Keys** in the left sidebar (or go to [console.groq.com/keys](https://console.groq.com/keys))
2. Click **Create API Key**
3. Give it a name (e.g. `simonrowe-dev`)
4. Copy the key immediately — it won't be shown again

### 3. Set the Environment Variable

Add the key to your backend environment:

```bash
export GROQ_API_KEY=gsk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Or add it to your `backend/.env` file:

```
GROQ_API_KEY=gsk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### 4. Verify

Start the backend and open the site. Use the search bar to ask a question — if the chat responds, the API key is working.

## Configuration

The application connects to Groq via the Spring AI OpenAI starter with these settings in `application.yml`:

```yaml
spring:
  ai:
    openai:
      api-key: ${GROQ_API_KEY:not-set}
      base-url: https://api.groq.com/openai
      chat:
        options:
          model: llama-3.3-70b-versatile
```

No changes are needed — just set the `GROQ_API_KEY` environment variable.

## Free Tier Limits

Groq offers a generous free tier:

- **Requests per minute**: 30
- **Tokens per minute**: 15,000
- **Requests per day**: 14,400

These limits are sufficient for development and low-traffic production use. The application includes rate limiting (Bucket4j) on the chat endpoint to stay within these bounds.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Chat returns errors | Check the backend logs for `401 Unauthorized` — the API key may be invalid or expired |
| `GROQ_API_KEY` not picked up | Ensure the env var is set in the same shell/process running the backend |
| Rate limit errors (429) | Wait a minute and try again, or check your usage at [console.groq.com/usage](https://console.groq.com/usage) |
| Model not available | Groq occasionally rotates model availability — check [console.groq.com/docs/models](https://console.groq.com/docs/models) for current models |
