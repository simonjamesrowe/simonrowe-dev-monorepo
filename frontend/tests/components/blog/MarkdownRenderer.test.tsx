import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { MarkdownRenderer } from '../../../src/components/blog/MarkdownRenderer'

const TABLE = [
  '| Override | Boot 4.1.1 manages | Effect if kept |',
  '| --- | --- | --- |',
  '| `commons-lang3` 3.18.0 | 3.20.0 | a silent **downgrade** |',
  '| `jackson-bom` 2.21.5 | 3.1.5 | **broken build** |',
].join('\n')

describe('MarkdownRenderer', () => {
  it('renders a GFM table as a real table with header and body cells', () => {
    render(<MarkdownRenderer content={TABLE} />)

    const table = screen.getByRole('table')
    expect(table.tagName).toBe('TABLE')
    expect(screen.getAllByRole('columnheader').map((c) => c.textContent)).toEqual([
      'Override',
      'Boot 4.1.1 manages',
      'Effect if kept',
    ])
    // Two body rows plus the header row.
    expect(screen.getAllByRole('row')).toHaveLength(3)
    expect(screen.getAllByRole('cell')).toHaveLength(6)
  })

  it('wraps the table in a scroll container, because the table cannot own overflow-x itself', () => {
    const { container } = render(<MarkdownRenderer content={TABLE} />)

    const wrap = container.querySelector('.blog-detail__table-wrap')
    expect(wrap).not.toBeNull()
    // The wrapper's only child must be the table: the wrapper is what scrolls,
    // and `display: block` on the table would stop its cells sizing as a table.
    expect(wrap?.children).toHaveLength(1)
    expect(wrap?.firstElementChild?.tagName).toBe('TABLE')
    expect(container.querySelector('table')?.closest('.blog-detail__table-wrap')).toBe(wrap)
  })

  it('keeps inline formatting inside table cells', () => {
    render(<MarkdownRenderer content={TABLE} />)

    expect(screen.getByText('commons-lang3').tagName).toBe('CODE')
    expect(screen.getByText('downgrade').tagName).toBe('STRONG')
  })

  it('leaves non-table markdown untouched', () => {
    const { container } = render(
      <MarkdownRenderer content={'## Heading\n\nSome *text* with a [link](https://example.com).'} />
    )

    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Heading')
    expect(screen.getByRole('link', { name: 'link' })).toHaveAttribute('href', 'https://example.com')
    expect(container.querySelector('.blog-detail__table-wrap')).toBeNull()
  })
})
