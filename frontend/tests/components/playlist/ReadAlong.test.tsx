import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ReadAlong } from '../../../src/components/playlist/ReadAlong'
import type { WordTiming } from '../../../src/components/playlist/readAlongTimings'
import { NarrationAudioStub } from '../../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../../testUtils/narrationAudioValue'

vi.mock('../../../src/config/api', () => ({ API_BASE_URL: 'http://backend' }))

const TIMINGS_URL = '/uploads/narrations/n-1/timings-abc.json'

// The title is spoken first, then two sentences of the body.
const WORDS: WordTiming[] = [
  { w: 'Agent', s: 0.4, n: 0 },
  { w: 'observability.', s: 0.8, n: 0 },
  { w: 'Traces', s: 2.4, n: 1 },
  { w: 'follow', s: 2.9, n: 1 },
  { w: 'conventions.', s: 3.3, n: 1 },
  { w: 'Code', s: 4.4, n: 2 },
  { w: 'example', s: 4.6, n: 2 },
  { w: 'omitted.', s: 4.9, n: 2 },
  { w: 'Grafana', s: 6.4, n: 3 },
  { w: 'reads', s: 6.9, n: 3 },
  { w: 'them.', s: 7.2, n: 3 },
]

const MARKDOWN = 'Traces follow conventions.\n\n```\nconst traces = true\n```\n\nGrafana reads them.'

function renderAt(position: number, timingsUrl: string | null = TIMINGS_URL) {
  const audio = narrationAudioStub({ position })
  const view = render(
    <NarrationAudioStub value={audio}>
      <ReadAlong markdown={MARKDOWN} timingsUrl={timingsUrl ?? undefined} />
    </NarrationAudioStub>,
  )
  const moveTo = (seconds: number) => {
    const moved = narrationAudioStub({ position: seconds })
    view.rerender(
      <NarrationAudioStub value={moved}>
        <ReadAlong markdown={MARKDOWN} timingsUrl={timingsUrl ?? undefined} />
      </NarrationAudioStub>,
    )
    return moved
  }
  return { audio, moveTo }
}

const word = (text: string) => screen.getByText(text, { selector: '.read-along__word' })

/** Selects a word as a reader would. jsdom keeps a selection but never fires the event. */
function selectWord(text: string) {
  const range = document.createRange()
  range.selectNodeContents(word(text))
  const selection = document.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
  act(() => { document.dispatchEvent(new Event('selectionchange')) })
}

