import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { MarkdownRenderer } from '../../../src/components/blog/MarkdownRenderer'
import { NewsSummaryDrawer } from '../../../src/components/news/NewsSummaryDrawer'
import { SnippetLayer } from '../../../src/components/snippets/SnippetLayer'
import { postSource, summarySource } from '../../../src/components/snippets/snippetSource'
import type { ArticleSummaryResponse } from '../../../src/types/articleSummary'
import type { BlogDetail as BlogDetailType } from '../../../src/types/blog'
import type { ArticleResponse } from '../../../src/types/news'
import type { Snippet } from '../../../src/types/snippets'

vi.mock('../../../src/auth/useAuth', () => ({
  useAuth: () => ({ getAccessToken: tokenFn }),
}))

vi.mock('../../../src/services/snippetsApi', () => ({
  fetchSnippetsFor: vi.fn(),
  fetchSnippetTags: vi.fn(),
  createSnippet: vi.fn(),
  updateSnippet: vi.fn(),
  deleteSnippet: vi.fn(),
}))

const tokenFn = vi.fn().mockResolvedValue('token')

import {
  createSnippet,
  deleteSnippet,
  fetchSnippetsFor,
  fetchSnippetTags,
  updateSnippet,
} from '../../../src/services/snippetsApi'

const ARTICLE: ArticleResponse = {
  id: 'pr',
  title: 'Human PR Review Is Next',
  sourceName: 'TLDR Dev',
  originalUrl: 'https://example.com/pr',
  summary: 'Card blurb.',
  author: 'William Martin',
  publishedDate: '2026-10-12T20:37:19Z',
  fetchedAt: '2026-10-12T21:00:00Z',
  visible: true,
  imageUrl: null,
}

const BODY = 'Martin says engineers should review what it is required to do.\n\n'
  + 'Requirements and UAT remain human responsibilities.'

const READY: ArticleSummaryResponse = {
  state: 'READY', version: 1, body: BODY, model: 'm', retryable: false, message: '',
}

function snippet(overrides: Partial<Snippet> = {}): Snippet {
  return {
    id: 's1',
    sourceType: 'ARTICLE_SUMMARY',
    contentId: 'pr',
    exact: 'Requirements and UAT',
    prefix: 'to do.\n\n',
    suffix: ' remain human',
    note: 'Opening line',
    tags: ['talk'],
    articleTitle: ARTICLE.title,
    sourceName: ARTICLE.sourceName,
    author: ARTICLE.author,
    originalUrl: ARTICLE.originalUrl,
    articlePublishedAt: ARTICLE.publishedDate,
    createdAt: '2026-10-17T10:00:00Z',
    updatedAt: '2026-10-17T10:00:00Z',
    inCurrentSummary: true,
    articleAvailable: true,
    ...overrides,
  }
}

function renderDrawer({ isFavourite = true } = {}) {
  const proseRef = createRef<HTMLDivElement>()
  const onFavourite = vi.fn()
  const onCountChange = vi.fn()
  const onClose = vi.fn()
  render(
    <MemoryRouter>
      <NewsSummaryDrawer
        article={ARTICLE}
        delayed={false}
        error={null}
        isFavourite={isFavourite}
        loading={false}
        onClose={onClose}
        onRetry={vi.fn()}
        onToggleFavourite={vi.fn()}
        proseRef={proseRef}
        snippetLayer={(
          <SnippetLayer
            body={BODY}
            isFavourite={isFavourite}
            onCountChange={onCountChange}
            onFavourite={onFavourite}
            proseRef={proseRef}
            source={summarySource(ARTICLE)}
          />
        )}
        summary={READY}
      />
    </MemoryRouter>,
  )
  return { proseRef, onFavourite, onCountChange, onClose }
}

/** Selects text the way a reader would, then lets the selection settle. */
function select(fragment: string) {
  const prose = document.querySelector('.news-summary__prose') as HTMLElement
  const walker = document.createTreeWalker(prose, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node as Text
    const at = text.data.indexOf(fragment)
    if (at < 0) continue
    const range = document.createRange()
    range.setStart(text, at)
    range.setEnd(text, at + fragment.length)
    const selection = document.getSelection()!
    selection.removeAllRanges()
    selection.addRange(range)
    // jsdom keeps the selection but never fires the event a browser does.
    act(() => { document.dispatchEvent(new Event('selectionchange')) })
    return
  }
  throw new Error(`"${fragment}" is not in the summary`)
}

