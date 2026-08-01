import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { Profile } from '../types/Profile'

const PROFILE_ENDPOINT = `${API_BASE_URL}/api/profile`

export async function fetchProfile(): Promise<Profile> {
  return fetchWithRetry<Profile>(PROFILE_ENDPOINT, {
    fallbackMessage: 'Unable to load profile data.',
  })
}
