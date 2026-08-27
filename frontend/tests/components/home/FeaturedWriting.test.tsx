import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { FeaturedWriting } from '../../../src/components/home/FeaturedWriting'
import type { BlogSummary } from '../../../src/types/blog'
import { NarrationAudioStub } from '../../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../../testUtils/narrationAudioValue'

function post(index: number): BlogSummary {
  return {
    id: `b-${index}`,
    title: `Post ${index}`,
    shortDescription: `Description ${index}`,
    createdDate: '2024-06-01T10:00:00Z',
    tags: [{ name: 'Spring' }],
    contentType: 'ENGINEERING',
  }
}

function posts(count: number): BlogSummary[] {
  return Array.from({ length: count }, (_, i) => post(i + 1))
}

function renderFeatured(blogs?: BlogSummary[]) {
  return render(
    <MemoryRouter>
      <NarrationAudioStub value={narrationAudioStub()}>
        <FeaturedWriting blogs={blogs} />
      </NarrationAudioStub>
    </MemoryRouter>,
  )
}

describe('FeaturedWriting', () => {
  beforeEach(() => {
    // jsdom has no layout, so scrollBy is not implemented; the arrows only need it called.
    Element.prototype.scrollBy = vi.fn()
  })

  it('renders up to ten posts', () => {
    renderFeatured(posts(14))

    expect(screen.getAllByRole('article')).toHaveLength(10)
    expect(screen.getByText('Post 1')).toBeInTheDocument()
    expect(screen.getByText('Post 10')).toBeInTheDocument()
    expect(screen.queryByText('Post 11')).not.toBeInTheDocument()
  })

  it('renders fewer than ten without placeholder gaps', () => {
    renderFeatured([post(1)])

    expect(screen.getAllByRole('article')).toHaveLength(1)
    expect(document.querySelectorAll('.featured-writing__track > *')).toHaveLength(1)
  })

  it('exposes the carousel as a labelled list of slides', () => {
    renderFeatured(posts(4))

    expect(screen.getByRole('list', { name: 'Recent engineering posts' })).toBeInTheDocument()
    expect(document.querySelectorAll('.featured-writing__slide')).toHaveLength(4)
  })

  it('scrolls the track when the next arrow is used', async () => {
    renderFeatured(posts(6))

    // jsdom does no layout, so every dimension is 0 and the track looks fully scrolled.
    // Give it an overflowing geometry so the next arrow becomes live.
    const track = document.querySelector('.featured-writing__track') as HTMLElement
    Object.defineProperty(track, 'clientWidth', { configurable: true, value: 600 })
    Object.defineProperty(track, 'scrollWidth', { configurable: true, value: 2400 })
    fireEvent.scroll(track)

    const next = screen.getByRole('button', { name: 'Scroll to more posts' })
    expect(next).toBeEnabled()
    await userEvent.click(next)

    expect(Element.prototype.scrollBy).toHaveBeenCalled()
  })

  it('disables the previous arrow at the start of the track', () => {
    renderFeatured(posts(6))

    // scrollLeft is 0 in jsdom, so the carousel starts pinned to the left.
    expect(screen.getByRole('button', { name: 'Scroll to previous posts' })).toBeDisabled()
  })

  it('does not auto-rotate — the cards carry text that must stay readable', () => {
    renderFeatured(posts(6))

    const track = document.querySelector('.featured-writing__track')
    // Motion here would fail WCAG 2.2.2; the marquee animation belongs to the logo strip.
    expect(track?.className).not.toContain('marquee')
    expect(document.querySelector('.employer-logo-strip__track')).toBeNull()
  })

  it('links each post to its detail page and offers a link to the full listing', () => {
    renderFeatured([post(1)])

    expect(document.querySelector('.article-card__link')).toHaveAttribute('href', '/blogs/b-1')
    expect(screen.getByRole('link', { name: /Read the blog/i })).toHaveAttribute('href', '/blogs')
  })

  it('renders nothing when there are no posts', () => {
    const { container } = renderFeatured([])
    expect(container).toBeEmptyDOMElement()

    const { container: undefinedBlogs } = renderFeatured(undefined)
    expect(undefinedBlogs).toBeEmptyDOMElement()
  })
})
