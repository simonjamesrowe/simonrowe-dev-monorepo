import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { BlogListingPage } from '../../src/pages/BlogListingPage'
import type { BlogSummary } from '../../src/types/blog'

vi.mock('../../src/services/blogApi', () => ({
  fetchBlogs: vi.fn(),
  searchBlogs: vi.fn().mockResolvedValue([]),
}))

import { fetchBlogs } from '../../src/services/blogApi'
import { NarrationAudioStub } from '../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../testUtils/narrationAudioValue'

const blogs: BlogSummary[] = [
  {
    id: 'b-1',
    title: 'Spring Boot Tips',
    shortDescription: 'About Spring',
    createdDate: '2024-06-01T10:00:00Z',
    tags: [{ name: 'Spring' }],
    contentType: 'ENGINEERING',
  },
]

/** Newest first, as the API returns them: a digest sits above the engineering posts. */
const mixedBlogs: BlogSummary[] = [
  {
    id: 'd-1',
    title: 'Weekly Digest 42',
    shortDescription: 'Links from the week',
    createdDate: '2024-07-05T10:00:00Z',
    tags: [{ name: 'Weekly Digest' }],
    contentType: 'DIGEST',
  },
  {
    id: 'e-1',
    title: 'Event Sourcing Without Ceremony',
    shortDescription: 'What we kept',
    createdDate: '2024-07-01T10:00:00Z',
    tags: [{ name: 'Kafka' }],
    contentType: 'ENGINEERING',
  },
  {
    id: 'e-2',
    title: 'Spring Boot Tips',
    shortDescription: 'About Spring',
    createdDate: '2024-06-01T10:00:00Z',
    tags: [{ name: 'Spring' }],
    contentType: 'ENGINEERING',
  },
]

/** Reassigned per test so a case can seed "this post has audio" before rendering. */
let narration = narrationAudioStub()

function renderPage() {
  return render(
    <MemoryRouter>
      <NarrationAudioStub value={narration}>
        <BlogListingPage />
      </NarrationAudioStub>
    </MemoryRouter>,
  )
}

