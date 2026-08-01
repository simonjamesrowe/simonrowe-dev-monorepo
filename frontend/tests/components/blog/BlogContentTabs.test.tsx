import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { BlogContentTabs } from '../../../src/components/blog/BlogContentTabs'

describe('BlogContentTabs', () => {
  it('renders three tabs inside a tablist', () => {
    render(<BlogContentTabs active="ENGINEERING" onChange={vi.fn()} />)

    expect(screen.getByRole('tablist')).toBeInTheDocument()
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual([
      'All',
      'Engineering',
      'Weekly Digest',
    ])
  })

  it('marks only the active tab as selected', () => {
    render(<BlogContentTabs active="ENGINEERING" onChange={vi.fn()} />)

    expect(screen.getByRole('tab', { name: 'Engineering' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByRole('tab', { name: 'Weekly Digest' })).toHaveAttribute(
      'aria-selected',
      'false',
    )
  })

  it('styles the active tab with the shared chip classes', () => {
    render(<BlogContentTabs active="DIGEST" onChange={vi.fn()} />)

    expect(screen.getByRole('tab', { name: 'Weekly Digest' })).toHaveClass('chip', 'chip--active')
    expect(screen.getByRole('tab', { name: 'Engineering' })).not.toHaveClass('chip--active')
  })

  it('reports the selected content type on click', () => {
    const onChange = vi.fn()
    render(<BlogContentTabs active="ENGINEERING" onChange={onChange} />)

    fireEvent.click(screen.getByRole('tab', { name: 'Weekly Digest' }))
    expect(onChange).toHaveBeenCalledWith('DIGEST')

    fireEvent.click(screen.getByRole('tab', { name: 'All' }))
    expect(onChange).toHaveBeenCalledWith('ALL')

    fireEvent.click(screen.getByRole('tab', { name: 'Engineering' }))
    expect(onChange).toHaveBeenCalledWith('ENGINEERING')
  })
})
