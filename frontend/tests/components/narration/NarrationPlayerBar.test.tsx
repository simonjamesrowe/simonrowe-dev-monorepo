import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { NarrationPlayerBar } from '../../../src/components/narration/NarrationPlayerBar'
import { NarrationAudioStub } from '../../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../../testUtils/narrationAudioValue'
import type { NarrationAudioApi } from '../../../src/components/narration/narrationAudioContext'
import type { NarrationTrack } from '../../../src/types/narrationAudio'

const blogTrack: NarrationTrack = {
  contentType: 'BLOG',
  contentId: 'blog-1',
  title: 'Kafka Without Surprises',
  href: '/blogs/blog-1',
  audioUrl: '/uploads/narrations/aaa/narration.mp3',
  durationSeconds: 734,
}

const newsTrack: NarrationTrack = {
  contentType: 'ARTICLE_SUMMARY',
  contentId: 'article-9',
  title: 'Virtual threads in anger',
  href: 'https://example.com/article-9',
  external: true,
}

function renderBar(value: NarrationAudioApi) {
  return render(
    <MemoryRouter>
      <NarrationAudioStub value={value}>
        <NarrationPlayerBar />
      </NarrationAudioStub>
    </MemoryRouter>,
  )
}

describe('NarrationPlayerBar', () => {
  beforeEach(() => {
    // Desktop by default; the compact case stubs its own match.
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }))
  })

  it('renders nothing at all when there is no track and nothing to report', () => {
    const { container } = renderBar(narrationAudioStub())

    expect(container).toBeEmptyDOMElement()
  })

  it('is a labelled region with a polite status for stage announcements', () => {
    renderBar(narrationAudioStub({ track: blogTrack, stage: 'ready', duration: 734 }))

    expect(screen.getByRole('region', { name: 'Narration player' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  })

  it('offers the full transport for a ready track', () => {
    renderBar(narrationAudioStub({
      track: blogTrack, stage: 'ready', duration: 734, position: 65,
    }))

    expect(screen.getByRole('link', { name: 'Kafka Without Surprises' }))
      .toHaveAttribute('href', '/blogs/blog-1')
    expect(screen.getByRole('button', { name: 'Play' })).toBeInTheDocument()
    expect(screen.getByRole('slider', { name: 'Seek' })).toHaveValue('65')
    expect(screen.getByText('1:05 / 12:14')).toBeInTheDocument()
    expect(screen.getByLabelText('Playback speed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close the narration player' }))
      .toBeInTheDocument()
  })

  it('links a news track out to the original article', () => {
    renderBar(narrationAudioStub({ track: newsTrack, stage: 'ready' }))

    const link = screen.getByRole('link', { name: 'Virtual threads in anger' })
    expect(link).toHaveAttribute('href', 'https://example.com/article-9')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('shows Pause while playing', () => {
    renderBar(narrationAudioStub({ track: blogTrack, stage: 'ready', playing: true }))

    expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument()
  })

  it('drives play, seek, speed and dismiss through the provider', async () => {
    const value = narrationAudioStub({
      track: blogTrack, stage: 'ready', duration: 734, position: 10,
    })
    renderBar(value)

    await userEvent.click(screen.getByRole('button', { name: 'Play' }))
    expect(value.togglePlay).toHaveBeenCalledOnce()

    await userEvent.selectOptions(screen.getByLabelText('Playback speed'), '1.5')
    expect(value.setRate).toHaveBeenCalledWith(1.5)

    await userEvent.click(screen.getByRole('button', { name: 'Close the narration player' }))
    expect(value.dismiss).toHaveBeenCalledOnce()
  })

  it('drops the playback-speed control on narrow viewports', () => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }))

    renderBar(narrationAudioStub({ track: blogTrack, stage: 'ready', duration: 734 }))

    // Dropped rather than hidden, so it leaves the tab order too.
    expect(screen.queryByLabelText('Playback speed')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Play' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Kafka Without Surprises' })).toBeInTheDocument()
    expect(screen.getByRole('slider', { name: 'Seek' })).toBeInTheDocument()
  })

  describe('failures', () => {
    it('shows a retryable error with a retry control', async () => {
      const value = narrationAudioStub({
        track: blogTrack,
        error: { message: 'Too many requests. Try again in 42 seconds.', retryable: true },
      })
      renderBar(value)

      expect(screen.getByRole('alert'))
        .toHaveTextContent('Too many requests. Try again in 42 seconds.')
      await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
      expect(value.retry).toHaveBeenCalledOnce()
    })

    // A spent monthly budget, or an article too thin to summarise, cannot be retried into
    // working, so offering the button would be a lie.
    it.each([
      'Audio is unavailable this month.',
      "There isn't enough of this article to summarise.",
    ])('offers no retry for the non-retryable "%s"', (message) => {
      renderBar(narrationAudioStub({
        track: blogTrack,
        error: { message, retryable: false },
      }))

      expect(screen.getByRole('alert')).toHaveTextContent(message)
      expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
    })

    /** Audio that 404s at playback time clears the track, but the message still has to land. */
    it('stays on screen for an error that outlived its track', () => {
      renderBar(narrationAudioStub({
        track: null,
        error: { message: 'This audio is no longer available.', retryable: false },
      }))

      expect(screen.getByRole('region', { name: 'Narration player' })).toBeInTheDocument()
      expect(screen.getByRole('alert')).toHaveTextContent('This audio is no longer available.')
      // Nothing to play, so no transport at all — just the message and a way out.
      expect(screen.queryByRole('button', { name: 'Play' })).not.toBeInTheDocument()
      expect(screen.queryByRole('slider', { name: 'Seek' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Close the narration player' }))
        .toBeInTheDocument()
    })

    // Out of long-polls is not a failure: the work may well still be running server-side.
    it('offers a manual re-check when polling outran its window', async () => {
      const value = narrationAudioStub({ track: blogTrack, stage: 'narrating', delayed: true })
      renderBar(value)

      expect(screen.getByText(
        'This is taking longer than usual. You can keep browsing and check again.',
      )).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Check audio status' }))
      expect(value.recheck).toHaveBeenCalledOnce()
    })

    it('prefers a real error over the taking-a-while prompt', () => {
      renderBar(narrationAudioStub({
        track: blogTrack,
        delayed: true,
        error: { message: 'Audio could not be prepared.', retryable: true },
      }))

      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Check audio status' }))
        .not.toBeInTheDocument()
    })
  })

  describe('while generating', () => {
    it.each([
      ['summarising', 'Summarising…'],
      ['narrating', 'Preparing audio…'],
    ] as const)('announces the %s stage and offers no transport', (stage, label) => {
      renderBar(narrationAudioStub({ track: newsTrack, stage }))

      const status = screen.getByRole('status')
      expect(status).toHaveTextContent(label)
      expect(status).toHaveAttribute('aria-live', 'polite')
      // Nothing to seek yet; a dead scrubber would be worse than none.
      expect(screen.queryByRole('button', { name: 'Play' })).not.toBeInTheDocument()
      expect(screen.queryByRole('slider', { name: 'Seek' })).not.toBeInTheDocument()
      // Dismiss stays available throughout, and the title still says what is being prepared.
      expect(screen.getByRole('button', { name: 'Close the narration player' }))
        .toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Virtual threads in anger' })).toBeInTheDocument()
    })
  })

  describe('page layout', () => {
    // Fixed to the bottom of the viewport, so it would otherwise sit on top of the footer or
    // the last row of cards.
    it('reserves space at the foot of the page only while it is on screen', () => {
      const { unmount } = renderBar(narrationAudioStub({ track: blogTrack, stage: 'ready' }))

      expect(document.body.hasAttribute('data-narration-bar')).toBe(true)

      unmount()
      expect(document.body.hasAttribute('data-narration-bar')).toBe(false)
    })

    it('reserves nothing when there is no track and nothing to report', () => {
      renderBar(narrationAudioStub())

      expect(document.body.hasAttribute('data-narration-bar')).toBe(false)
    })
  })

  it('is operable by keyboard alone', async () => {
    const value = narrationAudioStub({ track: blogTrack, stage: 'ready', duration: 734 })
    renderBar(value)

    await userEvent.tab()
    expect(screen.getByRole('link', { name: 'Kafka Without Surprises' })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByRole('button', { name: 'Play' })).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    expect(value.togglePlay).toHaveBeenCalledOnce()
  })
})
