import { API_BASE_URL } from '../config/api'
import type { Profile } from '../types/Profile'

const PROFILE_ENDPOINT = `${API_BASE_URL}/api/profile`

export async function fetchProfile(): Promise<Profile> {
  const response = await fetch(PROFILE_ENDPOINT)

  if (!response.ok) {
    let message = 'Unable to load profile data.'

    try {
      const errorPayload = await response.json()
      if (typeof errorPayload.message === 'string' && errorPayload.message.trim() !== '') {
        message = errorPayload.message
      }
    } catch {
      // Keep default fallback message when the response has no JSON payload.
    }

    throw new Error(message)
  }

  return (await response.json()) as Profile
}
