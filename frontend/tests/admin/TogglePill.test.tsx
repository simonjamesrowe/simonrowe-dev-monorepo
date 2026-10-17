import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { TogglePill } from '../../src/components/admin/TogglePill'

describe('TogglePill', () => {
  it('is a switch named by the label showing the current state', () => {
    render(<TogglePill checked={false} onChange={vi.fn()} />)

    const toggle = screen.getByRole('switch', { name: 'Draft' })
    expect(toggle).not.toBeChecked()
  })

  it('reports the flipped state on click', () => {
    const onChange = vi.fn()
    render(<TogglePill checked onChange={onChange} />)

    fireEvent.click(screen.getByRole('switch', { name: 'Published' }))

    expect(onChange).toHaveBeenCalledWith(false)
  })
})
