import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'

import { matchTimings, wordAt, type WordTiming } from './readAlongTimings'
import { narrationMediaUrl } from '../narration/narrationMedia'
import { useNarrationAudio } from '../narration/useNarrationAudio'

/**
 * The text being heard, with the spoken word and its sentence highlighted.
 *
 * The timings are one entry per word of the narration *script* (the title, then the body as
 * plain prose), so they are matched onto the rendered words in order (see `matchTimings`) rather
 * than assumed to line up one for one. Code blocks and tables are not narrated, so their words are
 * never wrapped and stay unhighlighted.
 *
 * The highlight runs on its own animation-frame loop from the audio element's clock and touches
 * only the spans that change. Going through React state would re-render every word several times
 * a second, and `position` only updates about four times a second, which is too slow for words.
 */

interface HastNode {
  type: string
  value?: string
  tagName?: string
  properties?: Record<string, unknown>
  children?: HastNode[]
}

/** Elements whose text the narration leaves out: "Code example omitted", "Table omitted". */
const UNNARRATED = new Set(['pre', 'table'])
/** After the reader scrolls the text themselves, stop following for this long. */
const MANUAL_SCROLL_GRACE_MS = 6000
/** Where the current line sits in the box, as a share of its height from the top. */
const FOLLOW_OFFSET = 0.35
const SCROLL_KEYS = new Set(['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '])

const WORD = 'read-along__word'
const CURRENT = 'read-along__word--current'
const SENTENCE = 'read-along__word--sentence'
const SPOKEN = 'read-along__word--spoken'
const TIMED = 'read-along__word--timed'

/** Wraps every narrated word of the rendered text in a span the highlighter can find. */
function rehypeWrapWords() {
  return (tree: HastNode) => {
    const walk = (node: HastNode) => {
      if (!node.children) return
      node.children = node.children.flatMap((child) => {
        if (child.type === 'element' && UNNARRATED.has(child.tagName ?? '')) return [child]
        if (child.type !== 'text' || !child.value) {
          walk(child)
          return [child]
        }
        return child.value.split(/(\s+)/).filter(Boolean).map((part) => (/^\s+$/.test(part)
          ? { type: 'text', value: part }
          : {
            type: 'element',
            tagName: 'span',
            properties: { className: [WORD] },
            children: [{ type: 'text', value: part }],
          }))
      })
    }
    walk(tree)
  }
}

/** The text is from a model or a post: links are not followed from here, and images are not shown. */
const COMPONENTS: Components = {
  a({ children }) {
    return <>{children}</>
  },
  img() {
    return null
  },
}

interface Layout {
  /** Only the words that were matched to a timing, in order. */
  words: HTMLElement[]
  starts: number[]
  sentenceOf: number[]
  /** First and last index in `words` of each sentence, keyed by sentence number. */
  sentences: Map<number, [number, number]>
}

const EMPTY_LAYOUT: Layout = { words: [], starts: [], sentenceOf: [], sentences: new Map() }

interface ReadAlongProps {
  markdown: string
  /** The timings file, or undefined for audio made before read-along, shown as plain text. */
  timingsUrl?: string
  className?: string
}

