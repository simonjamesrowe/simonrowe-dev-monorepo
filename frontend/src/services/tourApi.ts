import { API_BASE_URL } from '../config/api'
import type { TourStep } from '../types/tour'

const TOUR_ENDPOINT = `${API_BASE_URL}/api/tour/steps`

export async function fetchTourSteps(): Promise<TourStep[]> {
  const response = await fetch(TOUR_ENDPOINT)
  if (!response.ok) {
    let message = 'Unable to load tour data.'
    try {
      const errorPayload = await response.json()
      if (typeof errorPayload.message === 'string' && errorPayload.message.trim() !== '') {
        message = errorPayload.message
      }
    } catch {
      // Keep default fallback message
    }
    throw new Error(message)
  }
  return (await response.json()) as TourStep[]
}
