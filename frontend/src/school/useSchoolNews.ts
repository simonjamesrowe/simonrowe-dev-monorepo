import { useEffect, useState } from 'react'

import { fetchNewsletters, type Newsletter } from './schoolNewsApi'

export type NewsLoad<T> =
  | { status: 'loading' }
  | { status: 'ready'; items: T[] }
  | { status: 'error' }

/**
 * The school's newsletters, read once when the page opens.
 *
 * Not narrowed by year group: a newsletter is one document for the whole school, and the points
 * that concern particular years say so.
 */
export function useSchoolNewsletters(): NewsLoad<Newsletter> {
  const [newsletters, setNewsletters] = useState<NewsLoad<Newsletter>>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    fetchNewsletters(controller.signal)
      .then((items) => setNewsletters({ status: 'ready', items }))
      .catch(() => {
        if (!controller.signal.aborted) setNewsletters({ status: 'error' })
      })
    return () => controller.abort()
  }, [])

  return newsletters
}
