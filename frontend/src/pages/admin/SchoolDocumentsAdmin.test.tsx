import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuth } from '../../auth/useAuth'
import {
  editSchoolDocument,
  fetchSchoolDocuments,
  type SchoolDocumentSummary,
} from '../../services/adminApi'
import { SchoolDocumentsAdmin } from './SchoolDocumentsAdmin'

vi.mock('../../auth/useAuth', () => ({ useAuth: vi.fn() }))
vi.mock('../../services/adminApi', () => ({
  bulkSchoolApproval: vi.fn(),
  decideSchoolLink: vi.fn(),
  editSchoolDocument: vi.fn(),
  fetchSchoolDocuments: vi.fn(),
  openSchoolAttachment: vi.fn(),
}))

const getAccessToken = vi.fn().mockResolvedValue('token')
const mockedUseAuth = vi.mocked(useAuth)
const mockedFetch = vi.mocked(fetchSchoolDocuments)
const mockedEdit = vi.mocked(editSchoolDocument)

const SPELLINGS = 'Autumn 1 Week 3\nPrefixes un, dis\n\nunhappy\nunusual'

function row(overrides: Partial<SchoolDocumentSummary> = {}): SchoolDocumentSummary {
  return {
    id: 'note-1',
    title: "Prefixes 'un', 'dis' - Autumn 1 Week 3",
    preview: SPELLINGS,
    sourceType: 'PASTED_NOTE',
    sourceRef: 'paste:abc',
    publishedAt: '2026-09-30T07:00:00Z',
    visibility: 'PUBLIC',
    proposedVisibility: null,
    proposalReason: null,
    approvedBy: null,
    approvedAt: null,
    hasAttachment: false,
    body: SPELLINGS,
    discoveredLinks: [],
    originalUrl: null,
    yearGroups: [],
    editsOverwrittenByCrawl: false,
    ...overrides,
  }
}

function renderPage() {
  return render(<SchoolDocumentsAdmin heading="Documents" subtitle="Everything ingested" />)
}

describe('SchoolDocumentsAdmin inline editing', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedUseAuth.mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    mockedFetch.mockResolvedValue({ items: [row()], total: 1, page: 0, size: 25 })
  })

  it('turns the card into an editor and saves the title, text and year groups', async () => {
    const user = userEvent.setup()
    mockedEdit.mockResolvedValue({
      document: row({
        title: 'Year 3 spellings - Autumn 1 Week 3',
        body: `${SPELLINGS}\ndisagree`,
        preview: `${SPELLINGS}\ndisagree`,
        yearGroups: ['Year 3'],
      }),
      changed: true,
      eventRefreshFailed: false,
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /^Edit / }))

    const title = screen.getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'Year 3 spellings - Autumn 1 Week 3')
    await user.type(screen.getByLabelText('Text'), '\ndisagree')
    await user.click(screen.getByLabelText('Year 3'))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(mockedEdit).toHaveBeenCalledWith(getAccessToken, 'note-1', {
      title: 'Year 3 spellings - Autumn 1 Week 3',
      body: `${SPELLINGS}\ndisagree`,
      yearGroups: ['Year 3'],
    })
    // Back to the read-only card, showing what was stored.
    await waitFor(() => expect(screen.queryByLabelText('Text')).not.toBeInTheDocument())
    expect(
      screen.getByRole('heading', { name: 'Year 3 spellings - Autumn 1 Week 3' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Year 3', { selector: '.school-admin__tag' })).toBeInTheDocument()
    expect(screen.getByText(/Saved\./)).toBeInTheDocument()
  })

  it('cancel discards the draft without saving', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /^Edit / }))
    await user.type(screen.getByLabelText('Text'), ' typo')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(mockedEdit).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Text')).not.toBeInTheDocument()
    expect(screen.getByText(/unusual$/)).toBeInTheDocument()
  })

  it('will not save a blank text', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /^Edit / }))
    await user.clear(screen.getByLabelText('Text'))

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('warns that a crawled page may be overwritten', async () => {
    const user = userEvent.setup()
    mockedFetch.mockResolvedValue({
      items: [row({ sourceType: 'WEBSITE_PAGE', editsOverwrittenByCrawl: true })],
      total: 1,
      page: 0,
      size: 25,
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /^Edit / }))

    expect(screen.getByText(/next crawl may replace this edit/)).toBeInTheDocument()
  })

  it('says so when the dates could not be re-read', async () => {
    const user = userEvent.setup()
    mockedEdit.mockResolvedValue({ document: row(), changed: true, eventRefreshFailed: true })
    renderPage()

    await user.click(await screen.findByRole('button', { name: /^Edit / }))
    await user.type(screen.getByLabelText('Text'), ' more')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/dates could not be re-read/)).toBeInTheDocument()
  })

  it('keeps the editor open with the error when the save fails', async () => {
    const user = userEvent.setup()
    mockedEdit.mockRejectedValue(new Error('A document needs a title'))
    renderPage()

    await user.click(await screen.findByRole('button', { name: /^Edit / }))
    await user.type(screen.getByLabelText('Text'), ' more')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('A document needs a title')).toBeInTheDocument()
    const editor = screen.getByLabelText('Text')
    expect(editor).toHaveValue(`${SPELLINGS} more`)
    expect(within(editor.closest('li')!).getByRole('button', { name: 'Save' })).toBeEnabled()
  })
})
