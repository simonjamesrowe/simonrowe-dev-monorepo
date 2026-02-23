import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { BlogGrid } from '../../../src/components/blog/BlogGrid'
import type { BlogSummary } from '../../../src/types/blog'

function makeBlog(id: string, title: string): BlogSummary {
  return {
    id,
    title,
    shortDescription: 'Short',
    createdDate: '2024-01-01T00:00:00Z',
    tags: [],
  }
}

describe('BlogGrid', () => {
  it('renders all blogs', () => {
    const blogs = [makeBlog('1', 'Post 1'), makeBlog('2', 'Post 2'), makeBlog('3', 'Post 3')]

    render(
      <MemoryRouter>
        <BlogGrid blogs={blogs} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Post 1')).toBeInTheDocument()
    expect(screen.getByText('Post 2')).toBeInTheDocument()
    expect(screen.getByText('Post 3')).toBeInTheDocument()
  })

  it('renders empty grid when no blogs provided', () => {
    const { container } = render(
      <MemoryRouter>
        <BlogGrid blogs={[]} />
      </MemoryRouter>,
    )

    expect(container.querySelector('.blog-grid')).toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })
})
