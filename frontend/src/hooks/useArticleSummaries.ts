import { useCallback, useEffect, useRef, useState } from 'react'

import { useEnsureAuthenticated } from './useEnsureAuthenticated'
import {
  fetchArticleSummary,
  fetchSummarisedArticleIds,
  requestArticleSummary,
} from '../services/articleSummaryApi'
import { useAuth } from '../auth/useAuth'
import type { ArticleSummaryResponse } from '../types/articleSummary'

/** Matches the server-side @Max(25) bound on waitSeconds. */
const LONG_POLL_SECONDS = 25

/**
 * Enough long-polls to cover a generation that has badly overrun. Past this the drawer
 * offers a manual re-check rather than holding a request open forever.
 */
const MAX_LONG_POLLS = 4

function isPending(summary: ArticleSummaryResponse | null | undefined): boolean {
  return summary?.state === 'GENERATING'
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

export interface ArticleSummariesApi {
  /** Whether the article already has a completed summary, from the shared ids set. */
  hasSummary: (articleId: string) => boolean
  /** The summary currently loaded for an article, if any. */
  summaryFor: (articleId: string) => ArticleSummaryResponse | null
  /** True while a request or poll for this article is in flight. */
  isLoading: (articleId: string) => boolean
  /** True when polling gave up waiting and the reader should re-check manually. */
  isDelayed: (articleId: string) => boolean
  /** A client-side error for this article, if any. */
  errorFor: (articleId: string) => string | null
  /** Reads the existing summary. Public — no session needed. */
  loadSummary: (articleId: string) => Promise<void>
  /** Requests generation, prompting for sign-in first. Resolves when settled. */
  requestSummary: (articleId: string) => Promise<void>
  /** Drops all in-flight work for an article, e.g. when the drawer closes. */
  cancel: (articleId: string) => void
  /**
   * Records that an article now has a `READY` summary, without refetching the ids set.
   *
   * Exists because the docked audio player's Listen chain can generate a summary as an
   * intermediate step, and it lives *above* this hook in the tree so it cannot write here
   * itself. Without this, a card whose summary came from that chain would keep reading
   * "Summarise" until the next full page load. Same local flip `store()` already performs when a
   * drawer generation completes, and idempotent.
   */
  noteSummarised: (articleId: string) => void
}

/**
 * Owns article summaries for the news page: the shared "which articles have one" id set,
 * plus per-article state for the drawer.
 *
 * Summaries are globally shared, so the id set and every read are public — a logged-out
 * visitor sees "Read summary" on anything already summarised and opens it with no prompt.
 * Only generation needs a session, because only generation costs money.
 */
export function useArticleSummaries(): ArticleSummariesApi {
  const ensureAuthenticated = useEnsureAuthenticated()
  const { getAccessToken } = useAuth()

  const [summarisedIds, setSummarisedIds] = useState<Set<string>>(new Set())
  const [summaries, setSummaries] = useState<Record<string, ArticleSummaryResponse>>({})
  const [loading, setLoading] = useState<Record<string, boolean>>({})
  const [delayed, setDelayed] = useState<Record<string, boolean>>({})
  const [errors, setErrors] = useState<Record<string, string>>({})

  const controllers = useRef<Map<string, AbortController>>(new Map())

  useEffect(() => {
    let cancelled = false
    fetchSummarisedArticleIds()
      .then(ids => {
        if (!cancelled) setSummarisedIds(new Set(ids))
      })
      .catch(() => {
        // Leave the set empty — every card just offers "Summarise" instead. A failed ids
        // fetch must not stop the news list rendering.
      })
    return () => { cancelled = true }
  }, [])

  // Abort every in-flight poll when the page unmounts.
  const controllersRef = controllers
  useEffect(() => () => {
    controllersRef.current.forEach(controller => controller.abort())
    controllersRef.current.clear()
  }, [controllersRef])

  const replaceController = useCallback((articleId: string) => {
    controllers.current.get(articleId)?.abort()
    const controller = new AbortController()
    controllers.current.set(articleId, controller)
    return controller
  }, [])

  const setFlag = (
    setter: React.Dispatch<React.SetStateAction<Record<string, boolean>>>,
    articleId: string,
    value: boolean,
  ) => setter(prev => ({ ...prev, [articleId]: value }))

  const store = useCallback((articleId: string, summary: ArticleSummaryResponse) => {
    setSummaries(prev => ({ ...prev, [articleId]: summary }))
    if (summary.state === 'READY') {
      // Flip the card label without refetching the whole ids set.
      setSummarisedIds(prev => new Set(prev).add(articleId))
    }
  }, [])

  const pollUntilSettled = useCallback(async (
    articleId: string,
    initial: ArticleSummaryResponse,
    controller: AbortController,
  ) => {
    let current = initial

    for (let attempt = 0; attempt < MAX_LONG_POLLS; attempt += 1) {
      const next = await fetchArticleSummary(articleId, {
        afterVersion: current.version,
        waitSeconds: LONG_POLL_SECONDS,
        signal: controller.signal,
      })
      if (controller.signal.aborted) return

      store(articleId, next)
      current = next
      if (!isPending(next)) {
        setFlag(setDelayed, articleId, false)
        return
      }
    }

    if (!controller.signal.aborted) {
      setFlag(setDelayed, articleId, true)
    }
  }, [store])

  const loadSummary = useCallback(async (articleId: string) => {
    const controller = replaceController(articleId)
    setErrors(prev => {
      const next = { ...prev }
      delete next[articleId]
      return next
    })
    setFlag(setDelayed, articleId, false)
    setFlag(setLoading, articleId, true)

    try {
      const summary = await fetchArticleSummary(articleId, { signal: controller.signal })
      if (controller.signal.aborted) return
      store(articleId, summary)
      setFlag(setLoading, articleId, false)
      if (isPending(summary)) {
        await pollUntilSettled(articleId, summary, controller)
      }
    } catch (error) {
      if (!controller.signal.aborted && !isAbortError(error)) {
        setFlag(setLoading, articleId, false)
        setErrors(prev => ({
          ...prev,
          [articleId]: 'The summary could not be loaded. Please try again.',
        }))
      }
    }
  }, [pollUntilSettled, replaceController, store])

  const requestSummary = useCallback(async (articleId: string) => {
    // Sign-in first. A dismissed popup means no request is issued at all, so a visitor who
    // changes their mind never triggers a paid call.
    if (!(await ensureAuthenticated())) return

    const controller = replaceController(articleId)
    setErrors(prev => {
      const next = { ...prev }
      delete next[articleId]
      return next
    })
    setFlag(setDelayed, articleId, false)
    setFlag(setLoading, articleId, true)

    try {
      const summary = await requestArticleSummary(
        getAccessToken, articleId, controller.signal)
      if (controller.signal.aborted) return
      store(articleId, summary)
      setFlag(setLoading, articleId, false)
      if (isPending(summary)) {
        await pollUntilSettled(articleId, summary, controller)
      }
    } catch (error) {
      if (!controller.signal.aborted && !isAbortError(error)) {
        setFlag(setLoading, articleId, false)
        setErrors(prev => ({
          ...prev,
          [articleId]: 'The summary could not be requested. Please try again.',
        }))
      }
    }
  }, [ensureAuthenticated, getAccessToken, pollUntilSettled, replaceController, store])

  const noteSummarised = useCallback((articleId: string) => {
    setSummarisedIds(prev => (prev.has(articleId) ? prev : new Set(prev).add(articleId)))
  }, [])

  const cancel = useCallback((articleId: string) => {
    controllers.current.get(articleId)?.abort()
    controllers.current.delete(articleId)
    setFlag(setLoading, articleId, false)
    setFlag(setDelayed, articleId, false)
  }, [])

  return {
    hasSummary: useCallback(
      (articleId: string) => summarisedIds.has(articleId), [summarisedIds]),
    summaryFor: useCallback(
      (articleId: string) => summaries[articleId] ?? null, [summaries]),
    isLoading: useCallback(
      (articleId: string) => loading[articleId] ?? false, [loading]),
    isDelayed: useCallback(
      (articleId: string) => delayed[articleId] ?? false, [delayed]),
    errorFor: useCallback(
      (articleId: string) => errors[articleId] ?? null, [errors]),
    loadSummary,
    requestSummary,
    cancel,
    noteSummarised,
  }
}
