import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { YearSelector } from '../../src/school/components/YearSelector'

const YEARS = ['Reception', 'Year 1', 'Year 2', 'Year 3', 'Year 4', 'Year 5', 'Year 6']

describe('YearSelector', () => {
  it('offers every year group plus an all-years option', () => {
    render(<YearSelector yearGroups={YEARS} selected={[]} onChange={() => {}} />)

    YEARS.forEach((year) => {
      expect(screen.getByRole('checkbox', { name: year })).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: 'All years' })).toBeInTheDocument()
  })

  it('lets a parent with children in two years select both', () => {
    const onChange = vi.fn()
    render(<YearSelector yearGroups={YEARS} selected={['Year 3']} onChange={onChange} />)

    fireEvent.click(screen.getByRole('checkbox', { name: 'Year 6' }))
    expect(onChange).toHaveBeenCalledWith(['Year 3', 'Year 6'])
  })

  it('returns selections in year order regardless of click order', () => {
    // The order reaches the prompt as "Year 3 and Year 6"; clicking Year 6 first should not
    // produce "Year 6 and Year 3".
    const onChange = vi.fn()
    render(<YearSelector yearGroups={YEARS} selected={['Year 6']} onChange={onChange} />)

    fireEvent.click(screen.getByRole('checkbox', { name: 'Year 3' }))
    expect(onChange).toHaveBeenCalledWith(['Year 3', 'Year 6'])
  })

  it('deselects a year that was already chosen', () => {
    const onChange = vi.fn()
    render(
      <YearSelector yearGroups={YEARS} selected={['Year 3', 'Year 6']} onChange={onChange} />,
    )

    fireEvent.click(screen.getByRole('checkbox', { name: 'Year 3' }))
    expect(onChange).toHaveBeenCalledWith(['Year 6'])
  })

  it('clears the whole selection with All years', () => {
    const onChange = vi.fn()
    render(<YearSelector yearGroups={YEARS} selected={['Year 3']} onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: 'All years' }))
    expect(onChange).toHaveBeenCalledWith([])
  })

  it('marks All years as pressed only when nothing is selected', () => {
    const { rerender } = render(
      <YearSelector yearGroups={YEARS} selected={[]} onChange={() => {}} />,
    )
    expect(screen.getByRole('button', { name: 'All years' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    rerender(<YearSelector yearGroups={YEARS} selected={['Year 2']} onChange={() => {}} />)
    expect(screen.getByRole('button', { name: 'All years' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })
})