describe('SnippetLayer in the summary drawer', () => {
  beforeEach(() => {
    vi.mocked(fetchSnippetsFor).mockReset().mockResolvedValue([])
    vi.mocked(fetchSnippetTags).mockReset().mockResolvedValue([{ tag: 'talk', count: 3 }])
    vi.mocked(createSnippet).mockReset()
    vi.mocked(updateSnippet).mockReset()
    vi.mocked(deleteSnippet).mockReset()
  })

  it('keeps a selected passage with the text around it, and lists it', async () => {
    const saved = snippet({ id: 'new', exact: 'engineers should review', note: '', tags: [] })
    vi.mocked(createSnippet).mockResolvedValue(saved)
    const { onCountChange } = renderDrawer()
    await screen.findByText('Select any passage in the summary to keep it as a snippet.')

    select('engineers should review')
    await userEvent.click(await screen.findByRole('button', { name: 'Highlight' }))

    expect(fetchSnippetsFor).toHaveBeenCalledWith(expect.any(Function), 'pr', 'ARTICLE_SUMMARY')
    expect(createSnippet).toHaveBeenCalledWith(tokenFn, {
      sourceType: 'ARTICLE_SUMMARY',
      contentId: 'pr',
      exact: 'engineers should review',
      prefix: 'Martin says ',
      suffix: ' what it is required to do.\n\nReq',
    })
    const list = await screen.findByRole('region', { name: 'Your snippets' })
    expect(within(list).getByText('engineers should review')).toBeInTheDocument()
    expect(screen.getByText('Snippet saved')).toBeInTheDocument()
    await waitFor(() => expect(onCountChange).toHaveBeenLastCalledWith('pr', 1))
  })

  it('copies an unsaved passage with its citation without keeping it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    renderDrawer()
    await screen.findByText('Select any passage in the summary to keep it as a snippet.')

    select('Requirements and UAT')
    await userEvent.click(await screen.findByRole('button', { name: 'Copy with citation' }))

    expect(writeText).toHaveBeenCalledWith(expect.stringContaining(
      '> Requirements and UAT\n\n— AI summary of "Human PR Review Is Next" '
      + '(William Martin, TLDR Dev, 12 Oct 2026)'))
    expect(createSnippet).not.toHaveBeenCalled()
    expect(await screen.findByText('Copied with citation (not saved)')).toBeInTheDocument()
  })

  it('offers to heart the article after its first snippet, and only then', async () => {
    vi.mocked(createSnippet).mockResolvedValue(snippet({ id: 'new', exact: 'engineers' }))
    const { onFavourite } = renderDrawer({ isFavourite: false })
    await screen.findByText('Select any passage in the summary to keep it as a snippet.')

    select('engineers')
    await userEvent.click(await screen.findByRole('button', { name: 'Highlight' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Heart it' }))

    expect(onFavourite).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'Heart it' })).not.toBeInTheDocument()
  })

  it('opens an existing passage instead of keeping it twice', async () => {
    vi.mocked(fetchSnippetsFor).mockResolvedValue([snippet()])
    renderDrawer()
    await screen.findByText('Opening line')

    select('Requirements and UAT')
    await userEvent.click(await screen.findByRole('button', { name: 'Highlight' }))

    expect(createSnippet).not.toHaveBeenCalled()
    expect(await screen.findByRole('dialog', { name: 'Snippet' })).toBeInTheDocument()
    expect(screen.getByText('Already highlighted. Edit it here.')).toBeInTheDocument()
  })

  it('refuses a passage over the limit with a reason', async () => {
    const long = `${'word '.repeat(450)}end.`
    render(
      <MemoryRouter>
        <div className="news-summary__prose" ref={el => { proseHolder.current = el }}><p>{long}</p></div>
        <SnippetLayer
          body={long}
          isFavourite
          onFavourite={vi.fn()}
          proseRef={proseHolder}
          source={summarySource(ARTICLE)}
        />
      </MemoryRouter>,
    )
    await screen.findByText('Select any passage in the summary to keep it as a snippet.')

    select(long.trim())

    expect(await screen.findByText(/That is over 2,000 characters/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Highlight' })).not.toBeInTheDocument()
  })

  it('edits a note and tags, saving when Done is pressed', async () => {
    vi.mocked(fetchSnippetsFor).mockResolvedValue([snippet()])
    vi.mocked(updateSnippet).mockResolvedValue(snippet({ note: 'Closing line', tags: ['talk', 'metrics'] }))
    renderDrawer()

    await userEvent.click(await screen.findByRole('button', { name: /Requirements and UAT/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Snippet' })
    const note = within(dialog).getByLabelText('Note')
    await userEvent.clear(note)
    await userEvent.type(note, 'Closing line')
    await userEvent.type(within(dialog).getByLabelText('Tags'), 'Metrics{Enter}')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Done' }))

    expect(updateSnippet).toHaveBeenCalledWith(tokenFn, 's1', {
      note: 'Closing line', tags: ['talk', 'metrics'],
    })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Snippet' })).not.toBeInTheDocument())
    expect(screen.getByText('Closing line')).toBeInTheDocument()
  })

  it('closes only the popover on Escape, never the summary behind it', async () => {
    vi.mocked(fetchSnippetsFor).mockResolvedValue([snippet()])
    const { onClose } = renderDrawer()

    await userEvent.click(await screen.findByRole('button', { name: /Requirements and UAT/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Snippet' })
    fireEvent.keyDown(within(dialog).getByLabelText('Note'), { key: 'Escape' })

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Snippet' })).not.toBeInTheDocument())
    expect(onClose).not.toHaveBeenCalled()
    expect(updateSnippet).not.toHaveBeenCalled()
  })

  it('deletes only after a second, explicit press', async () => {
    vi.mocked(fetchSnippetsFor).mockResolvedValue([snippet()])
    vi.mocked(deleteSnippet).mockResolvedValue(undefined)
    renderDrawer()

    await userEvent.click(await screen.findByRole('button', { name: /Requirements and UAT/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Snippet' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete snippet' }))
    expect(deleteSnippet).not.toHaveBeenCalled()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete snippet' }))

    expect(deleteSnippet).toHaveBeenCalledWith(tokenFn, 's1')
    expect(await screen.findByText('Snippet deleted')).toBeInTheDocument()
    expect(screen.queryByText('Opening line')).not.toBeInTheDocument()
  })

  it('says when a kept passage is no longer in the summary, and keeps it', async () => {
    vi.mocked(fetchSnippetsFor).mockResolvedValue([
      snippet({ exact: 'A sentence from an earlier summary.', inCurrentSummary: false }),
    ])
    renderDrawer()

    expect(await screen.findByText('A sentence from an earlier summary.')).toBeInTheDocument()
    expect(screen.getByText('No longer in the current summary. Your copy is kept.')).toBeInTheDocument()
  })
})

const proseHolder: { current: HTMLDivElement | null } = { current: null }

const POST: BlogDetailType = {
  id: 'b-1',
  title: 'Measuring Engineering',
  shortDescription: 'About measuring',
  content: 'Most teams **measure the wrong thing**.\n\n| Metric | Why |\n| --- | --- |\n'
    + '| Lines of code | It rewards volume |\n\nVerification is the bottleneck now.',
  featuredImageUrl: null,
  createdDate: '2024-06-01T10:00:00Z',
  tags: [],
  skills: [],
  contentType: 'ENGINEERING',
}

/** The post rendered as the blog page renders it (BlogDetail's own wiring is tested there). */
function renderPost() {
  const proseRef = createRef<HTMLDivElement>()
  render(
    <MemoryRouter>
      <div className="blog-detail__content" ref={proseRef}>
        <MarkdownRenderer content={POST.content} />
      </div>
      <SnippetLayer body={POST.content} proseRef={proseRef} source={postSource(POST)} />
    </MemoryRouter>,
  )
}

/** Selects from the start of one fragment to the end of another, across blocks. */
function selectAcross(from: string, to: string) {
  const prose = document.querySelector('.blog-detail__content') as HTMLElement
  const nodes: Text[] = []
  const walker = document.createTreeWalker(prose, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) nodes.push(node as Text)
  const start = nodes.find(node => node.data.includes(from))!
  const end = nodes.find(node => node.data.includes(to))!
  const range = document.createRange()
  range.setStart(start, start.data.indexOf(from))
  range.setEnd(end, end.data.indexOf(to) + to.length)
  const selection = document.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
  act(() => { document.dispatchEvent(new Event('selectionchange')) })
}

describe('SnippetLayer under a blog post', () => {
  beforeEach(() => {
    vi.mocked(fetchSnippetsFor).mockReset().mockResolvedValue([])
    vi.mocked(fetchSnippetTags).mockReset().mockResolvedValue([])
    vi.mocked(createSnippet).mockReset()
  })

  it('keeps a passage across a table as a post snippet, with no offer to heart anything', async () => {
    vi.mocked(createSnippet).mockResolvedValue(snippet({
      id: 'new', sourceType: 'BLOG', contentId: 'b-1', exact: 'Lines of code', note: '', tags: [],
    }))
    renderPost()
    await screen.findByText('Select any passage in the post to keep it as a snippet.')
    expect(fetchSnippetsFor).toHaveBeenCalledWith(expect.any(Function), 'b-1', 'BLOG')

    selectAcross('Lines of code', 'It rewards')
    await userEvent.click(await screen.findByRole('button', { name: 'Highlight' }))

    // Table cells are blocks, as the server reads them, so the quote keeps them apart.
    expect(createSnippet).toHaveBeenCalledWith(tokenFn, expect.objectContaining({
      sourceType: 'BLOG',
      contentId: 'b-1',
      exact: 'Lines of code\n\nIt rewards',
    }))
    expect(await screen.findByText('Snippet saved')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Heart it' })).not.toBeInTheDocument()
  })

  it('cites a copied passage as the post, not as an AI summary', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    renderPost()
    await screen.findByText('Select any passage in the post to keep it as a snippet.')

    selectAcross('Verification', 'bottleneck')
    await userEvent.click(await screen.findByRole('button', { name: 'Copy with citation' }))

    expect(writeText).toHaveBeenCalledWith(
      '> Verification is the bottleneck\n\n'
      + '— Simon Rowe, "Measuring Engineering" (simonrowe.dev, 1 Jun 2024)\n'
      + `  ${window.location.origin}/blogs/b-1`)
  })

  it('says a kept passage is no longer in the post once it is edited', async () => {
    vi.mocked(fetchSnippetsFor).mockResolvedValue([snippet({
      sourceType: 'BLOG', contentId: 'b-1', exact: 'An older sentence.', inCurrentSummary: false,
    })])
    renderPost()

    expect(await screen.findByText('No longer in the current post. Your copy is kept.'))
      .toBeInTheDocument()
  })
})
