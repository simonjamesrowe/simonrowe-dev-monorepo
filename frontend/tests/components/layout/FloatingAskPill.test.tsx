import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { FloatingAskPill } from '../../../src/components/layout/FloatingAskPill'

const openChat = vi.fn()
vi.mock('../../../src/contexts/ChatContext', () => ({ useChat: () => ({ openChat }) }))

function renderPill(anchorBottom?: number) {
  return render(
    <MemoryRouter>
      {anchorBottom === undefined ? null : (
        <div
          data-ask-anchor
          ref={element => {
            if (element) {
              element.getBoundingClientRect = () => ({ bottom: anchorBottom } as DOMRect)
            }
          }}
        />
      )}
      <FloatingAskPill />
    </MemoryRouter>,
  )
}

const pill = () => document.querySelector('.ask-pill')!

describe('FloatingAskPill', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    openChat.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows on a page with no Ask anchor, but only once the page has had a moment to load', () => {
    renderPill()
    expect(pill()).not.toHaveClass('ask-pill--visible')
    act(() => { vi.advanceTimersByTime(700) })
    expect(pill()).toHaveClass('ask-pill--visible')
  })

  it('stays hidden while the page’s own Ask anchor is on screen', () => {
    renderPill(400)
    act(() => { vi.advanceTimersByTime(700) })
    expect(pill()).not.toHaveClass('ask-pill--visible')
    expect(pill()).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByRole('button', { hidden: true })).toHaveAttribute('tabindex', '-1')
  })

  it('shows once the anchor has scrolled above the viewport', () => {
    renderPill(-10)
    act(() => {
      fireEvent.scroll(window)
    })
    expect(pill()).toHaveClass('ask-pill--visible')
  })

  it('opens the chat', () => {
    renderPill()
    act(() => { vi.advanceTimersByTime(700) })
    fireEvent.click(screen.getByRole('button', { name: 'Ask Simon anything' }))
    expect(openChat).toHaveBeenCalledWith()
  })
})
