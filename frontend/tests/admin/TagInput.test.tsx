import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { TagInput } from '../../src/components/admin/TagInput'

const options = [
  { id: 'react', name: 'React' },
  { id: 'java', name: 'Java' },
]

function renderLabelled(onChange = vi.fn()) {
  render(
    <>
      <label htmlFor="tags">Tags</label>
      <TagInput id="tags" options={options} selected={[]} onChange={onChange} />
    </>,
  )
  return onChange
}

describe('TagInput', () => {
  it('is named by a label pointing at its id', () => {
    renderLabelled()

    expect(screen.getByRole('combobox', { name: 'Tags' })).toBeInTheDocument()
  })

  it('points aria-activedescendant at the highlighted option', () => {
    renderLabelled()
    const input = screen.getByRole('combobox', { name: 'Tags' })

    fireEvent.focus(input)
    expect(input).not.toHaveAttribute('aria-activedescendant')

    fireEvent.keyDown(input, { key: 'ArrowDown' })
    const highlighted = screen.getByRole('option', { name: 'React' })
    expect(input).toHaveAttribute('aria-activedescendant', highlighted.id)
    expect(input).toHaveAttribute('aria-controls', screen.getByRole('listbox').id)
    expect(highlighted).toHaveAttribute('aria-selected', 'true')
  })

  it('selects an option on click', () => {
    const onChange = renderLabelled()
    fireEvent.focus(screen.getByRole('combobox', { name: 'Tags' }))

    fireEvent.click(screen.getByRole('option', { name: 'Java' }))

    expect(onChange).toHaveBeenCalledWith(['java'])
  })
})
