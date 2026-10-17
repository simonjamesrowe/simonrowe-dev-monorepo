import { fireEvent, render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { closeOnBackdropClick } from '../../src/utils/backdropClick'

function Modal({ onClose }: { onClose: () => void }) {
  return (
    <div className="backdrop" onClick={closeOnBackdropClick(onClose)} role="presentation">
      <div className="panel">
        <button type="button">Inside</button>
      </div>
    </div>
  )
}

describe('closeOnBackdropClick', () => {
  it('closes on a click on the backdrop itself', () => {
    const onClose = vi.fn()
    const { container } = render(<Modal onClose={onClose} />)

    fireEvent.click(container.querySelector('.backdrop')!)

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('ignores clicks that bubble up from inside the panel', () => {
    const onClose = vi.fn()
    const { container, getByRole } = render(<Modal onClose={onClose} />)

    fireEvent.click(container.querySelector('.panel')!)
    fireEvent.click(getByRole('button', { name: 'Inside' }))

    expect(onClose).not.toHaveBeenCalled()
  })

  it('does not close an outer modal when a nested one is dismissed', () => {
    const outer = vi.fn()
    const inner = vi.fn()
    const { container } = render(
      <div className="outer" onClick={closeOnBackdropClick(outer)} role="presentation">
        <Modal onClose={inner} />
      </div>,
    )

    fireEvent.click(container.querySelector('.backdrop')!)

    expect(inner).toHaveBeenCalledTimes(1)
    expect(outer).not.toHaveBeenCalled()
  })
})
