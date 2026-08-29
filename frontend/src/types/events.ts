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
  /**
   * The full absolute share URL, ready to use. Absent when the event has no link minted
   * yet — the Share control is hidden rather than broken.
   */
  shortUrl?: string | null
}

export interface EventPage {
  content: EventResponse[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}
