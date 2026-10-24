import { useEffect, useState } from 'react'

import {
  fetchNewsletters,
  fetchSchoolMessages,
  type Newsletter,
  type SchoolMessage,
} from './schoolNewsApi'

export type NewsLoad<T> =
  | { status: 'loading' }
  | { status: 'ready'; items: T[] }
  | { status: 'error' }

export interface SchoolNews {
  newsletters: NewsLoad<Newsletter>
  messages: NewsLoad<SchoolMessage>
}

/**
 * The newsletters and the school's recent emails, read once when the page opens.
 *
 * Not narrowed by year group: a newsletter is one document for the whole school, and the points
 * that concern particular years say so. The two requests are independent, so one failing still
 * shows the other.
 */
export function useSchoolNews(): SchoolNews {
  const [newsletters, setNewsletters] = useState<NewsLoad<Newsletter>>({ status: 'loading' })
  const [messages, setMessages] = useState<NewsLoad<SchoolMessage>>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    fetchNewsletters(controller.signal)
      .then((items) => setNewsletters({ status: 'ready', items }))
      .catch(() => {
        if (!controller.signal.aborted) setNewsletters({ status: 'error' })
      })
    fetchSchoolMessages(controller.signal)
      .then((items) => setMessages({ status: 'ready', items }))
      .catch(() => {
        if (!controller.signal.aborted) setMessages({ status: 'error' })
      })
    return () => controller.abort()
  }, [])

  return { newsletters, messages }
}
