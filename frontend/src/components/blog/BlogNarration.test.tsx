import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  fetchBlogNarrationStatus,
  requestBlogNarration,
} from '../../services/blogApi'
import type { BlogNarrationResponse } from '../../types/blog'
import { BlogNarration } from './BlogNarration'

vi.mock('../../services/blogApi', () => ({
  fetchBlogNarrationStatus: vi.fn(),
  requestBlogNarration: vi.fn(),
}))

const notRequested: BlogNarrationResponse = {
  state: 'NOT_REQUESTED',
  version: 0,
  retryable: false,
  message: 'Narration has not been requested.',
}

const queued: BlogNarrationResponse = {
  state: 'QUEUED',
  version: 1,
  retryable: false,
  message: 'Narration is queued.',
}

const ready: BlogNarrationResponse = {
  state: 'READY',
  version: 3,
  retryable: false,
  message: 'Narration is ready.',
  audioUrl: '/uploads/narrations/n-1/narration.mp3',
  durationSeconds: 372,
}

function renderNarration() {
  return render(<BlogNarration blogId="blog-1" blogTitle="Kafka Without Surprises" />)
}

describe('BlogNarration', () => {
  beforeEach(() => {
    vi.mocked(fetchBlogNarrationStatus).mockReset()
    vi.mocked(requestBlogNarration).mockReset()
  })

  it('offers signed-out visitors an explicitly labelled Listen action', async () => {
    vi.mocked(fetchBlogNarrationStatus).mockResolvedValue(notRequested)

    renderNarration()

    expect(await screen.findByRole('heading', { name: 'Generated narration' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Listen to this post' })).toBeEnabled()
    expect(screen.getByText('Prefer to listen? Generate an audio version of this post.')).toBeInTheDocument()
  })

  it('renders ready native audio controls with an accessible name and never autoplays', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
    vi.mocked(fetchBlogNarrationStatus).mockResolvedValue(ready)

    renderNarration()

    const player = await screen.findByLabelText('Generated narration for Kafka Without Surprises')
    expect(player).toBeInstanceOf(HTMLAudioElement)
    expect(player).toHaveAttribute('controls')
    expect(player).toHaveAttribute('preload', 'metadata')
    expect(player).toHaveAttribute(
      'src',
      expect.stringContaining('/uploads/narrations/n-1/narration.mp3'),
    )
    expect(player).not.toHaveAttribute('autoplay')
    expect(play).not.toHaveBeenCalled()
    expect(screen.getByRole('combobox', { name: 'Playback speed' })).toHaveValue('1')
    expect(screen.getByText('About 6 min')).toBeInTheDocument()

    play.mockRestore()
  })

  it('changes the audio element playback rate from the labelled speed selector', async () => {
    vi.mocked(fetchBlogNarrationStatus).mockResolvedValue(ready)

    renderNarration()

    const player = await screen.findByLabelText('Generated narration for Kafka Without Surprises')
    const speed = screen.getByRole('combobox', { name: 'Playback speed' })

    fireEvent.change(speed, { target: { value: '1.5' } })

    expect(speed).toHaveValue('1.5')
    expect((player as HTMLAudioElement).playbackRate).toBe(1.5)
  })

  it('requests uncached audio and transitions from queued to ready through one long poll', async () => {
    vi.mocked(fetchBlogNarrationStatus)
      .mockResolvedValueOnce(notRequested)
      .mockResolvedValueOnce(ready)
    vi.mocked(requestBlogNarration).mockResolvedValue(queued)

    renderNarration()
    fireEvent.click(await screen.findByRole('button', { name: 'Listen to this post' }))

    expect(await screen.findByLabelText('Generated narration for Kafka Without Surprises')).toBeInTheDocument()
    expect(requestBlogNarration).toHaveBeenCalledWith('blog-1', expect.any(AbortSignal))
    expect(fetchBlogNarrationStatus).toHaveBeenNthCalledWith(2, 'blog-1', {
      afterVersion: 1,
      waitSeconds: 25,
      signal: expect.any(AbortSignal),
    })
  })

  it('stops after four unchanged long polls and offers a manual status check', async () => {
    vi.mocked(fetchBlogNarrationStatus).mockResolvedValue(queued)

    renderNarration()

    expect(await screen.findByText(
      'This is taking longer than usual. You can keep reading and check again.',
    )).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check audio status' })).toBeEnabled()
    expect(fetchBlogNarrationStatus).toHaveBeenCalledTimes(5)
  })

  it('retries a retryable failure and displays the resulting player', async () => {
    const failed: BlogNarrationResponse = {
      state: 'FAILED',
      version: 2,
      retryable: true,
      message: 'Please try again.',
    }
    vi.mocked(fetchBlogNarrationStatus).mockResolvedValue(failed)
    vi.mocked(requestBlogNarration).mockResolvedValue(ready)

    renderNarration()

    expect(await screen.findByRole('alert')).toHaveTextContent('Audio could not be prepared.')
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByLabelText('Generated narration for Kafka Without Surprises')).toBeInTheDocument()
    expect(requestBlogNarration).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['UNAVAILABLE', 'Narration is temporarily unavailable.'],
    ['INELIGIBLE', 'Narration is not available for this post.'],
  ] as const)('renders the %s state without a generation action', async (state, message) => {
    vi.mocked(fetchBlogNarrationStatus).mockResolvedValue({
      state,
      version: 1,
      retryable: false,
      message,
    })

    renderNarration()

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Listen to this post' })).not.toBeInTheDocument()
  })

  it('shows an unavailable response returned directly by the request endpoint', async () => {
    const unavailable: BlogNarrationResponse = {
      state: 'UNAVAILABLE',
      version: 0,
      retryable: false,
      message: 'Narration is temporarily unavailable.',
    }
    vi.mocked(fetchBlogNarrationStatus).mockResolvedValue(notRequested)
    vi.mocked(requestBlogNarration).mockResolvedValue(unavailable)

    renderNarration()
    fireEvent.click(await screen.findByRole('button', { name: 'Listen to this post' }))

    expect(await screen.findByText('Narration is temporarily unavailable.')).toBeInTheDocument()
  })

  it('aborts an outstanding long poll when it unmounts', async () => {
    let longPollSignal: AbortSignal | undefined
    vi.mocked(fetchBlogNarrationStatus)
      .mockResolvedValueOnce(queued)
      .mockImplementationOnce((_blogId, options) => {
        longPollSignal = options?.signal
        return new Promise((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => {
            reject(new DOMException('Aborted', 'AbortError'))
          })
        })
      })

    const { unmount } = renderNarration()
    await waitFor(() => expect(fetchBlogNarrationStatus).toHaveBeenCalledTimes(2))

    unmount()

    expect(longPollSignal?.aborted).toBe(true)
  })
})
