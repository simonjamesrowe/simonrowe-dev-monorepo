import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { EventPage, EventResponse } from '../types/events'

const EVENTS_ENDPOINT = `${API_BASE_URL}/api/events`

const FALLBACK_MESSAGE = 'Unable to load events data.'

export async function fetchEvents(page = 0, size = 20, upcoming?: boolean): Promise<EventPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (upcoming !== undefined) params.set('upcoming', String(upcoming))
  return fetchWithRetry<EventPage>(`${EVENTS_ENDPOINT}?${params}`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}

export async function fetchEventsById(id: string): Promise<EventResponse> {
  return fetchWithRetry<EventResponse>(`${EVENTS_ENDPOINT}/${id}`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}
