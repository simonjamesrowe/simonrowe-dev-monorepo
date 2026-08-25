import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { NewsSummaryDrawer } from '../../../src/components/news/NewsSummaryDrawer'
import type { ArticleSummaryResponse } from '../../../src/types/articleSummary'
import type { ArticleResponse } from '../../../src/types/news'

const ARTICLE: ArticleResponse = {
  id: 'art-1',
  title: 'Spring Boot 4 Released',
  sourceName: 'InfoQ',
  originalUrl: 'https://infoq.com/spring-boot-4',
  summary: 'Stored blurb.',
  author: 'Jane Doe',
  publishedDate: '2026-08-20T09:00:00Z',
  fetchedAt: '2026-08-20T10:00:00Z',
  visible: true,
  imageUrl: null,
}

const READY: ArticleSummaryResponse = {
  state: 'READY',
  version: 2,
  body: 'First paragraph of the summary.\n\nSecond paragraph of the summary.',
  model: 'test-model',
  retryable: false,
  message: 'Summary ready',
}

function renderDrawer(overrides: Partial<Parameters<typeof NewsSummaryDrawer>[0]> = {}) {
  const onClose = vi.fn()
  const onRetry = vi.fn()
  const onToggleFavourite = vi.fn()
  const utils = render(
    <NewsSummaryDrawer
      article={ARTICLE}
      delayed={false}
      error={null}
      isFavourite={false}
      loading={false}
      onClose={onClose}
      onRetry={onRetry}
      onToggleFavourite={onToggleFavourite}
      summary={READY}
      {...overrides}
    />,
  )
  return { ...utils, onClose, onRetry, onToggleFavourite }
}

describe('NewsSummaryDrawer', () => {
  it('renders the prose', () => {
    renderDrawer()

    expect(screen.getByText('First paragraph of the summary.')).toBeInTheDocument()
    expect(screen.getByText('Second paragraph of the summary.')).toBeInTheDocument()
  })

  it('always discloses that the summary is AI-generated', () => {
    renderDrawer()

    expect(screen.getByText('AI-generated summary')).toBeInTheDocument()
  })

  it('shows the source, the date, the title link and the original link', () => {
    renderDrawer()

    expect(screen.getByText('InfoQ')).toBeInTheDocument()
    expect(screen.getByText('20 Aug 2026')).toBeInTheDocument()

    const titleLink = screen.getByRole('link', { name: 'Spring Boot 4 Released' })
    expect(titleLink).toHaveAttribute('href', 'https://infoq.com/spring-boot-4')

    expect(screen.getByRole('link', { name: /Read the original/ }))
      .toHaveAttribute('href', 'https://infoq.com/spring-boot-4')
  })

  it('renders the favourite control and reports toggles', () => {
    const { onToggleFavourite } = renderDrawer()

    fireEvent.click(
      screen.getByRole('button', { name: /Add Spring Boot 4 Released to favourites/ }))

    expect(onToggleFavourite).toHaveBeenCalledTimes(1)
  })

  it('shows a generating state while the summary is being written', () => {
    renderDrawer({ summary: { state: 'GENERATING', version: 1, retryable: false, message: 'Writing' } })

    expect(screen.getByRole('status')).toHaveTextContent(/Writing the summary/)
  })

  it('shows a manual re-check once polling has given up', () => {
    renderDrawer({
      summary: { state: 'GENERATING', version: 1, retryable: false, message: 'Writing' },
      delayed: true,
    })

    expect(screen.getByRole('status')).toHaveTextContent(/taking longer than usual/)
    expect(screen.getByRole('button', { name: 'Check again' })).toBeInTheDocument()
  })

  it('explains an insufficient-source failure and offers no retry', () => {
    renderDrawer({
      summary: {
        state: 'FAILED',
        version: 2,
        failureCode: 'INSUFFICIENT_SOURCE_TEXT',
        retryable: false,
        message: 'There is not enough of this article available to summarise.',
      },
    })

    expect(screen.getByRole('alert'))
      .toHaveTextContent('There is not enough of this article available to summarise.')
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })

  it('offers a retry for a retryable failure', () => {
    const { onRetry } = renderDrawer({
      summary: {
        state: 'FAILED',
        version: 2,
        failureCode: 'MODEL_ERROR',
        retryable: true,
        message: 'The summary could not be written. Please try again.',
      },
    })

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('shows a client-side error with a retry', () => {
    const { onRetry } = renderDrawer({ error: 'The summary could not be loaded.' })

    expect(screen.getByRole('alert')).toHaveTextContent('The summary could not be loaded.')
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('closes on Escape', () => {
    const { onClose } = renderDrawer()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes on a click outside the panel', () => {
    const { onClose, container } = renderDrawer()

    fireEvent.click(container.querySelector('.drawer-overlay')!)

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('does not close on a click inside the panel', () => {
    const { onClose, container } = renderDrawer()

    fireEvent.click(container.querySelector('.drawer')!)

    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes on the close button', () => {
    const { onClose } = renderDrawer()

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('locks background scrolling while open and restores it on unmount', () => {
    const { unmount } = renderDrawer()

    expect(document.body.style.overflow).toBe('hidden')

    unmount()

    expect(document.body.style.overflow).toBe('')
  })

  /**
   * The summary is model output. The prompt forbids links, so anything link-shaped that
   * does appear is fabricated and must degrade to plain text rather than render as a live
   * destination.
   */
  it('degrades a fabricated external link in the prose to plain text', () => {
    renderDrawer({
      summary: {
        ...READY,
        body: 'See [the source](https://evil.example/steal) for details.',
      },
    })

    expect(screen.getByText(/See the source for details/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'the source' })).not.toBeInTheDocument()
  })

  it('drops an image the model tries to smuggle into the prose', () => {
    const { container } = renderDrawer({
      summary: {
        ...READY,
        body: 'Prose. ![tracker](https://evil.example/pixel.gif)',
      },
    })

    expect(container.querySelector('img')).toBeNull()
  })

  it('renders the audio panel slot when one is supplied', () => {
    renderDrawer({ audioPanel: <div data-testid="audio-panel" /> })

    expect(screen.getByTestId('audio-panel')).toBeInTheDocument()
  })

  /**
   * Closing the drawer unmounts the audio element, which is what stops playback. No extra
   * handling is needed — but if the panel ever moved outside the drawer's tree, playback
   * would carry on with no visible controls, so this pins it.
   */
  it('unmounts the audio element when the drawer closes', () => {
    const { container, unmount } = renderDrawer({
      audioPanel: <audio data-testid="summary-audio" />,
    })

    expect(container.querySelector('audio')).not.toBeNull()

    unmount()

    expect(document.querySelector('[data-testid="summary-audio"]')).toBeNull()
  })

  it('keeps the prose readable when audio is unavailable', () => {
    renderDrawer({
      audioPanel: <p>Narration is temporarily unavailable.</p>,
    })

    expect(screen.getByText('Narration is temporarily unavailable.')).toBeInTheDocument()
    expect(screen.getByText('First paragraph of the summary.')).toBeInTheDocument()
  })

  it('omits the audio panel entirely while the summary is still generating', () => {
    const { container } = renderDrawer({
      summary: { state: 'GENERATING', version: 1, retryable: false, message: 'Writing' },
    })

    expect(container.querySelector('audio')).toBeNull()
  })
})
