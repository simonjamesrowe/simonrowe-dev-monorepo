import { loadEnv } from 'vite'
import { configDefaults, defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

import { coparentWorkbox } from './coparent-pwa.config'

export default defineConfig(({ mode }) => {
  // Conductor runs several workspaces at once and they all want the dev server port. Set
  // VITE_DEV_PORT in frontend/.env to move this workspace out of the way. Read through Vite's
  // own loadEnv rather than process.env: the config runs in Node so process exists, but this
  // project has no @types/node, and loadEnv also picks the value up from .env files.
  //
  // strictPort stays true. Falling back to a random free port would be worse than failing —
  // the backend's CORS allowlist and any registered OAuth callback name a specific port, so a
  // silent move produces confusing failures later rather than an obvious one now.
  const env = loadEnv(mode, '.', '')
  const devPort = Number(env.VITE_DEV_PORT) || 5173

  return {
  plugins: [
    react(),
    {
      name: 'coparent-dev-host',
      configureServer(server) {
        server.middlewares.use((request, _response, next) => {
          const host = request.headers.host?.split(':')[0]
          const pathname = request.url?.split('?')[0] ?? ''
          const isNavigationPath = pathname === '/' || !pathname.split('/').pop()?.includes('.')
          const acceptsHtml = request.headers.accept?.includes('text/html') ?? false
          if (host === 'coparents.localhost' && request.method === 'GET' && acceptsHtml
              && isNavigationPath
              && !pathname.startsWith('/api/')) {
            request.url = '/coparent/index.html'
          }
          next()
        })
      },
    },
    VitePWA({
      injectRegister: null,
      manifest: false,
      filename: 'coparent-sw.js',
      // Private family API responses are intentionally absent: only immutable build assets
      // and the CoParent navigation shell can be used offline.
      workbox: coparentWorkbox,
      devOptions: { enabled: false },
    }),
  ],
  build: {
    rollupOptions: {
      // Three entry points, one project. Term Time and CoParent are separate apps with their
      // own routers, design
      // language and audience, but it shares this package.json, ESLint config, Vitest config,
      // CI job and Docker build stage. A second npm project would duplicate all of those and
      // add a second `npm ci` to every image build.
      //
      // Both bundles emit into the same dist/assets/, which is why nginx.conf needs no second
      // caching rule — only a `location /school/` with its own try_files.
      // Paths are relative to Vite's root. Deliberately not resolve(__dirname, ...): that
      // needs @types/node, which this project does not depend on, and adding a dependency to
      // spell a two-element list is not a trade worth making.
      input: {
        main: 'index.html',
        school: 'school/index.html',
        coparent: 'coparent/index.html',
      },
    },
  },
  server: {
    port: devPort,
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
      // Share links are served by the backend, not the SPA — mirrors the `location /s/`
      // block in nginx.conf. Without this, /s/ 404s in local development.
      //
      // The trailing slash is load-bearing. Vite matches a string proxy key with
      // `startsWith`, so a bare '/s' also captures '/src/**' — every module the dev
      // server serves — and the whole SPA 404s with only a blank page to show for it.
      '/s/': {
        target: 'http://localhost:8080',
        changeOrigin: true,
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
  }
})
