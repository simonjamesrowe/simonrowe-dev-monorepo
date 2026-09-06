import { useEffect, useRef } from 'react'

import { useTour } from '../../hooks/useTour'

/**
 * The query is built up in stages rather than retyped from scratch at each one, because every
 * stage is a refinement of the last. Restarting from an empty string took the query below the
 * two-character minimum and tore the results down between stages — the opposite of what this
 * step exists to demonstrate.
 */
export const QUERY_STAGES = ['spring boot', ' kubernetes', ' jenkins']

/**
 * Typing is deliberately unhurried, and the dwell at each stage is deliberately long.
 *
 * The point of this step is the autocomplete results, not the typing: a real search runs
 * behind it, and `SiteSearch` debounces 300ms before issuing a request. Typing as fast as a
 * machine can meant every query was superseded before its results came back, so the step only
 * ever showed a filling input and an empty dropdown. Each stage now finishes, waits out the
 * debounce, waits for the response, and stays on screen long enough to be read.
 */
export const CHAR_DELAY_MS = 90
export const RESULTS_DWELL_MS = 2600

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms)
    const onAbort = () => {
      clearTimeout(timer)
      resolve()
    }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

export function SearchSimulation() {
  const { setSearchValue, setStepActivityPending } = useTour()
  const abortControllerRef = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    abortControllerRef.current = controller
    const { signal } = controller

    // Autoplay waits on this: advancing mid-word would cut the demonstration in half.
    setStepActivityPending(true)

    const simulate = async () => {
      let typed = ''
      for (const stage of QUERY_STAGES) {
        for (const character of stage) {
          if (signal.aborted) return
          typed += character
          setSearchValue(typed)
          await delay(CHAR_DELAY_MS, signal)
        }
        if (signal.aborted) return
        // Dwell at every stage, the last included: without it the step's narration could
        // finish and the tour advance the instant the final results appeared.
        await delay(RESULTS_DWELL_MS, signal)
      }
    }

    void simulate().finally(() => {
      // Only the live run may release the flag. An aborted run's cleanup has already done so,
      // and under React's development double-invoke the discarded run settles *after* the
      // live one has started — clearing it there lets the tour advance mid-demonstration.
      if (!signal.aborted) {
        setStepActivityPending(false)
      }
    })

    return () => {
      controller.abort()
      setSearchValue('')
      setStepActivityPending(false)
    }
  }, [setSearchValue, setStepActivityPending])

  return null
}
