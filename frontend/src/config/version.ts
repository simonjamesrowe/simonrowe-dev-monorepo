import type { ServiceVersion } from '../types/platform'

/**
 * This bundle's own commit, baked in by Dockerfile.frontend at build time.
 *
 * The frontend reports its own version rather than letting the backend assert one for it:
 * the backend cannot know which bundle a browser loaded, and a guess would be wrong exactly
 * when it mattered — during a partial deploy, which is the case /status exists to surface.
 *
 * Absent in local development, where the page shows a dev build rather than an error.
 */
export const FRONTEND_COMMIT = import.meta.env.VITE_GIT_SHA || 'unknown'

export const FRONTEND_SHORT_COMMIT =
  FRONTEND_COMMIT === 'unknown' ? 'dev' : FRONTEND_COMMIT.slice(0, 7)

export const FRONTEND_BUILD_TIME = import.meta.env.VITE_BUILD_TIME ?? null

/**
 * This bundle as a `ServiceVersion`, so it can sit in the same list as the backend-reported
 * services without the page special-casing it.
 *
 * `startedAt` is null by design: a static bundle served by nginx has no process start time,
 * and inventing the page-load time would be a different fact wearing the same label.
 */
export function frontendServiceVersion(): ServiceVersion {
  return {
    name: 'frontend',
    commit: FRONTEND_COMMIT,
    shortCommit: FRONTEND_SHORT_COMMIT,
    commitSubject: null,
    commitTime: FRONTEND_BUILD_TIME,
    startedAt: null,
    reachable: true,
  }
}
