import { describe, expect, it } from 'vitest'

import {
  formatApproximateDuration,
  formatCompactDuration,
} from '../../../src/components/narration/formatDuration'

describe('narration duration formatting', () => {
  it('rounds to the nearest minute for the detail-page player', () => {
    expect(formatApproximateDuration(734)).toBe('About 12 min')
    expect(formatApproximateDuration(412)).toBe('About 7 min')
  })

  it('rounds to the nearest minute for the card', () => {
    expect(formatCompactDuration(734)).toBe('12 min')
    expect(formatCompactDuration(412)).toBe('7 min')
  })

  it('never reports zero minutes for very short audio', () => {
    expect(formatApproximateDuration(20)).toBe('About 1 min')
    expect(formatCompactDuration(20)).toBe('1 min')
    expect(formatCompactDuration(0)).toBe('1 min')
  })

  // The whole reason the module exists: the two surfaces word it differently but the number
  // behind them is the same, so a card and the detail page can never disagree.
  it.each([0, 1, 29, 30, 59, 60, 89, 90, 599, 734, 3600])(
    'agrees on the minute count for %i seconds',
    (seconds) => {
      const compact = formatCompactDuration(seconds)
      expect(formatApproximateDuration(seconds)).toBe(`About ${compact}`)
    },
  )
})
