import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ShareButton } from '../../src/components/common/ShareButton'

const URL = 'https://simonrowe.dev/s/exactly-once'
const TITLE = 'Exactly-once semantics'

/**
 * jsdom has neither `navigator.share` nor `navigator.clipboard`, so each tier has to be
 * installed explicitly. That is also why the component detects at click time rather than at
 * render — detecting at render would make every one of these tests exercise the fallback.
 */
function withShare(impl: (data: ShareData) => Promise<void>) {
  Object.defineProperty(navigator, 'share', {
    value: vi.fn(impl),
    configurable: true,
    writable: true,
  })
}

function withClipboard(impl: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText: vi.fn(impl) },
    configurable: true,
    writable: true,
  })
}

function withoutShareOrClipboard() {
  Object.defineProperty(navigator, 'share', { value: undefined, configurable: true })
  Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
}

afterEach(() => {
  withoutShareOrClipboard()
  vi.restoreAllMocks()
})

describe('ShareButton', () => {
  describe('native share sheet', () => {
    it('opens the OS sheet with the title and the url when one is available', async () => {
      withShare(() => Promise.resolve())
      render(<ShareButton title={TITLE} url={URL} />)

      fireEvent.click(screen.getByRole('button', { name: `Share ${TITLE}` }))

      await waitFor(() =>
        expect(navigator.share).toHaveBeenCalledWith({ title: TITLE, url: URL }))
    })

    it('shows no confirmation after a native share, because the OS already did', async () => {
      withShare(() => Promise.resolve())
      render(<ShareButton title={TITLE} url={URL} />)

      fireEvent.click(screen.getByRole('button'))

      await waitFor(() => expect(navigator.share).toHaveBeenCalled())
      expect(screen.queryByText('Copied')).not.toBeInTheDocument()
    })

    it('reports nothing when the visitor dismisses the sheet', async () => {
      // Cancelling is a person changing their mind, not a failure.
      const abort = Object.assign(new Error('cancelled'), { name: 'AbortError' })
      withShare(() => Promise.reject(abort))
      withClipboard(() => Promise.resolve())
      render(<ShareButton title={TITLE} url={URL} />)

      fireEvent.click(screen.getByRole('button'))

      await waitFor(() => expect(navigator.share).toHaveBeenCalled())
      // And it does not silently fall through to copying instead — the visitor said no.
      expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
      expect(screen.queryByText('Copied')).not.toBeInTheDocument()
    })

    it('falls back to the clipboard when the sheet errors for any other reason', async () => {
      withShare(() => Promise.reject(new Error('not allowed')))
      withClipboard(() => Promise.resolve())
      render(<ShareButton title={TITLE} url={URL} />)

      fireEvent.click(screen.getByRole('button'))

      await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith(URL))
      expect(await screen.findByText('Copied')).toBeInTheDocument()
    })
  })

  describe('clipboard', () => {
    beforeEach(() => {
      Object.defineProperty(navigator, 'share', { value: undefined, configurable: true })
    })

    it('copies the url and confirms', async () => {
      withClipboard(() => Promise.resolve())
      render(<ShareButton title={TITLE} url={URL} />)

      fireEvent.click(screen.getByRole('button', { name: `Share ${TITLE}` }))

      await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith(URL))
      expect(await screen.findByText('Copied')).toBeInTheDocument()
      expect(screen.getByRole('button')).toHaveAccessibleName(`Link to ${TITLE} copied`)
    })

    it('reverts to Share after two seconds', async () => {
      vi.useFakeTimers()
      try {
        withClipboard(() => Promise.resolve())
        render(<ShareButton title={TITLE} url={URL} />)

        fireEvent.click(screen.getByRole('button'))
        await act(async () => {})
        expect(screen.getByText('Copied')).toBeInTheDocument()

        await act(async () => {
          vi.advanceTimersByTime(2000)
        })
        expect(screen.getByText('Share')).toBeInTheDocument()
      } finally {
        vi.useRealTimers()
      }
    })
  })

  describe('non-secure context', () => {
    it('uses the execCommand fallback when the clipboard api is absent', async () => {
      // Realistically only local development over plain HTTP. Kept so the control is never
      // simply dead there.
      withoutShareOrClipboard()
      const execCommand = vi.fn(() => true)
      Object.defineProperty(document, 'execCommand', {
        value: execCommand,
        configurable: true,
        writable: true,
      })
      render(<ShareButton title={TITLE} url={URL} />)

      fireEvent.click(screen.getByRole('button'))

      await waitFor(() => expect(execCommand).toHaveBeenCalledWith('copy'))
      expect(await screen.findByText('Copied')).toBeInTheDocument()
      // The temporary textarea must not be left in the document.
      expect(document.querySelectorAll('textarea')).toHaveLength(0)
    })

    it('does not claim to have copied when the fallback fails', async () => {
      withoutShareOrClipboard()
      Object.defineProperty(document, 'execCommand', {
        value: vi.fn(() => false),
        configurable: true,
        writable: true,
      })
      render(<ShareButton title={TITLE} url={URL} />)

      fireEvent.click(screen.getByRole('button'))

      await act(async () => {})
      expect(screen.queryByText('Copied')).not.toBeInTheDocument()
    })
  })

  describe('inside a card', () => {
    it('does not navigate the anchor it sits in', async () => {
      // News and blog cards are <a> elements wrapping their action row, so without
      // preventDefault/stopPropagation pressing Share opens the article instead.
      withClipboard(() => Promise.resolve())
      const onAnchorClick = vi.fn((e: React.MouseEvent) => e.preventDefault())
      render(
        <a href="https://example.com/article" onClick={onAnchorClick}>
          <ShareButton title={TITLE} url={URL} />
        </a>,
      )

      fireEvent.click(screen.getByRole('button'))

      await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalled())
      expect(onAnchorClick).not.toHaveBeenCalled()
    })
  })

  describe('presentation', () => {
    it('hides the label when asked, for rows that are crowded at mobile width', () => {
      render(<ShareButton iconOnly title={TITLE} url={URL} />)

      expect(screen.queryByText('Share')).not.toBeInTheDocument()
      // The accessible name still carries it, so the control is not unlabelled.
      expect(screen.getByRole('button')).toHaveAccessibleName(`Share ${TITLE}`)
    })
  })
})
