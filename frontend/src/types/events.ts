export interface EventResponse {
  id: string
  title: string
  sourceName: string
  originalUrl: string
  summary: string | null
  description: string | null
  eventDate: string
  eventEndDate: string | null
  venue: string | null
  location: string | null
  fetchedAt: string
  visible: boolean
}

export interface EventPage {
  content: EventResponse[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}
