import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { NarrationPanel } from '../../../src/components/narration/NarrationPanel'
import type { NarrationState } from '../../../src/components/narration/useNarration'
import type { BlogNarrationResponse } from '../../../src/types/blog'

function state(narration: BlogNarrationResponse | null): NarrationState {
  return {
    narration,
    checking: false,
    requesting: false,
    delayed: false,
    clientError: null,
    requestNarration: vi.fn(),
    checkStatus: vi.fn(),
    recheck: vi.fn(),
  }
}

function renderPanel(narration: BlogNarrationResponse | null) {
  return render(
    <NarrationPanel domId="panel" state={state(narration)} subject="the summary" />)
}

describe('NarrationPanel', () => {
  /**
   * A reader who declines the sign-in popup gets UNAVAILABLE carrying an explanation.
   * Rendering the generic wording instead would blame the service for their own choice.
   */
  it('shows the response message rather than a generic unavailable line', () => {
    renderPanel({
      state: 'UNAVAILABLE',
      version: 0,
      retryable: false,
      message: 'Sign in to generate audio for this summary.',
    })

    expect(screen.getByRole('status'))
      .toHaveTextContent('Sign in to generate audio for this summary.')
    expect(screen.queryByText(/temporarily unavailable/)).not.toBeInTheDocument()
  })

  it('falls back to the generic line when no message is supplied', () => {
    renderPanel({
      state: 'UNAVAILABLE', version: 0, retryable: false, message: '',
    })

    expect(screen.getByRole('status'))
      .toHaveTextContent('Narration is temporarily unavailable.')
  })

  it('falls back to the ineligible line for that state', () => {
    renderPanel({
      state: 'INELIGIBLE', version: 0, retryable: false, message: '   ',
    })

    expect(screen.getByRole('status'))
      .toHaveTextContent('Narration is not available for this post.')
  })

  it('offers generation when nothing has been requested', () => {
    renderPanel({
      state: 'NOT_REQUESTED', version: 0, retryable: false, message: 'Listen',
    })

    expect(screen.getByRole('button', { name: 'Listen to this post' }))
      .toBeInTheDocument()
  })

  it('renders a player with a playback-speed control when ready', () => {
    renderPanel({
      state: 'READY',
      version: 2,
      audioUrl: '/uploads/narrations/id/narration.mp3',
      durationSeconds: 990,
      retryable: false,
      message: 'Ready',
    })

    expect(screen.getByLabelText('Generated narration for the summary')).toBeInTheDocument()
    expect(screen.getByLabelText(/Playback speed/)).toBeInTheDocument()
    expect(screen.getByText('About 17 min')).toBeInTheDocument()
  })
})