describe('ReadAlong', () => {
  beforeEach(() => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ version: 1, words: WORDS }), { status: 200 }),
    )
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('highlights the word being spoken and its sentence', async () => {
    renderAt(3.0)

    await waitFor(() => expect(word('follow')).toHaveClass('read-along__word--current'))
    expect(globalThis.fetch).toHaveBeenCalledWith(`http://backend${TIMINGS_URL}`,
      expect.anything())
    expect(word('Traces')).toHaveClass('read-along__word--sentence', 'read-along__word--spoken')
    expect(word('conventions.')).toHaveClass('read-along__word--sentence')
    expect(word('conventions.')).not.toHaveClass('read-along__word--spoken')
    expect(word('Grafana')).not.toHaveClass('read-along__word--sentence')
  })

  it('moves the highlight to the next sentence as the audio moves', async () => {
    const { moveTo } = renderAt(3.0)
    await waitFor(() => expect(word('follow')).toHaveClass('read-along__word--current'))

    moveTo(7.0)

    expect(word('reads')).toHaveClass('read-along__word--current', 'read-along__word--sentence')
    expect(word('follow')).not.toHaveClass('read-along__word--current')
    expect(word('follow')).not.toHaveClass('read-along__word--sentence')
    expect(word('conventions.')).toHaveClass('read-along__word--spoken')
  })

  it('takes the highlight back when the audio is moved back', async () => {
    const { moveTo } = renderAt(7.0)
    await waitFor(() => expect(word('reads')).toHaveClass('read-along__word--current'))

    moveTo(2.5)

    expect(word('Traces')).toHaveClass('read-along__word--current')
    expect(word('Traces')).not.toHaveClass('read-along__word--spoken')
    expect(word('conventions.')).not.toHaveClass('read-along__word--spoken')
    expect(word('reads')).not.toHaveClass('read-along__word--current')
  })

  it('leaves code, which the narration skips, unwrapped', async () => {
    // At 4.6s the narration is saying "Code example omitted", which is not on the page, so the
    // highlight stays on the last word that is.
    renderAt(4.6)
    await waitFor(() => expect(word('conventions.')).toHaveClass('read-along__word--current'))

    expect(screen.getByText('const traces = true')).not.toHaveClass('read-along__word')
    expect(document.querySelectorAll('pre .read-along__word')).toHaveLength(0)
  })

  it('jumps the audio to a word when it is clicked', async () => {
    const { audio } = renderAt(0)
    await waitFor(() => expect(word('Grafana')).toHaveClass('read-along__word--timed'))

    await userEvent.click(word('Grafana'))

    expect(audio.seek).toHaveBeenCalledWith(6.4)
  })

  it('leaves the audio where it is when a click ends a selection', async () => {
    const { audio } = renderAt(0)
    await waitFor(() => expect(word('Grafana')).toHaveClass('read-along__word--timed'))
    selectWord('Grafana')

    fireEvent.click(word('Grafana'))

    expect(audio.seek).not.toHaveBeenCalled()
  })

  it('holds the text still while some of it is selected, and follows again after', async () => {
    const scrollTo = vi.fn()
    const original = HTMLElement.prototype.scrollTo
    HTMLElement.prototype.scrollTo = scrollTo
    try {
      const { moveTo } = renderAt(0)
      await waitFor(() => expect(word('Grafana')).toHaveClass('read-along__word--timed'))
      selectWord('Grafana')

      moveTo(3.0)
      expect(word('follow')).toHaveClass('read-along__word--current')
      expect(scrollTo).not.toHaveBeenCalled()

      act(() => {
        document.getSelection()!.removeAllRanges()
        document.dispatchEvent(new Event('selectionchange'))
      })
      moveTo(7.0)
      expect(scrollTo).toHaveBeenCalled()
    } finally {
      HTMLElement.prototype.scrollTo = original
      document.getSelection()!.removeAllRanges()
    }
  })

  it('stops following while the reader scrolls, and offers the way back', async () => {
    renderAt(3.0)
    await waitFor(() => expect(word('follow')).toHaveClass('read-along__word--current'))

    fireEvent.wheel(screen.getByRole('region', { name: 'Read along' }))

    await userEvent.click(screen.getByRole('button', { name: "Back to where it's reading" }))
    expect(screen.queryByRole('button', { name: "Back to where it's reading" })).toBeNull()
    expect(word('follow')).toHaveClass('read-along__word--current')
  })

  it('keeps offering the way back for as long as the reader keeps scrolling', async () => {
    renderAt(3.0)
    await waitFor(() => expect(word('follow')).toHaveClass('read-along__word--current'))
    vi.useFakeTimers()
    try {
      const region = screen.getByRole('region', { name: 'Read along' })
      const back = () => screen.queryByRole('button', { name: "Back to where it's reading" })

      fireEvent.wheel(region)
      act(() => { vi.advanceTimersByTime(4000) })
      fireEvent.wheel(region)
      act(() => { vi.advanceTimersByTime(4000) })
      // Six seconds after the first scroll, but only four after the second.
      expect(back()).not.toBeNull()

      act(() => { vi.advanceTimersByTime(2500) })
      expect(back()).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows the text plainly when the audio has no timings', () => {
    renderAt(3.0, null)

    expect(globalThis.fetch).not.toHaveBeenCalled()
    expect(word('follow')).not.toHaveClass('read-along__word--timed')
    expect(document.querySelector('.read-along--timed')).toBeNull()
  })

  it('shows the text plainly when the timings cannot be fetched', async () => {
    vi.mocked(globalThis.fetch).mockResolvedValue(new Response('', { status: 404 }))
    renderAt(3.0)

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled())
    expect(word('follow')).not.toHaveClass('read-along__word--current')
  })
})
