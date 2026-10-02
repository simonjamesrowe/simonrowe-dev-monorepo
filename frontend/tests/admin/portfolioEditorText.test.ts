import { describe, expect, it } from 'vitest'

import {
  besideField,
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

  it('drops the field path from a message shown under that field, and nothing else', () => {
    expect(besideField('pages[0].slug', 'pages[0].slug must be lower-case')).toBe('Must be lower-case')
    expect(besideField('name', 'name is required')).toBe('Is required')
    // A message about some other field, or one that only starts the same way, is left whole.
    expect(besideField('pages[0].slug', 'pages[1].slug is used by another page'))
      .toBe('pages[1].slug is used by another page')
    expect(besideField('name', 'names must differ')).toBe('names must differ')
  })
})