export function ReadAlong({ markdown, timingsUrl, className }: ReadAlongProps) {
  const { currentTime, seek, playing, position } = useNarrationAudio()
  const scrollerRef = useRef<HTMLDivElement>(null)
  const layoutRef = useRef<Layout>(EMPTY_LAYOUT)
  const lastIndexRef = useRef(-1)
  const lastLineRef = useRef<number | null>(null)
  const pausedUntilRef = useRef(0)
  const pauseTimerRef = useRef<number | undefined>(undefined)
  const [timings, setTimings] = useState<WordTiming[] | null>(null)
  const [paused, setPaused] = useState(false)

  useEffect(() => {
    setTimings(null)
    if (!timingsUrl) return undefined
    const controller = new AbortController()
    fetch(narrationMediaUrl(timingsUrl), { signal: controller.signal })
      .then((response) => (response.ok ? response.json() : null))
      .then((body: { words?: WordTiming[] } | null) => {
        if (!controller.signal.aborted && Array.isArray(body?.words)) setTimings(body.words)
      })
      .catch(() => {
        // No timings: the text still shows, just without the highlight.
      })
    return () => controller.abort()
  }, [timingsUrl])

  // Match the timings onto the rendered words once per text, and start the highlight afresh.
  useLayoutEffect(() => {
    const scroller = scrollerRef.current
    const rendered = scroller
      ? Array.from(scroller.querySelectorAll<HTMLElement>(`.${WORD}`))
      : []
    for (const word of rendered) word.classList.remove(CURRENT, SENTENCE, SPOKEN, TIMED)
    lastIndexRef.current = -1
    lastLineRef.current = null
    if (!timings) {
      layoutRef.current = EMPTY_LAYOUT
      return
    }
    const matches = matchTimings(rendered.map((word) => word.textContent ?? ''), timings)
    const layout: Layout = { words: [], starts: [], sentenceOf: [], sentences: new Map() }
    matches.forEach((match, index) => {
      if (match < 0) return
      const slot = layout.words.length
      const sentence = timings[match].n
      layout.words.push(rendered[index])
      layout.starts.push(timings[match].s)
      layout.sentenceOf.push(sentence)
      const range = layout.sentences.get(sentence)
      layout.sentences.set(sentence, [range ? range[0] : slot, slot])
      rendered[index].classList.add(TIMED)
    })
    layoutRef.current = layout
  }, [markdown, timings])

  const paint = useCallback((index: number) => {
    const last = lastIndexRef.current
    if (index === last) return
    const { words, sentenceOf, sentences } = layoutRef.current
    // Everything before the current word has been heard.
    for (let i = Math.min(last, index); i < Math.max(last, index); i += 1) {
      if (i >= 0) words[i].classList.toggle(SPOKEN, i < index)
    }
    if (last >= 0) words[last]?.classList.remove(CURRENT)
    const lastSentence = last >= 0 ? sentenceOf[last] : undefined
    const sentence = index >= 0 ? sentenceOf[index] : undefined
    if (lastSentence !== sentence) {
      const toggleSentence = (number: number | undefined, on: boolean) => {
        const range = number === undefined ? undefined : sentences.get(number)
        if (!range) return
        for (let i = range[0]; i <= range[1]; i += 1) words[i].classList.toggle(SENTENCE, on)
      }
      toggleSentence(lastSentence, false)
      toggleSentence(sentence, true)
    }
    lastIndexRef.current = index

    const current = index >= 0 ? words[index] : undefined
    const scroller = scrollerRef.current
    if (!current || !scroller) return
    current.classList.add(CURRENT)
    // Scroll only when the line changes, and never while the reader is scrolling themselves.
    if (current.offsetTop === lastLineRef.current || Date.now() < pausedUntilRef.current) return
    lastLineRef.current = current.offsetTop
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    scroller.scrollTo?.({
      top: Math.max(0, current.offsetTop - scroller.clientHeight * FOLLOW_OFFSET),
      behavior: reduce ? 'auto' : 'smooth',
    })
  }, [])

  const paintNow = useCallback(() => {
    paint(wordAt(layoutRef.current.starts, currentTime()))
  }, [currentTime, paint])

  useEffect(() => {
    if (!timings || !playing) return undefined
    let frame = 0
    const tick = () => {
      paintNow()
      frame = requestAnimationFrame(tick)
    }
    tick()
    return () => cancelAnimationFrame(frame)
  }, [paintNow, playing, timings])

  // Paused, the clock only moves by seeking, which `position` reports.
  useEffect(() => {
    if (timings && !playing) paintNow()
  }, [paintNow, playing, position, timings])

  useEffect(() => () => window.clearTimeout(pauseTimerRef.current), [])

  // Every scroll restarts the grace period, so the button and the pause end together.
  const pauseFollowing = () => {
    if (!timings) return
    pausedUntilRef.current = Date.now() + MANUAL_SCROLL_GRACE_MS
    window.clearTimeout(pauseTimerRef.current)
    pauseTimerRef.current = window.setTimeout(() => setPaused(false), MANUAL_SCROLL_GRACE_MS)
    setPaused(true)
  }

  const resumeFollowing = () => {
    window.clearTimeout(pauseTimerRef.current)
    pausedUntilRef.current = 0
    lastLineRef.current = null
    setPaused(false)
    const index = lastIndexRef.current
    lastIndexRef.current = -1
    paint(-1)
    paint(index)
  }

  const onClick = (event: React.MouseEvent) => {
    const word = (event.target as HTMLElement).closest(`.${TIMED}`)
    if (!word) return
    const { words, starts } = layoutRef.current
    const index = words.indexOf(word as HTMLElement)
    if (index >= 0) seek(starts[index])
  }

  return (
    <div className={`read-along${timings ? ' read-along--timed' : ''} ${className ?? ''}`}>
      {/* Focusable although not interactive, deliberately (Sonar typescript:S6845 declined): it
          is a scrolling box, and without a tab stop a keyboard user cannot scroll it at all.
          Safari does not make scrollers focusable by itself. WCAG 2.1.1. */}
      <div
        aria-label="Read along"
        className="read-along__text"
        onClick={onClick}
        onKeyDown={(event) => { if (SCROLL_KEYS.has(event.key)) pauseFollowing() }}
        onTouchMove={pauseFollowing}
        onWheel={pauseFollowing}
        ref={scrollerRef}
        role="region"
        tabIndex={0}
      >
        <ReactMarkdown
          components={COMPONENTS}
          rehypePlugins={[rehypeWrapWords]}
          remarkPlugins={[remarkGfm]}
          skipHtml
        >
          {markdown}
        </ReactMarkdown>
      </div>
      {timings && paused && (
        <button className="read-along__follow" onClick={resumeFollowing} type="button">
          Back to where it's reading
        </button>
      )}
    </div>
  )
}
