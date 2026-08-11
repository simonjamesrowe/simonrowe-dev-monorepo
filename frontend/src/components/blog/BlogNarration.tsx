import { Headphones, Loader2 } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'

import { API_BASE_URL } from '../../config/api'
import {
  fetchBlogNarrationStatus,
  requestBlogNarration,
} from '../../services/blogApi'
import type { BlogNarrationResponse } from '../../types/blog'

const LONG_POLL_SECONDS = 25
const MAX_LONG_POLLS = 4
const PLAYBACK_SPEEDS = [0.75, 1, 1.25, 1.5, 2]

interface BlogNarrationProps {
  blogId: string
  blogTitle: string
}

function isPending(response: BlogNarrationResponse | null | undefined): boolean {
  return response?.state === 'QUEUED' || response?.state === 'PROCESSING'
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function mediaUrl(path: string): string {
  return path.startsWith('http://') || path.startsWith('https://')
    ? path
    : `${API_BASE_URL}${path}`
}

function formatApproximateDuration(durationSeconds: number): string {
  const minutes = Math.max(1, Math.round(durationSeconds / 60))
  return `About ${minutes} min`
}

export function BlogNarration({ blogId, blogTitle }: BlogNarrationProps) {
  const [narration, setNarration] = useState<BlogNarrationResponse | null>(null)
  const [checking, setChecking] = useState(true)
  const [requesting, setRequesting] = useState(false)
  const [delayed, setDelayed] = useState(false)
  const [clientError, setClientError] = useState<string | null>(null)
  const [playbackRate, setPlaybackRate] = useState(1)
  const controllerRef = useRef<AbortController | null>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const headingId = `blog-narration-${blogId}`

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
      const next = await fetchBlogNarrationStatus(blogId, {
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
  }, [blogId])

  const checkStatus = useCallback(async (current?: BlogNarrationResponse) => {
    const controller = replaceController()
    setClientError(null)
    setDelayed(false)
    setChecking(current === undefined)
    if (current === undefined) {
      setNarration(null)
    }

    try {
      const startingStatus = current ?? await fetchBlogNarrationStatus(blogId, {
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
  }, [blogId, pollUntilSettled, replaceController])

  useEffect(() => {
    void checkStatus()
    return () => controllerRef.current?.abort()
  }, [checkStatus])

  const handleRequest = async () => {
    const controller = replaceController()
    setRequesting(true)
    setClientError(null)
    setDelayed(false)

    try {
      const response = await requestBlogNarration(blogId, controller.signal)
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
  }

  const handlePlaybackRateChange = (rate: number) => {
    setPlaybackRate(rate)
    if (audioRef.current) {
      audioRef.current.playbackRate = rate
    }
  }

  const handleAudioPlay = (event: React.SyntheticEvent<HTMLAudioElement>) => {
    document.querySelectorAll('audio').forEach((audio) => {
      if (audio !== event.currentTarget) {
        audio.pause()
      }
    })
  }

  let content: React.ReactNode

  if (checking) {
    content = (
      <p aria-live="polite" className="blog-narration__status" role="status">
        <Loader2 aria-hidden="true" className="blog-narration__spinner" size={18} />
        Checking audio availability&hellip;
      </p>
    )
  } else if (clientError) {
    content = (
      <div className="blog-narration__feedback">
        <p className="blog-narration__message blog-narration__message--error" role="alert">
          {clientError}
        </p>
        <button
          className="button button--secondary blog-narration__action"
          onClick={() => void checkStatus(isPending(narration) ? narration ?? undefined : undefined)}
          type="button"
        >
          Check audio status
        </button>
      </div>
    )
  } else if (requesting) {
    content = (
      <p aria-live="polite" className="blog-narration__status" role="status">
        <Loader2 aria-hidden="true" className="blog-narration__spinner" size={18} />
        Requesting audio&hellip;
      </p>
    )
  } else if (!narration || narration.state === 'NOT_REQUESTED') {
    content = (
      <div className="blog-narration__prompt">
        <p className="blog-narration__message">
          Prefer to listen? Generate an audio version of this post.
        </p>
        <button
          className="button button--secondary blog-narration__action"
          onClick={() => void handleRequest()}
          type="button"
        >
          Listen to this post
        </button>
      </div>
    )
  } else if (narration.state === 'READY') {
    content = (
      <div className="blog-narration__player">
        <audio
          aria-label={`Generated narration for ${blogTitle}`}
          controls
          onLoadedMetadata={() => {
            if (audioRef.current) audioRef.current.playbackRate = playbackRate
          }}
          onPlay={handleAudioPlay}
          preload="metadata"
          ref={audioRef}
          src={mediaUrl(narration.audioUrl)}
        >
          Your browser does not support audio playback.
        </audio>
        <div className="blog-narration__player-meta">
          <span>{formatApproximateDuration(narration.durationSeconds)}</span>
          <label className="blog-narration__speed-label" htmlFor={`${headingId}-speed`}>
            Playback speed
            <select
              className="blog-narration__speed"
              id={`${headingId}-speed`}
              onChange={(event) => handlePlaybackRateChange(Number(event.target.value))}
              value={playbackRate}
            >
              {PLAYBACK_SPEEDS.map((speed) => (
                <option key={speed} value={speed}>{speed}&times;</option>
              ))}
            </select>
          </label>
        </div>
      </div>
    )
  } else if (isPending(narration)) {
    content = delayed ? (
      <div className="blog-narration__feedback">
        <p aria-live="polite" className="blog-narration__message" role="status">
          This is taking longer than usual. You can keep reading and check again.
        </p>
        <button
          className="button button--secondary blog-narration__action"
          onClick={() => void checkStatus(narration)}
          type="button"
        >
          Check audio status
        </button>
      </div>
    ) : (
      <p aria-live="polite" aria-atomic="true" className="blog-narration__status" role="status">
        <Loader2 aria-hidden="true" className="blog-narration__spinner" size={18} />
        Preparing audio. You can keep reading.
      </p>
    )
  } else if (narration.state === 'FAILED') {
    content = (
      <div className="blog-narration__feedback">
        <p className="blog-narration__message blog-narration__message--error" role="alert">
          Audio could not be prepared. {narration.message}
        </p>
        {narration.retryable && (
          <button
            className="button button--secondary blog-narration__action"
            onClick={() => void handleRequest()}
            type="button"
          >
            Try again
          </button>
        )}
      </div>
    )
  } else {
    const message = narration.state === 'INELIGIBLE'
      ? 'Narration is not available for this post.'
      : 'Narration is temporarily unavailable.'
    content = <p className="blog-narration__message" role="status">{message}</p>
  }

  return (
    <section aria-labelledby={headingId} className="blog-narration">
      <div className="blog-narration__heading">
        <span className="blog-narration__icon" aria-hidden="true">
          <Headphones size={20} />
        </span>
        <div>
          <span className="blog-narration__eyebrow">Listen</span>
          <h2 className="blog-narration__title" id={headingId}>Generated narration</h2>
        </div>
      </div>
      <div className="blog-narration__body">{content}</div>
    </section>
  )
}
