import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/services/adminApi', async () => {
  const actual = await vi.importActual<typeof import('../../src/services/adminApi')>(
    '../../src/services/adminApi',
  )
  return {
    AdminValidationError: actual.AdminValidationError,
    toAdminBookInput: actual.toAdminBookInput,
    fetchAdminBooks: vi.fn(),
    fetchAdminBook: vi.fn(),
    createAdminBook: vi.fn(),
    updateAdminBook: vi.fn(),
    deleteAdminBook: vi.fn(),
    setAdminBookPublished: vi.fn(),
    reorderAdminBooks: vi.fn(),
    uploadAdminMedia: vi.fn(),
    fetchAdminMedia: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 }),
  }
})
// One stable function, as the real hook provides.
const getAccessToken = vi.fn()
vi.mock('../../src/auth/useAuth', () => ({ useAuth: () => ({ getAccessToken }) }))
vi.mock('../../src/hooks/useUnsavedChanges', () => ({ useUnsavedChanges: vi.fn() }))
// MDXEditor does not run in jsdom; stand in a labelled textarea with the same contract.
vi.mock('../../src/components/admin/RichMarkdownEditor', () => ({
  RichMarkdownEditor: ({ label, markdown, onChange }: { label: string; markdown: string; onChange: (v: string) => void }) => (
    <textarea aria-label={label} defaultValue={markdown} onChange={event => onChange(event.target.value)} />
  ),
}))

import {
  AdminValidationError,
  createAdminBook,
  deleteAdminBook,
  fetchAdminBook,
  fetchAdminBooks,
  reorderAdminBooks,
  setAdminBookPublished,
  updateAdminBook,
  type AdminBook,
} from '../../src/services/adminApi'
import { BookEditor } from '../../src/pages/admin/BookEditor'
import { BooksAdmin } from '../../src/pages/admin/BooksAdmin'

const row = (id: string, title: string, published = true): AdminBook => ({
  id, slug: id, title, subtitle: null, authors: ['An Author'], publishedYear: null,
  category: 'Leading teams', summary: `${title} summary`, description: null, note: null,
  coverImage: null, purchaseUrl: null, sourceLabel: null, sourceUrl: null, displayOrder: 0,
  published, createdAt: '2026-10-19T00:00:00Z', updatedAt: '2026-10-19T00:00:00Z',
})

/** A book with every field set, as the API returns it. */
const full = (): AdminBook => ({
  ...row('continuous-delivery', 'Continuous Delivery'),
  subtitle: 'Reliable Software Releases',
  authors: ['Jez Humble', 'David Farley'],
  publishedYear: 2010,
  category: 'Delivery and production',
  description: '## Key ideas\n\n- Build once\n',
  note: 'This site deploys itself.',
  coverImage: { url: '/uploads/cd/original.jpg' },
  purchaseUrl: 'https://www.amazon.co.uk/dp/B003YMNVC0',
  sourceLabel: 'Trisha Gee',
  sourceUrl: 'https://trishagee.com/talk',
  displayOrder: 3,
})

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<BooksAdmin />} path="/admin/books" />
        <Route element={<BookEditor />} path="/admin/books/:id" />
      </Routes>
    </MemoryRouter>,
  )
}

