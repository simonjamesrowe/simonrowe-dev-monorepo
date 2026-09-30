import { useEffect, useState } from 'react'

import { fetchHomePage } from '../services/homePageApi'
import { DEFAULT_HOME_PAGE, type HomePageContent } from '../types/homePage'

/**
 * The hero copy, or `null` while it loads. A failed fetch resolves to the defaults rather
 * than an error: the hero is the first thing on the site, and wording from the last deploy
 * is better than a broken landing page.
 */
export function useHomePage(): HomePageContent | null {
  const [content, setContent] = useState<HomePageContent | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchHomePage()
      .then(result => {
        if (!cancelled) setContent(result)
      })
      .catch(() => {
        if (!cancelled) setContent(DEFAULT_HOME_PAGE)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return content
}
