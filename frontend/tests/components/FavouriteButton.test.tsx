import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { FavouriteButton } from '../../src/components/common/FavouriteButton'

describe('FavouriteButton', () => {
  it('renders an unfilled heart when inactive', () => {
    render(<FavouriteButton active={false} label="My Article" onClick={() => {}} />)

    const button = screen.getByRole('button', { name: 'Add My Article to favourites' })
    expect(button).toHaveAttribute('aria-pressed', 'false')
    expect(button.className).not.toContain('favourite-button--active')
    expect(button.querySelector('svg')).toHaveAttribute('fill', 'none')
  })

  it('renders a filled heart when active', () => {
    render(<FavouriteButton active label="My Article" onClick={() => {}} />)

    const button = screen.getByRole('button', { name: 'Remove My Article from favourites' })
    expect(button).toHaveAttribute('aria-pressed', 'true')
    expect(button.className).toContain('favourite-button--active')
    expect(button.querySelector('svg')).toHaveAttribute('fill', 'currentColor')
  })

  it('invokes onClick and suppresses the card link navigation', () => {
    const onClick = vi.fn()
    render(<FavouriteButton active={false} onClick={onClick} />)

    const clickEvent = new MouseEvent('click', { bubbles: true, cancelable: true })
    fireEvent(screen.getByRole('button'), clickEvent)

    expect(onClick).toHaveBeenCalledTimes(1)
    expect(clickEvent.defaultPrevented).toBe(true)
  })

  it('appends a placement className', () => {
    render(<FavouriteButton active={false} className="feed__favourite" onClick={() => {}} />)

    expect(screen.getByRole('button').className).toContain('feed__favourite')
  })
})
