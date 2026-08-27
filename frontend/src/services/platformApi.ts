import { API_BASE_URL } from '../config/api'
import type { PlatformStatus, Release } from '../types/platform'

const DEFAULT_LIMIT = 20

/**
 * What is running in production right now.
 *
 * @throws Error with a readable message when the request fails, so the page can show it
 */
export async function fetchPlatformStatus(): Promise<PlatformStatus> {
  const response = await fetch(`${API_BASE_URL}/api/platform/status`)
  if (!response.ok) {
    throw new Error(`Unable to load platform status (${response.status}).`)
  }
  return (await response.json()) as PlatformStatus
}

/**
 * Recent releases, newest first.
 *
 * @param limit how many to request; the backend clamps this to 100
 */
export async function fetchReleases(limit: number = DEFAULT_LIMIT): Promise<Release[]> {
  const response = await fetch(`${API_BASE_URL}/api/platform/releases?limit=${limit}`)
  if (!response.ok) {
    throw new Error(`Unable to load releases (${response.status}).`)
  }
  return (await response.json()) as Release[]
}
