import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { PlatformStatus, Release } from '../types/platform'

const DEFAULT_LIMIT = 20

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
 * Recent releases, newest first.
 *
 * @param limit how many to request; the backend clamps this to 100
 */
export async function fetchReleases(limit: number = DEFAULT_LIMIT): Promise<Release[]> {
  return fetchWithRetry<Release[]>(`${API_BASE_URL}/api/platform/releases?limit=${limit}`, {
    fallbackMessage: 'Unable to load releases.',
  })
}