describe('BooksAdmin', () => {
  beforeEach(() => {
    vi.mocked(fetchAdminBooks).mockReset().mockResolvedValue([row('a', 'Alpha'), row('b', 'Beta', false)])
    vi.mocked(reorderAdminBooks).mockReset().mockResolvedValue()
    vi.mocked(deleteAdminBook).mockReset().mockResolvedValue()
    vi.mocked(createAdminBook).mockReset()
    vi.mocked(setAdminBookPublished).mockReset()
  })

  it('lists books with how many are published', async () => {
    renderAt('/admin/books')
    expect(await screen.findByText('Alpha')).toBeInTheDocument()
    expect(screen.getByText(/1 of 2 published/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Unpublish Alpha' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Publish Beta' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('publishes a draft straight from the list', async () => {
    const user = userEvent.setup()
    vi.mocked(setAdminBookPublished).mockResolvedValue(row('b', 'Beta', true))
    renderAt('/admin/books')
    await user.click(await screen.findByRole('button', { name: 'Publish Beta' }))
    expect(setAdminBookPublished).toHaveBeenCalledWith(getAccessToken, row('b', 'Beta', false), true)
    expect(await screen.findByRole('button', { name: 'Unpublish Beta' })).toBeInTheDocument()
    expect(screen.getByText(/2 of 2 published/)).toBeInTheDocument()
  })

  it('sends the new order after a drag and drop', async () => {
    renderAt('/admin/books')
    const rows = (await screen.findAllByRole('row')).slice(1)
    fireEvent.dragStart(rows[1])
    fireEvent.dragOver(rows[0])
    fireEvent.drop(rows[0])
    await waitFor(() => expect(reorderAdminBooks).toHaveBeenCalledWith(getAccessToken, ['b', 'a']))
  })

  it('deletes after confirmation', async () => {
    const user = userEvent.setup()
    renderAt('/admin/books')
    await user.click(await screen.findByRole('button', { name: 'Delete Alpha' }))
    // `hidden: true` because the shared ConfirmDialog marks its own backdrop aria-hidden.
    await user.click(screen.getByRole('button', { hidden: true, name: 'Delete' }))
    await waitFor(() => expect(deleteAdminBook).toHaveBeenCalledWith(getAccessToken, 'a'))
  })

  it('suggests the address from the title and sends every field on create', async () => {
    const user = userEvent.setup()
    vi.mocked(createAdminBook).mockResolvedValue(row('new', 'New'))
    renderAt('/admin/books/new')

    await user.type(await screen.findByLabelText('Title'), 'Tidy First?')
    expect(screen.getByLabelText('Address')).toHaveValue('tidy-first')
    await user.type(screen.getByLabelText('Authors'), '  Kent Beck  {enter}{enter}')
    await user.type(screen.getByLabelText('Category'), 'Engineering in the AI era')
    await user.type(screen.getByLabelText('First published'), '2023')
    await user.type(screen.getByLabelText('Summary'), 'Small structural changes.')
    await user.click(screen.getByRole('checkbox', { name: 'Published' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(createAdminBook).toHaveBeenCalledTimes(1))
    expect(vi.mocked(createAdminBook).mock.calls[0][1]).toEqual({
      slug: 'tidy-first',
      title: 'Tidy First?',
      subtitle: '',
      authors: ['Kent Beck'],
      publishedYear: 2023,
      category: 'Engineering in the AI era',
      summary: 'Small structural changes.',
      description: '',
      note: '',
      coverImage: null,
      purchaseUrl: '',
      sourceLabel: '',
      sourceUrl: '',
      displayOrder: null,
      published: true,
    })
  })

  it('offers the existing categories to pick from', async () => {
    renderAt('/admin/books/new')
    await screen.findByLabelText('Category')
    const options = [...document.querySelectorAll('#book-categories option')].map(option => option.getAttribute('value'))
    expect(options).toEqual(['Leading teams'])
  })

  it('saves every field back unchanged when nothing was edited', async () => {
    const user = userEvent.setup()
    const book = full()
    vi.mocked(fetchAdminBook).mockReset().mockResolvedValue(book)
    vi.mocked(updateAdminBook).mockReset().mockResolvedValue(book)
    renderAt('/admin/books/continuous-delivery')

    expect(await screen.findByLabelText('Authors')).toHaveValue('Jez Humble\nDavid Farley')
    expect(screen.getByLabelText('First published')).toHaveValue('2010')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(updateAdminBook).toHaveBeenCalledTimes(1))
    const editable: Partial<AdminBook> = { ...book }
    delete editable.id
    delete editable.createdAt
    delete editable.updatedAt
    expect(vi.mocked(updateAdminBook).mock.calls[0][2]).toEqual(editable)
  })

  it('refuses a year that is not a number without calling the server', async () => {
    const user = userEvent.setup()
    renderAt('/admin/books/new')
    await user.type(await screen.findByLabelText('First published'), 'c. 2004')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText(/must be a year/i)).toBeInTheDocument()
    expect(createAdminBook).not.toHaveBeenCalled()
  })

  it('shows a server error for any one author under the authors box', async () => {
    const user = userEvent.setup()
    vi.mocked(createAdminBook).mockRejectedValue(new AdminValidationError('Validation failed', [
      { field: 'authors[1]', message: 'authors[1] must be at most 80 characters' },
    ]))
    renderAt('/admin/books/new')
    await user.type(await screen.findByLabelText('Title'), 'X')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/must be at most 80 characters/i)
  })
})
