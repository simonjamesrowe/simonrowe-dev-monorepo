import { configDefaults, defineConfig } from 'vitest/config'
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
      '/images': {
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
    // Playwright e2e specs live in e2e/ and must not be collected by Vitest.
    exclude: [...configDefaults.exclude, 'e2e/**'],
    coverage: {
      // v8 rather than istanbul: no instrumentation transform, so adding coverage
      // does not perturb the existing suite.
      provider: 'v8',
      // lcov is what Sonar reads (sonar.javascript.lcov.reportPaths in the root
      // build.gradle.kts); text keeps the number visible in the CI log.
      reporter: ['text', 'lcov'],
      reportsDirectory: 'coverage',
      // A ratchet against regression, not a target. Measured 2026-08-27:
      // 48.66% lines, 81.37% branches, 62.73% functions. Each floor sits a few
      // points under its measurement, the same margin the backend's 0.78 JaCoCo
      // floor leaves against its 82.5% — enough that adding an untested file in
      // an unrelated pull request does not turn the build red, tight enough that
      // deleting a test suite does.
      //
      // `npm run test:coverage` is what CI runs, so this is enforced by the
      // frontend job. Sonar sees the same lcov but its gate is advisory; this is
      // the blocking half. Raise these when the number rises — do not lower them.
      thresholds: {
        lines: 45,
        statements: 45,
        branches: 78,
        functions: 58,
      },
    },
  },
})
