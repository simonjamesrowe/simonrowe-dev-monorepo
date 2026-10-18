import { render } from '@testing-library/react'
import ReactMarkdown from 'react-markdown'
import { describe, expect, it } from 'vitest'

import {
  CONTEXT_LENGTH,
  locate,
  offsetOf,
  quoteFromRange,
  rangeFor,
  readProse,
} from '../../../../src/components/news/snippets/snippetText'

const BODY = [
  'Martin says engineers should **review what it is required** to do.',
  '',
  'He lists the failure modes:',
  '',
  '- duplication and drift,',
  '- weak or fabricated tests.',
  '',
  'Requirements and UAT remain human responsibilities.',
].join('\n')

/** The summary rendered exactly as the drawer renders it, inside a heading it must ignore. */
function renderSummary() {
  const { container } = render(
    <div>
      <h2>Title outside the prose</h2>
      <div data-testid="prose">
        <ReactMarkdown>{BODY}</ReactMarkdown>
      </div>
    </div>,
  )
  return {
    prose: container.querySelector('[data-testid="prose"]') as HTMLElement,
    title: container.querySelector('h2') as HTMLElement,
  }
}

function textNodeContaining(root: Node, fragment: string): Text {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if ((node as Text).data.includes(fragment)) return node as Text
  }
  throw new Error(`No text node contains "${fragment}"`)
}

function rangeBetween(root: Node, from: string, to: string): Range {
  const start = textNodeContaining(root, from)
  const end = textNodeContaining(root, to)
  const range = document.createRange()
  range.setStart(start, start.data.indexOf(from))
  range.setEnd(end, end.data.indexOf(to) + to.length)
  return range
}

describe('readProse', () => {
  it('joins blocks with a blank line and drops the markup between them', () => {
    const { prose } = renderSummary()

    expect(readProse(prose).text).toBe([
      'Martin says engineers should review what it is required to do.',
      'He lists the failure modes:',
      'duplication and drift,',
      'weak or fabricated tests.',
      'Requirements and UAT remain human responsibilities.',
    ].join('\n\n'))
  })
})

describe('quoteFromRange', () => {
  it('keeps text across emphasis inside one paragraph', () => {
    const { prose } = renderSummary()

    const quote = quoteFromRange(readProse(prose), rangeBetween(prose, 'engineers', 'required'))

    expect(quote?.exact).toBe('engineers should review what it is required')
    expect(quote?.prefix).toBe('Martin says ')
    expect(quote?.suffix.startsWith(' to do.')).toBe(true)
  })

  it('keeps a paragraph break as a blank line when the selection crosses blocks', () => {
    const { prose } = renderSummary()

    const quote = quoteFromRange(readProse(prose), rangeBetween(prose, 'drift,', 'weak'))

    expect(quote?.exact).toBe('drift,\n\nweak')
  })

  it('clips a selection that starts outside the prose to the prose', () => {
    const { prose, title } = renderSummary()
    const range = document.createRange()
    range.setStart(textNodeContaining(title, 'Title'), 0)
    const end = textNodeContaining(prose, 'Martin says')
    range.setEnd(end, 'Martin says'.length)

    expect(quoteFromRange(readProse(prose), range)?.exact).toBe('Martin says')
  })

  it('is null when nothing in the prose is selected', () => {
    const { prose, title } = renderSummary()
    const range = document.createRange()
    range.selectNodeContents(title)

    expect(quoteFromRange(readProse(prose), range)).toBeNull()
  })

  it('takes at most the context length either side', () => {
    const { prose } = renderSummary()

    const quote = quoteFromRange(readProse(prose), rangeBetween(prose, 'Requirements', 'UAT'))

    expect(quote?.prefix.length).toBe(CONTEXT_LENGTH)
    expect(quote?.prefix.endsWith('tests.\n\n')).toBe(true)
  })
})

describe('locate and rangeFor', () => {
  it('finds a saved quote again and maps it back onto the same text', () => {
    const { prose } = renderSummary()
    const text = readProse(prose)
    const quote = quoteFromRange(text, rangeBetween(prose, 'drift,', 'fabricated'))!

    const start = locate(text.text, quote)
    const range = rangeFor(text, start, start + quote.exact.length)

    expect(start).toBe(quote.start)
    // The range runs from "drift," to "fabricated"; toString() also picks up the newline
    // react-markdown leaves between the list items, which readProse skips.
    expect(range?.toString().replace(/\s+/g, ' ')).toBe('drift, weak or fabricated')
    expect((range?.startContainer as Text).data).toBe('duplication and drift,')
  })

  it('tells two identical phrases apart by the text around them', () => {
    const text = 'the cat sat. Later the cat ran.'
    const second = { exact: 'the cat', prefix: 'Later ', suffix: ' ran.' }

    expect(locate(text, second)).toBe(text.lastIndexOf('the cat'))
  })

  it('falls back to the quote alone when the surrounding text changed', () => {
    expect(locate('a new intro, then the cat ran.', { exact: 'the cat', prefix: 'Later ', suffix: '' }))
      .toBe('a new intro, then '.length)
  })

  it('reports a quote that is no longer there', () => {
    expect(locate('nothing here', { exact: 'the cat', prefix: '', suffix: '' })).toBe(-1)
  })

  it('maps a text position to its offset in the prose', () => {
    const { prose } = renderSummary()
    const text = readProse(prose)
    const node = textNodeContaining(prose, 'Requirements')

    expect(offsetOf(text, node, 0)).toBe(text.text.indexOf('Requirements'))
    expect(offsetOf(text, document.body, 0)).toBe(-1)
  })
})