describe('BlogListingPage', () => {
  beforeEach(() => {
    narration = narrationAudioStub()
    vi.mocked(fetchBlogs).mockReset()
  })

  it('renders loading state initially', () => {
    vi.mocked(fetchBlogs).mockImplementation(() => new Promise(() => {}))

    renderPage()

    expect(screen.getByText('Loading blogs...')).toBeInTheDocument()
  })

  it('renders blog listing when data loads', async () => {
    vi.mocked(fetchBlogs).mockResolvedValue(blogs)

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Spring Boot Tips')).toBeInTheDocument()
    })
  })

  it('renders error state when fetch fails', async () => {
    vi.mocked(fetchBlogs).mockRejectedValue(new Error('Network error'))

    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Network error')
    })
  })

  it('gives the error state a title and a retry that refetches', async () => {
    vi.mocked(fetchBlogs)
      .mockRejectedValueOnce(new Error('Network error'))
      .mockResolvedValueOnce(blogs)

    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Unable to load the blog')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => {
      expect(screen.getByText('Spring Boot Tips')).toBeInTheDocument()
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(vi.mocked(fetchBlogs)).toHaveBeenCalledTimes(2)
  })

  it('defaults to the Engineering tab and lists only engineering posts', async () => {
    vi.mocked(fetchBlogs).mockResolvedValue(mixedBlogs)

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Event Sourcing Without Ceremony')).toBeInTheDocument()
    })

    expect(screen.getByRole('tab', { name: 'Engineering' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByText('Spring Boot Tips')).toBeInTheDocument()
    expect(screen.queryByText('Weekly Digest 42')).not.toBeInTheDocument()
  })

  it('features the newest engineering post even when a digest is newer', async () => {
    vi.mocked(fetchBlogs).mockResolvedValue(mixedBlogs)

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('FEATURED ARTICLE')).toBeInTheDocument()
    })

    const featured = document.querySelector('.featured-article')
    expect(featured).toHaveTextContent('Event Sourcing Without Ceremony')
    expect(featured).not.toHaveTextContent('Weekly Digest 42')
  })

  it('lists only digests on the Weekly Digest tab', async () => {
    vi.mocked(fetchBlogs).mockResolvedValue(mixedBlogs)

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Event Sourcing Without Ceremony')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('tab', { name: 'Weekly Digest' }))

    expect(screen.getByText('Weekly Digest 42')).toBeInTheDocument()
    expect(screen.queryByText('Event Sourcing Without Ceremony')).not.toBeInTheDocument()
    expect(screen.queryByText('Spring Boot Tips')).not.toBeInTheDocument()
  })

  it('lists both content types on the All tab', async () => {
    vi.mocked(fetchBlogs).mockResolvedValue(mixedBlogs)

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Event Sourcing Without Ceremony')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('tab', { name: 'All' }))

    expect(screen.getByText('Weekly Digest 42')).toBeInTheDocument()
    expect(screen.getByText('Event Sourcing Without Ceremony')).toBeInTheDocument()
    expect(screen.getByText('Spring Boot Tips')).toBeInTheDocument()
    // The featured slot stays reserved for engineering writing.
    expect(document.querySelector('.featured-article')).toHaveTextContent(
      'Event Sourcing Without Ceremony',
    )
  })

  it('shows an empty state instead of an empty featured frame when a tab has no posts', async () => {
    vi.mocked(fetchBlogs).mockResolvedValue(
      mixedBlogs.filter((blog) => blog.contentType === 'ENGINEERING'),
    )

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Event Sourcing Without Ceremony')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('tab', { name: 'Weekly Digest' }))

    expect(screen.getByText('No posts in this section yet. Try another tab.')).toBeInTheDocument()
    expect(document.querySelector('.featured-article')).toBeNull()
  })

  it('shows an empty state when there are no posts at all', async () => {
    vi.mocked(fetchBlogs).mockResolvedValue([])

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('No posts published yet. Check back soon.')).toBeInTheDocument()
    })

    expect(document.querySelector('.featured-article')).toBeNull()
  })

  it('does not fall back to featuring a digest when there are no engineering posts', async () => {
    vi.mocked(fetchBlogs).mockResolvedValue(
      mixedBlogs.filter((blog) => blog.contentType === 'DIGEST'),
    )

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('No posts in this section yet. Try another tab.')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('tab', { name: 'All' }))

    // The digest is listed, but never promoted into the featured slot.
    expect(screen.getByText('Weekly Digest 42')).toBeInTheDocument()
    expect(document.querySelector('.featured-article')).toBeNull()
  })

  describe('the listen control', () => {
    it('appears on the featured article and on every grid card', async () => {
      vi.mocked(fetchBlogs).mockResolvedValue(mixedBlogs)

      renderPage()
      await waitFor(() => {
        expect(screen.getByText('Event Sourcing Without Ceremony')).toBeInTheDocument()
      })
      fireEvent.click(screen.getByRole('tab', { name: 'All' }))

      expect(document.querySelector('.featured-article .listen-button')).toBeInTheDocument()
      // One per grid card: the digest and the second engineering post.
      expect(document.querySelectorAll('.article-card .listen-button')).toHaveLength(2)
    })

    it('advertises the duration for a post that already has audio', async () => {
      narration = narrationAudioStub({
        ready: {
          'BLOG:e-1': {
            contentId: 'e-1',
            audioUrl: '/uploads/narrations/aaa/narration.mp3',
            durationSeconds: 734,
          },
        },
      })
      vi.mocked(fetchBlogs).mockResolvedValue(mixedBlogs)

      renderPage()

      await waitFor(() => {
        expect(screen.getByText('Event Sourcing Without Ceremony')).toBeInTheDocument()
      })
      expect(screen.getByRole('button', {
        name: 'Listen to the 12 min audio version of Event Sourcing Without Ceremony',
      })).toBeInTheDocument()
      // The other post has none, so it keeps the cold invitation.
      expect(screen.getByRole('button', {
        name: 'Generate an audio version of Spring Boot Tips',
      })).toBeInTheDocument()
    })
  })
})
