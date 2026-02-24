import { useEffect, useRef } from 'react'

import { useTour } from '../../hooks/useTour'

const QUERIES = ['spring boot', 'spring boot kubernetes', 'spring boot kubernetes jenkins']
const CHAR_DELAY_MS = 50
const PAUSE_DELAY_MS = 1500

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
  const { setSearchValue } = useTour()
  const abortControllerRef = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    abortControllerRef.current = controller
    const { signal } = controller

    const simulate = async () => {
      for (let qi = 0; qi < QUERIES.length; qi++) {
        const query = QUERIES[qi]
        for (let i = 1; i <= query.length; i++) {
          if (signal.aborted) return
          setSearchValue(query.substring(0, i))
          await delay(CHAR_DELAY_MS, signal)
          if (signal.aborted) return
        }
        if (qi < QUERIES.length - 1) {
          await delay(PAUSE_DELAY_MS, signal)
          if (signal.aborted) return
        }
      }
    }

    void simulate()

    return () => {
      controller.abort()
      setSearchValue('')
    }
  }, [setSearchValue])

  return null
}
