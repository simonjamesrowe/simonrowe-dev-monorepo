import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// These are module-level constants read once at import time, so each case mutates
// import.meta.env and then re-imports the module fresh via vi.resetModules() — a plain
// `import` would keep returning the first module evaluation's cached values.

type MutableEnv = Record<string, string | undefined>

describe('config/version', () => {
  const originalSha = import.meta.env.VITE_GIT_SHA
  const originalBuildTime = import.meta.env.VITE_BUILD_TIME

  beforeEach(() => {
    vi.resetModules()
  })

  afterEach(() => {
    ;(import.meta.env as MutableEnv).VITE_GIT_SHA = originalSha
    ;(import.meta.env as MutableEnv).VITE_BUILD_TIME = originalBuildTime
  })

  it('derives the seven-character short commit from a real SHA', async () => {
    ;(import.meta.env as MutableEnv).VITE_GIT_SHA = '840c311abcdef0123456789abcdef0123456789a'
    ;(import.meta.env as MutableEnv).VITE_BUILD_TIME = '2026-08-26T14:02:11Z'

    const { FRONTEND_COMMIT, FRONTEND_SHORT_COMMIT, FRONTEND_BUILD_TIME } =
      await import('../../src/config/version')

    expect(FRONTEND_COMMIT).toBe('840c311abcdef0123456789abcdef0123456789a')
    expect(FRONTEND_SHORT_COMMIT).toBe('840c311')
    expect(FRONTEND_BUILD_TIME).toBe('2026-08-26T14:02:11Z')
  })

  it('falls back to a dev build when VITE_GIT_SHA is absent', async () => {
    delete (import.meta.env as MutableEnv).VITE_GIT_SHA
    delete (import.meta.env as MutableEnv).VITE_BUILD_TIME

    const { FRONTEND_COMMIT, FRONTEND_SHORT_COMMIT, FRONTEND_BUILD_TIME } =
      await import('../../src/config/version')

    expect(FRONTEND_COMMIT).toBe('unknown')
    expect(FRONTEND_SHORT_COMMIT).toBe('dev')
    expect(FRONTEND_BUILD_TIME).toBeNull()
  })

  it('treats a blank VITE_GIT_SHA the same as absent', async () => {
    ;(import.meta.env as MutableEnv).VITE_GIT_SHA = ''

    const { FRONTEND_COMMIT, FRONTEND_SHORT_COMMIT } = await import('../../src/config/version')

    expect(FRONTEND_COMMIT).toBe('unknown')
    expect(FRONTEND_SHORT_COMMIT).toBe('dev')
  })

  it('reports frontendServiceVersion() with no start time and reachable true', async () => {
    ;(import.meta.env as MutableEnv).VITE_GIT_SHA = '840c311abcdef0123456789abcdef0123456789a'

    const { frontendServiceVersion } = await import('../../src/config/version')
    const version = frontendServiceVersion()

    expect(version.name).toBe('frontend')
    expect(version.startedAt).toBeNull()
    expect(version.reachable).toBe(true)
  })
})
