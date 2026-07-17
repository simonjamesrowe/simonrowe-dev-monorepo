/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/mcp': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // The SPA route /mcp and the backend MCP endpoint /mcp share a path.
        // Browser page navigation is a GET for HTML — let it fall through to the
        // SPA. The in-browser MCP client only ever POSTs, so proxy non-GET to the
        // backend. (An SSE GET stream would carry Accept: text/event-stream, but
        // the client doesn't use it.)
        bypass(req) {
          if (req.method === 'GET') return '/index.html'
        },
      },
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})
