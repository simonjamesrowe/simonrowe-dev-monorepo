import { API_BASE_URL } from '../config/api'
import type { EventPage, EventResponse } from '../types/events'

const EVENTS_ENDPOINT = `${API_BASE_URL}/api/events`

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = 'Unable to load events data.'
    try {
      const errorPayload = await response.json()
      if (typeof errorPayload.message === 'string' && errorPayload.message.trim() !== '') {
        message = errorPayload.message
      }
    } catch {
      // Keep default
    }
    throw new Error(message)
  }
  return (await response.json()) as T
}

export async function fetchEvents(page = 0, size = 20, upcoming?: boolean): Promise<EventPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (upcoming !== undefined) params.set('upcoming', String(upcoming))
  const response = await fetch(`${EVENTS_ENDPOINT}?${params}`)
  return handleResponse<EventPage>(response)
}

export async function fetchEventsById(id: string): Promise<EventResponse> {
  const response = await fetch(`${EVENTS_ENDPOINT}/${id}`)
  return handleResponse<EventResponse>(response)
}
