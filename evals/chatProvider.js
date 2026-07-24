/**
 * Custom promptfoo provider that drives the chat over its real transport: STOMP over
 * WebSocket. There is no HTTP `/api/chat` endpoint — the browser talks to `/ws/chat`,
 * sends to `/app/chat.send` and reads streamed `ChatResponse` frames from
 * `/topic/chat.<sessionId>`. This provider does the same, accumulates the streamed
 * chunks, and returns the final assistant text as the provider output.
 */
const { Client } = require('@stomp/stompjs');
const WebSocket = require('ws');

class StompChatProvider {
  constructor(options) {
    this.providerId = (options && options.id) || 'stomp-chat';
    this.config = (options && options.config) || {};
  }

  id() {
    return this.providerId;
  }

  async callApi(prompt) {
    const url = this.config.url || 'ws://localhost:8080/ws/chat';
    const timeoutMs = this.config.timeoutMs || 90000;
    // Unique per call so the per-session message cap (10) is never hit across tests.
    const sessionId = `eval-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;

    return new Promise((resolve) => {
      const chunks = [];
      let settled = false;

      const client = new Client({
        webSocketFactory: () => new WebSocket(url),
        reconnectDelay: 0,
        onConnect: () => {
          client.subscribe(`/topic/chat.${sessionId}`, (message) => {
            let frame;
            try {
              frame = JSON.parse(message.body);
            } catch (e) {
              return;
            }
            switch (frame.type) {
              case 'STREAM_CHUNK':
                if (frame.content) chunks.push(frame.content);
                break;
              case 'STREAM_END':
                finish(frame.content || chunks.join(''));
                break;
              case 'ERROR':
                finish(frame.content || 'ERROR');
                break;
              default:
                break;
            }
          });
          client.publish({
            destination: '/app/chat.send',
            body: JSON.stringify({ sessionId, message: prompt }),
          });
        },
        onStompError: (frame) =>
          finish(`STOMP error: ${(frame && frame.headers && frame.headers.message) || 'unknown'}`),
        onWebSocketError: () => finish('WebSocket connection error'),
      });

      const timer = setTimeout(() => finish(chunks.join('')), timeoutMs);

      function finish(output) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        try {
          client.deactivate();
        } catch (e) {
          // ignore teardown errors
        }
        resolve({ output });
      }

      client.activate();
    });
  }
}

module.exports = StompChatProvider;
