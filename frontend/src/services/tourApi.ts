import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { TourStep } from '../types/tour'

const TOUR_ENDPOINT = `${API_BASE_URL}/api/tour/steps`

export async function fetchTourSteps(): Promise<TourStep[]> {
  return fetchWithRetry<TourStep[]>(TOUR_ENDPOINT, {
    fallbackMessage: 'Unable to load tour data.',
  })
}
