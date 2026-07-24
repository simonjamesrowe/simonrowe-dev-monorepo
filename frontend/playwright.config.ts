import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright e2e config for the chat drawer.
 *
 * - `local` (primary, deterministic): drives the real chat against a running local full
 *   stack. Bring the stack up first (`./scripts/start.sh` or docker-compose) so the
 *   frontend (5173) and backend (8080) are live. Override the target with E2E_BASE_URL.
 * - `prod-smoke` (read-only): a minimal check against the deployed site. Override the
 *   target with E2E_PROD_URL (defaults to https://simonrowe.dev).
 *
 * Assertions target structure/behaviour (bubble count, ordering, tool label, link/image
 * rules), never exact model wording, so the suite stays deterministic despite a real LLM.
 */
const LOCAL_BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'
const PROD_BASE_URL = process.env.E2E_PROD_URL ?? 'https://simonrowe.dev'

export default defineConfig({
  testDir: './e2e',
  // Generous timeout: a real LLM answer can take many seconds.
  timeout: 90_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['list']] : [['list']],
  projects: [
    {
      name: 'local',
      testMatch: /\.local\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'], baseURL: LOCAL_BASE_URL },
    },
    {
      name: 'prod-smoke',
      testMatch: /chat\.prod-smoke\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], baseURL: PROD_BASE_URL },
    },
  ],
})
