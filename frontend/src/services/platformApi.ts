import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { PlatformStatus, ReleasePage } from '../types/platform'

export const RELEASE_PAGE_SIZE = 10

export interface ReleaseQuery {
  page?: number
  size?: number
  /** A conventional-commit type such as `feat`; absent for every type. */
  type?: string | null
  /** Free text matched against the subject, the release note and the SHA. */
  query?: string
}

/**
 * What is running in production right now.
 *
 * @throws Error with a readable message when the request fails, so the page can show it
 */
export async function fetchPlatformStatus(): Promise<PlatformStatus> {
  return fetchWithRetry<PlatformStatus>(`${API_BASE_URL}/api/platform/status`, {
    fallbackMessage: 'Unable to load platform status.',
  })
}

/**
 * One page of the changelog, newest first. The whole stored history is reachable a page at a
 * time; the backend clamps `size` to 50.
 */
export async function fetchReleases({
  page = 0,
  size = RELEASE_PAGE_SIZE,
  type = null,
  query = '',
}: ReleaseQuery = {}): Promise<ReleasePage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (type) params.set('type', type)
  if (query.trim()) params.set('q', query.trim())
  return fetchWithRetry<ReleasePage>(`${API_BASE_URL}/api/platform/releases?${params}`, {
    fallbackMessage: 'Unable to load releases.',
  })
}
