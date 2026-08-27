import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { ListenButton } from '../../../src/components/narration/ListenButton'
import { NarrationAudioStub } from '../../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../../testUtils/narrationAudioValue'
import type { NarrationAudioApi } from '../../../src/components/narration/narrationAudioContext'

function renderButton(value: NarrationAudioApi) {
  return render(
    <NarrationAudioStub value={value}>
      <ListenButton
        contentId="blog-1"
        contentType="BLOG"
        href="/blogs/blog-1"
        title="Kafka Without Surprises"
      />
    </NarrationAudioStub>,
  )
}

const readyBlog = {
  'BLOG:blog-1': {
    contentId: 'blog-1',
    audioUrl: '/uploads/narrations/aaa/narration.mp3',
    durationSeconds: 734,
  },
}

describe('ListenButton', () => {
  it('advertises the duration when audio already exists', () => {
    renderButton(narrationAudioStub({ ready: readyBlog }))

    const button = screen.getByRole('button', {
      name: 'Listen to the 12 min audio version of Kafka Without Surprises',
    })
    expect(button).toHaveTextContent('12 min')
    expect(button).toHaveClass('listen-button--ready')
    expect(button).toBeEnabled()
  })

  // Visible on cold cards on purpose: while narrations are sparse, a control that appears on an
  // unpredictable subset of cards reads as broken.
  it('offers a secondary-weight Listen invitation when there is no audio', () => {
    renderButton(narrationAudioStub())

    const button = screen.getByRole('button', {
      name: 'Generate an audio version of Kafka Without Surprises',
    })
    expect(button).toHaveTextContent('Listen')
    expect(button).toHaveClass('listen-button--cold')
  })

  it('asks the provider to listen, passing where the bar should link', async () => {
    const value = narrationAudioStub({ ready: readyBlog })
    renderButton(value)

    await userEvent.click(screen.getByRole('button'))

    expect(value.listen).toHaveBeenCalledWith({
      contentType: 'BLOG',
      contentId: 'blog-1',
      title: 'Kafka Without Surprises',
      href: '/blogs/blog-1',
      external: undefined,
    })
  })

  describe('while generation is in flight for this item', () => {
    it.each([
      ['summarising', 'Summarising…'],
      ['narrating', 'Preparing audio…'],
    ] as const)('shows a spinner and the %s stage label', (stage, label) => {
      renderButton(narrationAudioStub({ stages: { 'BLOG:blog-1': stage } }))

      const button = screen.getByRole('button', {
        name: `${label} for Kafka Without Surprises`,
      })
      expect(button).toHaveTextContent(label)
      expect(button).toHaveClass('listen-button--busy')
      expect(button).toBeDisabled()
      expect(button.querySelector('.listen-button__spinner')).toBeInTheDocument()
    })

    // The in-flight label wins even once the audio lands in the map mid-render, so the button
    // never flickers between states.
    it('takes precedence over a duration that has just arrived', () => {
      renderButton(narrationAudioStub({
        ready: readyBlog,
        stages: { 'BLOG:blog-1': 'narrating' },
      }))

      expect(screen.getByRole('button')).toHaveTextContent('Preparing audio…')
    })

    // Provider state keyed on the content id, never local state: that is what stops a filter
    // change or a "Load more" from losing it.
    it('stays idle for a different item', () => {
      renderButton(narrationAudioStub({ stages: { 'BLOG:blog-2': 'narrating' } }))

      expect(screen.getByRole('button')).toHaveTextContent('Listen')
      expect(screen.getByRole('button')).toBeEnabled()
    })
  })

  /**
   * News cards are anchors wrapping the whole card. Without stopping the event, clicking the
   * button would navigate away to the original article instead of starting playback. Asserted
   * the same way `SummaryButton.test.tsx` does it.
   */
  it('does not let the click reach the surrounding card link', async () => {
    const value = narrationAudioStub({ ready: readyBlog })
    const onCardClick = vi.fn()
    render(
      <NarrationAudioStub value={value}>
        <a href="https://example.com/original" onClick={onCardClick}>
          <ListenButton
            contentId="blog-1"
            contentType="BLOG"
            href="/blogs/blog-1"
            title="Kafka Without Surprises"
          />
        </a>
      </NarrationAudioStub>,
    )

    await userEvent.click(screen.getByRole('button'))

    expect(value.listen).toHaveBeenCalledOnce()
    expect(onCardClick).not.toHaveBeenCalled()
  })
})
