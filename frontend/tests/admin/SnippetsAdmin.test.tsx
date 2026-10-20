import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SnippetsAdmin } from '../../src/pages/admin/SnippetsAdmin'
import type { Snippet, SnippetPage } from '../../src/types/snippets'

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: () => ({ getAccessToken: vi.fn().mockResolvedValue('token') }),
}))

vi.mock('../../src/services/snippetsApi', () => ({
  searchSnippets: vi.fn(),
  fetchSnippetTags: vi.fn(),
  fetchSnippetSources: vi.fn(),
  updateSnippet: vi.fn(),
  deleteSnippet: vi.fn(),
}))

import {
  deleteSnippet,
  fetchSnippetSources,
  fetchSnippetTags,
  searchSnippets,
  updateSnippet,
} from '../../src/services/snippetsApi'

function snippet(id: string, overrides: Partial<Snippet> = {}): Snippet {
  return {
    id,
    sourceType: 'ARTICLE_SUMMARY',
    contentId: `article-${id}`,
    exact: `Quote ${id}`,
    prefix: '',
    suffix: '',
    note: '',
    tags: [],
    articleTitle: `Article ${id}`,
    sourceName: 'TLDR Dev',
    author: null,
    originalUrl: `https://example.com/${id}`,
    articlePublishedAt: '2026-10-12T00:00:00Z',
    createdAt: '2026-10-17T10:00:00Z',
    updatedAt: '2026-10-17T10:00:00Z',
    inCurrentSummary: true,
    articleAvailable: true,
    ...overrides,
  }
}

function page(content: Snippet[], last = true): SnippetPage {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 50, last }
}

/** A tag chip, not the same tag shown on a card. */
function chip(tag: string) {
  return within(screen.getByRole('group', { name: 'Filter by tag' }))
    .getByRole('button', { name: new RegExp(`^#${tag}`) })
}

function renderPage() {
  return render(
    <MemoryRouter>
      <SnippetsAdmin />
    </MemoryRouter>,
  )
}

