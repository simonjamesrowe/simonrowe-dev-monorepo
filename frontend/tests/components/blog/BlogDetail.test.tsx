import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { BlogDetail } from '../../../src/components/blog/BlogDetail'
import type { BlogDetail as BlogDetailType } from '../../../src/types/blog'

const blog: BlogDetailType = {
  id: 'b-1',
  title: 'My Blog Post',
  shortDescription: 'A short description',
  content: '# Hello\n\nThis is **markdown** content.',
  featuredImageUrl: '/images/blogs/post.jpg',
  createdDate: '2024-06-01T10:00:00Z',
  tags: [{ name: 'Spring' }, { name: 'Java' }],
  skills: [{ id: 's-1', name: 'Spring Boot' }],
}

describe('BlogDetail', () => {
  it('renders title and author', () => {
    render(
      <MemoryRouter>
        <BlogDetail blog={blog} />
      </MemoryRouter>,
    )

    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings[0]).toHaveTextContent('My Blog Post')
    expect(screen.getByText('Simon Rowe')).toBeInTheDocument()
  })

  it('renders tags', () => {
    render(
      <MemoryRouter>
        <BlogDetail blog={blog} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Spring')).toBeInTheDocument()
    expect(screen.getByText('Java')).toBeInTheDocument()
  })

  it('renders related skills', () => {
    render(
      <MemoryRouter>
        <BlogDetail blog={blog} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Spring Boot')).toBeInTheDocument()
  })

  it('renders markdown content', () => {
    render(
      <MemoryRouter>
        <BlogDetail blog={blog} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Hello')).toBeInTheDocument()
  })

  it('uses placeholder when featuredImageUrl is null', () => {
    const noImageBlog = { ...blog, featuredImageUrl: null }
    render(
      <MemoryRouter>
        <BlogDetail blog={noImageBlog} />
      </MemoryRouter>,
    )

    const img = screen.getByRole('img', { name: 'My Blog Post' })
    expect(img).toHaveAttribute('src', '/images/blogs/placeholder.jpg')
  })

  it('renders without tags list when blog has no tags', () => {
    const noTagBlog = { ...blog, tags: [] }
    render(
      <MemoryRouter>
        <BlogDetail blog={noTagBlog} />
      </MemoryRouter>,
    )

    expect(screen.queryByRole('list', { name: 'Tags' })).not.toBeInTheDocument()
  })
})
