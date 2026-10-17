import { describe, expect, it } from 'vitest'
import type { Paragraph, Root } from 'mdast'
import { remarkLinkify, trimTrailingPunctuation } from '../../../src/components/chat/remarkLinkify'

function linkify(text: string): Paragraph['children'] {
  const paragraph: Paragraph = { type: 'paragraph', children: [{ type: 'text', value: text }] }
  const tree: Root = { type: 'root', children: [paragraph] }
  remarkLinkify()(tree)
  return paragraph.children
}

describe('trimTrailingPunctuation', () => {
  it('drops a trailing run of sentence punctuation', () => {
    expect(trimTrailingPunctuation('https://example.com/a.,;:')).toBe('https://example.com/a')
  })

  it('keeps punctuation that is not at the end', () => {
    expect(trimTrailingPunctuation('https://example.com/a.b')).toBe('https://example.com/a.b')
  })

  it('returns an all-punctuation string as empty', () => {
    expect(trimTrailingPunctuation('...')).toBe('')
  })
})

describe('remarkLinkify', () => {
  it('links a bare external URL and leaves the full stop after it as text', () => {
    expect(linkify('See https://example.com/page. Thanks')).toEqual([
      { type: 'text', value: 'See ' },
      {
        type: 'link',
        url: 'https://example.com/page',
        children: [{ type: 'text', value: 'https://example.com/page' }],
      },
      { type: 'text', value: '. Thanks' },
    ])
  })

  it('links a bare internal path with its query', () => {
    expect(linkify('Try /about?job=abc, then')).toEqual([
      { type: 'text', value: 'Try ' },
      { type: 'link', url: '/about?job=abc', children: [{ type: 'text', value: '/about?job=abc' }] },
      { type: 'text', value: ', then' },
    ])
  })

  it('leaves text with no URL alone', () => {
    expect(linkify('nothing to see')).toEqual([{ type: 'text', value: 'nothing to see' }])
  })

  it('stays linear on a long punctuation run that does not end the URL', () => {
    // The shape that made `/[.,;:]+$/` quadratic: a long run of trailing-punctuation
    // characters inside the match, followed by one character that is not.
    const url = `https://example.com/${'.'.repeat(150_000)}a`
    const started = performance.now()
    const children = linkify(`x ${url} y`)
    const elapsed = performance.now() - started

    expect(elapsed).toBeLessThan(200)
    expect(children[1]).toMatchObject({ type: 'link', url })
  })
})
