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
  /** Null until the summary has been written, which happens within an hour of it arriving. */
  headline: string | null
  points: NewsletterPoint[]
}

/** A recent email from the school that has been approved for the public. */
export interface SchoolMessage {
  id: string
  /** The email's subject. */
  title: string
  /** ISO instant. */
  publishedAt: string
  yearGroups: string[]
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

/** The school's recent emails, newest first, leaving out the ones that only cover a newsletter. */
export function fetchSchoolMessages(signal?: AbortSignal): Promise<SchoolMessage[]> {
  return getJson<SchoolMessage[]>('/api/school/messages', signal)
}
