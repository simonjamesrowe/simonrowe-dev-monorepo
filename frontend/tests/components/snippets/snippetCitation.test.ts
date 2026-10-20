import { describe, expect, it } from 'vitest'

import {
  citation,
  exportMarkdown,
  normaliseTag,
} from '../../../src/components/snippets/snippetCitation'

const SNIPPET = {
  exact: 'Requirements and UAT remain human responsibilities.',
  note: 'For the "review the what" slide',
  articleTitle: 'Human PR Review Is Next',
  sourceName: 'TLDR Dev',
  author: 'William Martin',
  originalUrl: 'https://example.com/pr',
  articlePublishedAt: '2026-10-12T20:37:19Z',
}

describe('citation', () => {
  it('says the quote is from an AI summary and links the original article', () => {
    expect(citation(SNIPPET)).toBe([
      '> Requirements and UAT remain human responsibilities.',
      '',
      '— AI summary of "Human PR Review Is Next" (William Martin, TLDR Dev, 12 Oct 2026)',
      '  https://example.com/pr',
      '',
      'Note: For the "review the what" slide',
    ].join('\n'))
  })

  it('keeps paragraph breaks inside the blockquote and leaves out what is missing', () => {
    expect(citation({ ...SNIPPET, exact: 'one.\n\ntwo.', note: '  ', author: null, articlePublishedAt: null }))
      .toBe([
        '> one.',
        '>',
        '> two.',
        '',
        '— AI summary of "Human PR Review Is Next" (TLDR Dev)',
        '  https://example.com/pr',
      ].join('\n'))
  })
})

const POST_SNIPPET = {
  sourceType: 'BLOG' as const,
  exact: 'Verification is the bottleneck now.',
  articleTitle: 'Measuring Engineering',
  sourceName: 'simonrowe.dev',
  author: 'Simon Rowe',
  originalUrl: 'https://simonrowe.dev/blogs/b-1',
  articlePublishedAt: '2024-06-01T10:00:00Z',
}

describe('citation of a blog post', () => {
  it('cites the post as Simon\'s writing and links it, never as an AI summary', () => {
    expect(citation(POST_SNIPPET)).toBe([
      '> Verification is the bottleneck now.',
      '',
      '— Simon Rowe, "Measuring Engineering" (simonrowe.dev, 1 Jun 2024)',
      '  https://simonrowe.dev/blogs/b-1',
    ].join('\n'))
  })
})

describe('exportMarkdown', () => {
  it('only says quotes are AI-written when some are, and says which', () => {
    const when = new Date('2026-10-18T12:00:00Z')

    expect(exportMarkdown([POST_SNIPPET], [], when)).toContain('_Exported 18 Oct 2026._\n')
    expect(exportMarkdown([POST_SNIPPET, SNIPPET], [], when)).toContain(
      'Quotes cited as "AI summary of" are from AI-generated summaries, not the original authors._')
  })

  it('heads the export with the tags and separates snippets', () => {
    const markdown = exportMarkdown([SNIPPET, { ...SNIPPET, exact: 'Second.', note: '' }],
      ['platform-eng-talk'], new Date('2026-10-18T12:00:00Z'))

    expect(markdown.startsWith('# Snippets — #platform-eng-talk\n\n_Exported 18 Oct 2026.')).toBe(true)
    expect(markdown).toContain('not the original authors._')
    expect(markdown.split('\n---\n')).toHaveLength(2)
  })
})

describe('normaliseTag', () => {
  it.each([
    ['Platform Eng Talk', 'platform-eng-talk'],
    ['  ##Metrics ', 'metrics'],
    ['#', ''],
  ])('%s becomes %s, as on the server', (raw, expected) => {
    expect(normaliseTag(raw)).toBe(expected)
  })
})