describe('SnippetsAdmin', () => {
  beforeEach(() => {
    vi.mocked(searchSnippets).mockReset().mockResolvedValue(page([
      snippet('one', { note: 'Opening slide', tags: ['talk'] }),
      snippet('two', { tags: ['talk', 'metrics'], inCurrentSummary: false }),
    ]))
    vi.mocked(fetchSnippetTags).mockReset().mockResolvedValue([
      { tag: 'talk', count: 2 }, { tag: 'metrics', count: 1 },
    ])
    vi.mocked(fetchSnippetSources).mockReset().mockResolvedValue(['Meta Engineering', 'TLDR Dev'])
    vi.mocked(updateSnippet).mockReset()
    vi.mocked(deleteSnippet).mockReset()
  })

  it('lists snippets with their notes, tags and whether the summary still has them', async () => {
    renderPage()

    expect(await screen.findByText('Quote one')).toBeInTheDocument()
    expect(screen.getByText('Opening slide')).toBeInTheDocument()
    expect(screen.getAllByText('not in current summary')).toHaveLength(1)
    expect(searchSnippets).toHaveBeenCalledWith(expect.any(Function), expect.objectContaining({
      q: '', tags: [], sources: [], sort: 'newest', page: 0,
    }))
  })

  it('searches by text, tag and source, sending tags and sources as lists', async () => {
    renderPage()
    await screen.findByText('Quote one')

    await userEvent.type(screen.getByPlaceholderText(/Search quotes/), 'review')
    await userEvent.click(chip('talk'))
    await userEvent.click(screen.getByText('All sources'))
    await userEvent.click(screen.getByLabelText('Meta Engineering'))

    await waitFor(() => expect(searchSnippets).toHaveBeenLastCalledWith(
      expect.any(Function),
      expect.objectContaining({ q: 'review', tags: ['talk'], sources: ['Meta Engineering'] }),
    ))
    expect(chip('talk')).toHaveAttribute('aria-pressed', 'true')
  })

  it('copies every snippet shown as one markdown document', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    renderPage()
    await screen.findByText('Quote one')

    await userEvent.click(screen.getByLabelText('Select all shown'))
    await userEvent.click(screen.getByRole('button', { name: 'Copy as Markdown' }))

    const markdown = writeText.mock.calls[0][0] as string
    expect(markdown).toContain('> Quote one')
    expect(markdown).toContain('> Quote two')
    expect(markdown).toContain('Note: Opening slide')
    expect(await screen.findByText('Copied 2 snippets as Markdown')).toBeInTheDocument()
  })

  it('acts only on selected snippets that are still shown', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    renderPage()
    await screen.findByText('Quote one')
    await userEvent.click(screen.getByLabelText('Select all shown'))

    vi.mocked(searchSnippets).mockResolvedValue(page([snippet('two')]))
    await userEvent.click(chip('metrics'))
    await waitFor(() => expect(screen.queryByText('Quote one')).not.toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'Copy as Markdown' }))

    const markdown = writeText.mock.calls[0][0] as string
    expect(markdown).toContain('> Quote two')
    expect(markdown).not.toContain('Quote one')
    expect(markdown.startsWith('# Snippets — #metrics')).toBe(true)
  })

  it('saves a new note and tags from the side panel', async () => {
    vi.mocked(updateSnippet).mockResolvedValue(snippet('one', { note: 'Closing slide', tags: ['talk'] }))
    renderPage()

    await userEvent.click(await screen.findByText('Quote one'))
    const panel = screen.getByRole('complementary', { name: 'Snippet details' })
    const note = within(panel).getByLabelText('Note')
    await userEvent.clear(note)
    await userEvent.type(note, 'Closing slide')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    expect(updateSnippet).toHaveBeenCalledWith(expect.any(Function), 'one', {
      note: 'Closing slide', tags: ['talk'],
    })
    expect(await screen.findByText('Saved')).toBeInTheDocument()
  })

  it('links back to the summary, unless the article is gone', async () => {
    vi.mocked(searchSnippets).mockResolvedValue(page([
      snippet('one'), snippet('gone', { articleAvailable: false }),
    ]))
    renderPage()

    await userEvent.click(await screen.findByText('Quote one'))
    expect(screen.getByRole('link', { name: /Open summary/ }))
      .toHaveAttribute('href', '/news-events?article=article-one')

    await userEvent.click(screen.getByText('Quote gone'))
    expect(screen.getByRole('button', { name: /Open summary/ })).toBeDisabled()
  })

  it('links a post snippet back to the post and cites it as the post', async () => {
    vi.mocked(searchSnippets).mockResolvedValue(page([
      snippet('post', {
        sourceType: 'BLOG', contentId: 'b-1', articleTitle: 'Measuring Engineering',
        sourceName: 'simonrowe.dev', author: 'Simon Rowe', inCurrentSummary: false,
      }),
      snippet('draft', { sourceType: 'BLOG', contentId: 'b-2', articleAvailable: false }),
    ]))
    renderPage()

    expect(await screen.findByText('not in current post')).toBeInTheDocument()
    await userEvent.click(screen.getByText('Quote post'))
    const panel = screen.getByRole('complementary', { name: 'Snippet details' })
    expect(within(panel).getByRole('link', { name: /Open post/ })).toHaveAttribute('href', '/blogs/b-1')
    expect(within(panel).getByText(/^From/)).toBeInTheDocument()
    expect(within(panel).queryByText(/AI summary of/)).not.toBeInTheDocument()

    await userEvent.click(screen.getByText('Quote draft'))
    expect(screen.getByRole('button', { name: /Open post/ })).toBeDisabled()
  })

  it('deletes only after the delete is confirmed', async () => {
    vi.mocked(deleteSnippet).mockResolvedValue(undefined)
    renderPage()

    await userEvent.click(await screen.findByText('Quote one'))
    const panel = screen.getByRole('complementary', { name: 'Snippet details' })
    await userEvent.click(within(panel).getByRole('button', { name: /Delete/ }))
    expect(deleteSnippet).not.toHaveBeenCalled()
    await userEvent.click(within(panel).getByRole('button', { name: /Confirm delete/ }))

    expect(deleteSnippet).toHaveBeenCalledWith(expect.any(Function), 'one')
    await waitFor(() => expect(screen.queryByText('Quote one')).not.toBeInTheDocument())
  })

  it('offers to clear the filters when nothing matches', async () => {
    renderPage()
    await screen.findByText('Quote one')
    vi.mocked(searchSnippets).mockResolvedValue(page([]))

    await userEvent.type(screen.getByPlaceholderText(/Search quotes/), 'nothing')

    expect(await screen.findByText('No snippets match. Try fewer words, or clear a tag.'))
      .toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(screen.getByPlaceholderText(/Search quotes/)).toHaveValue('')
  })
})
