import { useCallback, useEffect, useRef, useState } from 'react'

import type { BlogNarrationResponse } from '../../types/blog'

/**
 * Matches the server-side @Max(25) bound on waitSeconds.
 *
 * Exported so `NarrationAudioProvider`'s generation chain reuses this policy rather than
 * inventing a second one. It needs its own loop — a per-track controller, a two-step
 * summary-then-narration chain and a dismissed-track check between iterations — but it must not
 * disagree with this file about how long a poll waits or when to give up.
 */
export const LONG_POLL_SECONDS = 25

/**
 * Enough long-polls to cover a render that has badly overrun. Past this the panel offers a
 * manual re-check rather than holding a request open forever.
 */
export const MAX_LONG_POLLS = 4

export interface NarrationTransport {
  /** Reads the current state, optionally long-polling for a change. */
  fetchStatus: (options: {
    afterVersion?: number
    waitSeconds?: number
    signal?: AbortSignal
  }) => Promise<BlogNarrationResponse>
  /** Queues generation. */
  request: (signal?: AbortSignal) => Promise<BlogNarrationResponse>
}

export interface NarrationState {
  narration: BlogNarrationResponse | null
  /** True during the initial availability check, before anything is known. */
  checking: boolean
  /** True while a generation request is being issued. */
  requesting: boolean
  /** True once polling gave up waiting and the reader should re-check manually. */
  delayed: boolean
  clientError: string | null
  requestNarration: () => Promise<void>
  checkStatus: (current?: BlogNarrationResponse) => Promise<void>
  /** Re-check from whatever is currently known — what a "check again" button calls. */
  recheck: () => void
}

export function isPending(
  response: BlogNarrationResponse | null | undefined,
): boolean {
  return response?.state === 'QUEUED' || response?.state === 'PROCESSING'
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

/**
 * Long-poll orchestration for a narration endpoint: initial status read, polling while
 * generation is in flight, abort-on-unmount, and the "this is taking a while" escape hatch.
 *
 * Extracted verbatim from `BlogNarration`, which was roughly 300 lines doing three
 * separable jobs. All of this is needed unchanged by the article summary drawer, so it is
 * parameterised by a transport rather than copied: the blog `POST` is public while the
 * summary `POST` is authenticated, and that is the only difference between the two callers.
 */
export function useNarration(transport: NarrationTransport): NarrationState {
  const [narration, setNarration] = useState<BlogNarrationResponse | null>(null)
  const [checking, setChecking] = useState(true)
  const [requesting, setRequesting] = useState(false)
  const [delayed, setDelayed] = useState(false)
  const [clientError, setClientError] = useState<string | null>(null)
  const controllerRef = useRef<AbortController | null>(null)

  // Held in a ref so the callbacks below keep a stable identity even when the caller
  // rebuilds the transport object on every render.
  const transportRef = useRef(transport)
  transportRef.current = transport

  const replaceController = useCallback(() => {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    return controller
  }, [])

  const pollUntilSettled = useCallback(async (
    initial: BlogNarrationResponse,
    controller: AbortController,
  ) => {
    let current = initial

    for (let attempt = 0; attempt < MAX_LONG_POLLS; attempt += 1) {
      const next = await transportRef.current.fetchStatus({
        afterVersion: current.version,
        waitSeconds: LONG_POLL_SECONDS,
        signal: controller.signal,
      })

      if (controller.signal.aborted) return

      setNarration(next)
      current = next
      if (!isPending(next)) {
        setDelayed(false)
        return
      }
    }

    if (!controller.signal.aborted) {
      setDelayed(true)
    }
  }, [])

  const checkStatus = useCallback(async (current?: BlogNarrationResponse) => {
    const controller = replaceController()
    setClientError(null)
    setDelayed(false)
    setChecking(current === undefined)
    if (current === undefined) {
      setNarration(null)
    }

    try {
      const startingStatus = current ?? await transportRef.current.fetchStatus({
        signal: controller.signal,
      })
      if (controller.signal.aborted) return

      setNarration(startingStatus)
      setChecking(false)
      if (isPending(startingStatus)) {
        await pollUntilSettled(startingStatus, controller)
      }
    } catch (error) {
      if (!controller.signal.aborted && !isAbortError(error)) {
        setChecking(false)
        setClientError('Audio status could not be checked. Please try again.')
      }
    }
  }, [pollUntilSettled, replaceController])

  useEffect(() => {
    void checkStatus()
    return () => controllerRef.current?.abort()
  }, [checkStatus])

  const requestNarration = useCallback(async () => {
    const controller = replaceController()
    setRequesting(true)
    setClientError(null)
    setDelayed(false)

    try {
      const response = await transportRef.current.request(controller.signal)
      if (controller.signal.aborted) return

      setNarration(response)
      setRequesting(false)
      if (isPending(response)) {
        await pollUntilSettled(response, controller)
      }
    } catch (error) {
      if (!controller.signal.aborted && !isAbortError(error)) {
        setClientError('Audio could not be requested. Please try again.')
      }
    } finally {
      if (!controller.signal.aborted) {
        setRequesting(false)
      }
    }
  }, [pollUntilSettled, replaceController])

  const recheck = useCallback(() => {
    void checkStatus(isPending(narration) ? narration ?? undefined : undefined)
  }, [checkStatus, narration])

  return {
    narration,
    checking,
    requesting,
    delayed,
    clientError,
    requestNarration,
    checkStatus,
    recheck,
  }
}
