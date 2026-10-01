import { describe, expect, it } from 'vitest'

import {
  chaptersToText,
  questionsToText,
  textToChapters,
  textToQuestions,
} from '../../src/pages/admin/portfolioEditorText'

describe('portfolio editor text fields', () => {
  it('round-trips questions, dropping blank lines and outer spaces', () => {
    expect(textToQuestions('  One?\n\n Two? \n')).toEqual(['One?', 'Two?'])
    expect(questionsToText(['One?', 'Two?'])).toBe('One?\nTwo?')
  })

  it('round-trips chapters as m:ss lines', () => {
    const chapters = [{ startSeconds: 0, label: 'Start' }, { startSeconds: 125, label: 'Admin console' }]
    expect(chaptersToText(chapters)).toBe('0:00 Start\n2:05 Admin console')
    expect(textToChapters('0:00 Start\n\n2:05   Admin console ')).toEqual({ ok: true, chapters })
  })

  it('names the first line it cannot read', () => {
    expect(textToChapters('0:00 Start\n1:75 Nope')).toEqual({ ok: false, line: 2 })
    expect(textToChapters('1:05')).toEqual({ ok: false, line: 1 })
  })

  it('reads a long hostile line in linear time', () => {
    const started = performance.now()
    expect(textToChapters(`1:05${' '.repeat(100_000)}`)).toEqual({ ok: false, line: 1 })
    expect(textToChapters(`${'9'.repeat(100_000)}:00 x`)).toEqual({ ok: false, line: 1 })
    expect(performance.now() - started).toBeLessThan(500)
  })
})
