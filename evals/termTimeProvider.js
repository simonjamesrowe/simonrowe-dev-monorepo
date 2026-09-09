/**
 * Custom promptfoo provider for Term Time, the school assistant.
 *
 * Same shape as chatProvider.js — a provider class with the endpoint in `config.url` — because
 * the two evals should be driven the same way even though the transports differ. The portfolio
 * chat is STOMP over WebSocket; Term Time is a plain JSON POST to /api/school/chat.
 *
 * The default endpoint is PRODUCTION over HTTPS. Note that pointing this at
 * `https://localhost:8080` cannot work: the backend serves plain HTTP and TLS terminates at
 * nginx (and Cloudflare) in front of it, which is the same reason chatProvider.js defaults to
 * `ws://` rather than `wss://`. To run against a local backend, override the scheme too:
 *
 *   providers:
 *     - id: file://termTimeProvider.js
 *       config:
 *         url: "http://localhost:8080/api/school/chat"
 *
 * Unauthenticated on purpose. The public tier is the surface anyone can reach, and its refusal
 * behaviour is the thing most worth pinning.
 */

const DEFAULT_URL = 'https://simonrowe.dev/api/school/chat';

class TermTimeProvider {
  constructor(options) {
    this.providerId = (options && options.id) || 'term-time';
    this.config = (options && options.config) || {};
  }

  id() {
    return this.providerId;
  }

  async callApi(prompt, context) {
    const url = this.config.url || DEFAULT_URL;
    const timeoutMs = this.config.timeoutMs || 60000;
    const yearGroup = (context && context.vars && context.vars.yearGroup) || null;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: prompt, yearGroup }),
        signal: controller.signal,
      });

      // 429 is the rate limiter and 503 is the feature being switched off or the daily budget
      // being spent. Both are real answers about the system's behaviour rather than transport
      // failures, so they are returned as output for an assertion to judge rather than thrown.
      if (response.status === 429) {
        return { output: 'RATE_LIMITED' };
      }
      if (!response.ok && response.status !== 503) {
        return { error: `HTTP ${response.status}` };
      }

      const body = await response.json();
      return {
        output: body.answer,
        // Surfaced so an assertion can distinguish a genuine "I don't know" from a withheld
        // answer or a budget stop — three outcomes that read identically as prose.
        metadata: { outcome: body.outcome },
      };
    } catch (e) {
      return { error: e.name === 'AbortError' ? `Timed out after ${timeoutMs}ms` : String(e) };
    } finally {
      clearTimeout(timer);
    }
  }
}

module.exports = TermTimeProvider;
