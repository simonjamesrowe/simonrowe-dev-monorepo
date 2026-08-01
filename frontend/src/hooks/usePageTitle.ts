import { useEffect } from 'react'

/** Shown on the home page and as the document default. */
export const SITE_TITLE = 'Simon Rowe | Software Engineering Leader'

/**
 * Sets the browser tab title for a page.
 *
 * Replaces the imperative `document.title` effects that were scattered across the
 * page components, three of which omitted the site name entirely.
 *
 * Pass nothing for the home page (or anywhere the bare site title is wanted); pass a
 * page name to get `<page> · Simon Rowe`. The title is re-applied whenever the
 * argument changes, so pages whose title depends on fetched data (a profile name, a
 * blog post title) can pass `undefined` on the first render and the real value once
 * it arrives.
 */
export function usePageTitle(pageTitle?: string): void {
  useEffect(() => {
    document.title =
      pageTitle === undefined || pageTitle.trim() === ''
        ? SITE_TITLE
        : `${pageTitle} · Simon Rowe`
  }, [pageTitle])
}
