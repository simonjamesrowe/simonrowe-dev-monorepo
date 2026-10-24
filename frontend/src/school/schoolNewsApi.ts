import { API_BASE_URL } from '../config/api'

/** One point from a newsletter's summary. */
export interface NewsletterPoint {
  text: string
  /** Empty means the whole school. */
  yearGroups: string[]
}

/** A school newsletter, with its summary once one has been written. */
export interface Newsletter {
  id: string
  title: string
  /** Where the school published it. */
  url: string
  /** ISO instant. */
  publishedAt: string
  /** Null until the summary has been written, usually within minutes of the newsletter arriving. */
  headline: string | null
  points: NewsletterPoint[]
}

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, { signal })
  if (!response.ok) {
    throw new Error(`${path} failed: ${response.status}`)
  }
  return (await response.json()) as T
}

/** The newest newsletters, newest first. Public content only, and needs no token. */
export function fetchNewsletters(signal?: AbortSignal): Promise<Newsletter[]> {
  return getJson<Newsletter[]>('/api/school/newsletters', signal)
}
