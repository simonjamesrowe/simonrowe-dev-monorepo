import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { SkillRatingBar, skillLevel } from '../../../src/components/skills/SkillRatingBar'

/**
 * Boundaries are expressed as decimals on purpose: live skill-group ratings are
 * decimals (9.5, 8.6, 8.3, 8.0, 7.6, 7.3, 7.2, 6.9), so an integer-set banding would
 * leave 8.6 and 6.9 unclassified.
 */
const BOUNDARIES: Array<[number, string]> = [
  [10, 'Expert'],
  [9, 'Expert'],
  [8.9, 'Advanced'],
  [8.6, 'Advanced'],
  [7, 'Advanced'],
  [6.9, 'Proficient'],
  [5, 'Proficient'],
  [4.9, 'Familiar'],
  [0, 'Familiar'],
]

describe('skillLevel', () => {
  it.each(BOUNDARIES)('maps %s to %s', (rating, expected) => {
    expect(skillLevel(rating)).toBe(expected)
  })
})

describe('SkillRatingBar', () => {
  it.each(BOUNDARIES)('renders the level word %s -> %s beside the bar', (rating, expected) => {
    render(<SkillRatingBar rating={rating} skillName="Kafka" />)

    expect(screen.getByText(expected)).toBeInTheDocument()
    expect(document.querySelector('.skill-rating-bar__level')).toHaveTextContent(expected)
  })

  it.each(BOUNDARIES)(
    'announces the same level word as it displays for %s (FR-028)',
    (rating, expected) => {
      render(<SkillRatingBar rating={rating} skillName="Kafka" />)

      const bar = screen.getByRole('progressbar')
      expect(bar).toHaveAttribute('aria-label', `Kafka proficiency: ${expected} (${rating} out of 10)`)
      expect(bar).toHaveAttribute('aria-valuenow', String(rating))
      expect(document.querySelector('.skill-rating-bar__level')).toHaveTextContent(expected)
    },
  )

  it('keeps the level word outside the progressbar element (valid ARIA)', () => {
    render(<SkillRatingBar rating={8.6} skillName="Kafka" />)

    const bar = screen.getByRole('progressbar')
    expect(bar.querySelector('.skill-rating-bar__level')).toBeNull()
    expect(bar.textContent).toBe('')
    expect(document.querySelector('.skill-rating-bar__level')).not.toBeNull()
  })

  it('sets only the width inline, leaving the fill colour to CSS', () => {
    render(<SkillRatingBar rating={8} skillName="Kafka" />)

    const fill = document.querySelector<HTMLElement>('.skill-rating-bar__fill')
    expect(fill).not.toBeNull()
    expect(fill!.style.width).toBe('80%')
    expect(fill!.style.backgroundColor).toBe('')
  })

  it('falls back to a Familiar zero bar for a null rating', () => {
    render(<SkillRatingBar rating={null} skillName="Kafka" />)

    expect(screen.getByText('Familiar')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
  })

  it('falls back to a Familiar zero bar for an undefined rating', () => {
    render(<SkillRatingBar rating={undefined} skillName="Kafka" />)

    expect(screen.getByText('Familiar')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
  })

  it.each([-1, 11, 99.9, Number.NaN])('clamps the out-of-range rating %s to 0', (rating) => {
    render(<SkillRatingBar rating={rating} skillName="Kafka" />)

    expect(screen.getByText('Familiar')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
    expect(document.querySelector<HTMLElement>('.skill-rating-bar__fill')!.style.width).toBe('0%')
  })

  it('labels the bar generically when no skill name is supplied', () => {
    render(<SkillRatingBar rating={7.3} />)

    expect(screen.getByRole('progressbar')).toHaveAttribute(
      'aria-label',
      'Skill proficiency: Advanced (7.3 out of 10)',
    )
  })
})
