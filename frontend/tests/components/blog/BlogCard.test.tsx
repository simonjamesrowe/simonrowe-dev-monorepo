import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { BlogCard } from '../../../src/components/blog/BlogCard'
import type { BlogSummary } from '../../../src/types/blog'

const blog: BlogSummary = {
  id: 'b-1',
  title: 'My Blog Post',
  shortDescription: 'A short description',
  featuredImageUrl: '/images/blogs/post.jpg',
  createdDate: '2024-06-01T10:00:00Z',
  tags: [{ name: 'Kubernetes' }, { name: 'Docker' }],
}

describe('BlogCard', () => {
  it('renders card with title and description', () => {
    render(
      <MemoryRouter>
        <BlogCard blog={blog} imagePosition="left" />
      </MemoryRouter>,
    )

    expect(screen.getByText('My Blog Post')).toBeInTheDocument()
    expect(screen.getByText('A short description')).toBeInTheDocument()
  })

  it('renders tags for a blog with tags', () => {
    render(
      <MemoryRouter>
        <BlogCard blog={blog} imagePosition="left" />
      </MemoryRouter>,
    )

    expect(screen.getByText('Kubernetes')).toBeInTheDocument()
    expect(screen.getByText('Docker')).toBeInTheDocument()
  })

  it('renders without tags when blog has no tags', () => {
    const noTagBlog = { ...blog, tags: [] }
    render(
      <MemoryRouter>
        <BlogCard blog={noTagBlog} imagePosition="left" />
      </MemoryRouter>,
    )

    expect(screen.queryByRole('list', { name: 'Tags' })).not.toBeInTheDocument()
  })

  it('uses placeholder when featuredImageUrl is null', () => {
    const noImageBlog = { ...blog, featuredImageUrl: null }
    render(
      <MemoryRouter>
        <BlogCard blog={noImageBlog} imagePosition="left" />
      </MemoryRouter>,
    )

    const img = screen.getByRole('img', { name: 'My Blog Post' })
    expect(img).toHaveAttribute('src', '/images/blogs/placeholder.svg')
  })

  it('links to the blog detail page', () => {
    render(
      <MemoryRouter>
        <BlogCard blog={blog} imagePosition="left" />
      </MemoryRouter>,
    )

    const link = screen.getByRole('link', { name: 'My Blog Post' })
    expect(link).toHaveAttribute('href', '/blogs/b-1')
  })

  it('renders with image on the right', () => {
    render(
      <MemoryRouter>
        <BlogCard blog={blog} imagePosition="right" />
      </MemoryRouter>,
    )

    expect(screen.getByText('My Blog Post')).toBeInTheDocument()
  })
})
