import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SchoolDocumentsAdmin } from '../../src/pages/admin/SchoolDocumentsAdmin'
import type { SchoolDocumentSummary } from '../../src/services/adminApi'

// Stable across renders: a fresh object changes getAccessToken's identity, which re-fires the
// load effect and silently undoes anything the test just did.
const auth = { getAccessToken: async () => 'token' }
vi.mock('../../src/auth/useAuth', () => ({ useAuth: () => auth }))

const fetchSchoolDocuments = vi.fn()
const bulkSchoolApproval = vi.fn()
const openSchoolAttachment = vi.fn()
const decideSchoolLink = vi.fn()
vi.mock('../../src/services/adminApi', () => ({
  fetchSchoolDocuments: (...a: unknown[]) => fetchSchoolDocuments(...a),
  bulkSchoolApproval: (...a: unknown[]) => bulkSchoolApproval(...a),
  openSchoolAttachment: (...a: unknown[]) => openSchoolAttachment(...a),
  decideSchoolLink: (...a: unknown[]) => decideSchoolLink(...a),
}))

function doc(overrides: Partial<SchoolDocumentSummary> = {}): SchoolDocumentSummary {
  return {
    id: 'doc-1',
    title: 'Weekly newsletter',
    preview: 'Half term runs from 26 October.',
    sourceType: 'EMAIL',
    sourceRef: 'msg-1',
    publishedAt: '2026-09-01T09:00:00Z',
    visibility: 'RESTRICTED',
    proposedVisibility: 'PUBLIC',
    proposalReason: 'classifier proposed PUBLIC',
    approvedBy: null,
    approvedAt: null,
    hasAttachment: false,
    discoveredLinks: [],
    originalUrl: null,
    body: 'Half term runs from 26 October. Full body text continues well past the preview.',
    ...overrides,
  }
}

function page(items: SchoolDocumentSummary[], total = items.length) {
  return { items, total, page: 0, size: 25 }
}

describe('SchoolDocumentsAdmin', () => {
  beforeEach(() => {
    fetchSchoolDocuments.mockReset()
    bulkSchoolApproval.mockReset()
  })

  it('lists documents with their source and tier', async () => {
    fetchSchoolDocuments.mockResolvedValue(page([doc()]))
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="all of them" />)

    expect(await screen.findByText('Weekly newsletter')).toBeInTheDocument()
    expect(screen.getByText('EMAIL')).toBeInTheDocument()
    expect(screen.getByText('RESTRICTED')).toBeInTheDocument()
  })

  it('selects every row on the page with one control', async () => {
    fetchSchoolDocuments.mockResolvedValue(
      page([doc(), doc({ id: 'doc-2', title: 'Second' })]),
    )
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="all of them" />)

    fireEvent.click(await screen.findByLabelText('Select all on this page'))
    expect(screen.getByText('2 selected')).toBeInTheDocument()
  })

  it('reports what a bulk approve actually did, not what was asked for', async () => {
    // Ids can go missing between the page loading and the button being pressed. Reporting the
    // request rather than the outcome would leave the operator believing they had published
    // more than they had.
    fetchSchoolDocuments.mockResolvedValue(page([doc()]))
    bulkSchoolApproval.mockResolvedValue({
      approved: 1,
      declined: 0,
      revoked: 0,
      missing: 2,
    })
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="all of them" />)

    fireEvent.click(await screen.findByLabelText('Select all on this page'))
    fireEvent.click(screen.getByRole('button', { name: /Make selected documents public/ }))

    expect(await screen.findByText(/1 made public/)).toBeInTheDocument()
    expect(screen.getByText(/2 not found/)).toBeInTheDocument()
  })

  it('passes the fixed status through so Approvals is a filtered view', async () => {
    fetchSchoolDocuments.mockResolvedValue(page([]))
    render(<SchoolDocumentsAdmin fixedStatus="awaiting" heading="Approvals" subtitle="x" />)

    await waitFor(() => expect(fetchSchoolDocuments).toHaveBeenCalled())
    expect(fetchSchoolDocuments.mock.calls[0][1]).toMatchObject({ status: 'awaiting' })
  })

  it('searches on submit rather than on every keystroke', async () => {
    fetchSchoolDocuments.mockResolvedValue(page([]))
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)
    await waitFor(() => expect(fetchSchoolDocuments).toHaveBeenCalledTimes(1))

    fireEvent.change(screen.getByLabelText('Search documents'), {
      target: { value: 'lunch' },
    })
    // Still one call: typing must not fire a request per character against a regex scan.
    expect(fetchSchoolDocuments).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() =>
      expect(fetchSchoolDocuments.mock.calls.at(-1)?.[1]).toMatchObject({ q: 'lunch' }),
    )
  })

  it('offers no name-gate override, because there is no name gate left to override', async () => {
    // The gate was removed on the owner's instruction: it blocked 98 of 99 school broadcasts
    // and could not tell a catering company from a child. This pins that the override control
    // went with it, rather than lingering as a second publish button that means nothing.
    fetchSchoolDocuments.mockResolvedValue(page([doc()]))
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    await screen.findByLabelText('Select all on this page')
    expect(screen.queryByRole('button', { name: /anyway/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/looks like it names/)).not.toBeInTheDocument()
  })

  it('never sends force on the ordinary approve path', async () => {
    fetchSchoolDocuments.mockResolvedValue(page([doc()]))
    bulkSchoolApproval.mockResolvedValue({
      approved: 1, declined: 0, revoked: 0, missing: 0,
    })
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    fireEvent.click(await screen.findByLabelText('Select all on this page'))
    fireEvent.click(screen.getByRole('button', { name: /Make selected documents public/ }))

    await waitFor(() => expect(bulkSchoolApproval).toHaveBeenCalled())
    expect(bulkSchoolApproval.mock.calls[0][3]).toBe(false)
  })

  it('expands to the full body and collapses again', async () => {
    // A 300-character preview is nowhere near enough to judge a newsletter by, which is the
    // entire job of this page.
    fetchSchoolDocuments.mockResolvedValue(
      page([doc({ preview: 'Half term runs…', body: 'Half term runs from 26 October.' })]),
    )
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    fireEvent.click(await screen.findByRole('button', { name: /Show more/ }))
    expect(screen.getByText('Half term runs from 26 October.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Show less/ }))
    expect(screen.queryByText('Half term runs from 26 October.')).toBeNull()
  })

  it('offers a PDF only when one is stored, and fetches it with auth', async () => {
    fetchSchoolDocuments.mockResolvedValue(page([doc({ hasAttachment: true })]))
    openSchoolAttachment.mockResolvedValue(undefined)
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    fireEvent.click(await screen.findByRole('button', { name: /Open PDF/ }))
    await waitFor(() => expect(openSchoolAttachment).toHaveBeenCalled())
    expect(openSchoolAttachment.mock.calls[0][1]).toBe('doc-1')
  })

  it('shows no PDF control for a document without one', async () => {
    fetchSchoolDocuments.mockResolvedValue(page([doc({ hasAttachment: false })]))
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    await screen.findByText('Weekly newsletter')
    expect(screen.queryByRole('button', { name: /Open PDF/ })).toBeNull()
  })

  it('lists links found in a message without making them clickable', async () => {
    // Clicking is the decision being made. Rendering the URL as an anchor invites exactly the
    // accidental click on a tracking link that this queue exists to prevent.
    fetchSchoolDocuments.mockResolvedValue(page([doc({
      discoveredLinks: [{
        id: 'link-1',
        sourceDocumentId: 'doc-1',
        url: 'https://example.org/menu.pdf',
        anchorText: 'Autumn menu',
        likelyKind: 'PDF document',
        status: 'PENDING' as const,
        failureReason: null,
      }],
    })]))
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    expect(await screen.findByText('Autumn menu')).toBeInTheDocument()
    expect(screen.getByText('https://example.org/menu.pdf').tagName).toBe('CODE')
    expect(screen.getByText(/none have been followed/)).toBeInTheDocument()
  })

  it('fetches a link only when asked', async () => {
    const link = {
      id: 'link-1',
      sourceDocumentId: 'doc-1',
      url: 'https://example.org/menu.pdf',
      anchorText: 'Autumn menu',
      likelyKind: 'PDF document',
      status: 'PENDING' as const,
      failureReason: null,
    }
    fetchSchoolDocuments.mockResolvedValue(page([doc({ discoveredLinks: [link] })]))
    decideSchoolLink.mockResolvedValue({ ...link, status: 'FETCHED' })
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    // Nothing requested on render.
    await screen.findByText('Autumn menu')
    expect(decideSchoolLink).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: /Fetch/ }))
    await waitFor(() => expect(decideSchoolLink).toHaveBeenCalled())
    expect(decideSchoolLink.mock.calls[0][2]).toBe('fetch')
  })

  it('surfaces why a fetch failed', async () => {
    const link = {
      id: 'link-1',
      sourceDocumentId: 'doc-1',
      url: 'https://example.org/x',
      anchorText: 'Something',
      likelyKind: 'Web page',
      status: 'PENDING' as const,
      failureReason: null,
    }
    fetchSchoolDocuments.mockResolvedValue(page([doc({ discoveredLinks: [link] })]))
    decideSchoolLink.mockResolvedValue({
      ...link, status: 'FAILED', failureReason: 'The server returned HTTP 403',
    })
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    fireEvent.click(await screen.findByRole('button', { name: /Fetch/ }))
    // Shown twice on purpose: once in the page banner, once against the link that failed.
    await waitFor(() => expect(screen.getAllByText(/HTTP 403/).length).toBeGreaterThan(0))
  })

  it('offers a per-row publish control rather than only the bulk bar', async () => {
    // An already-public website PDF needs one button, not a checkbox plus a toolbar trip.
    fetchSchoolDocuments.mockResolvedValue(page([doc({ visibility: 'PUBLIC' })]))
    bulkSchoolApproval.mockResolvedValue({
      approved: 0, declined: 0, revoked: 1, missing: 0,
    })
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    fireEvent.click(await screen.findByRole('button', { name: /Make private/ }))
    await waitFor(() => expect(bulkSchoolApproval).toHaveBeenCalled())
    expect(bulkSchoolApproval.mock.calls[0][1]).toEqual(['doc-1'])
    expect(bulkSchoolApproval.mock.calls[0][2]).toBe('revoke')
  })

  it('links to the original for a website PDF', async () => {
    // Website PDFs are not copied into the attachment store — the school already hosts them,
    // so the honest control is a link out rather than a download of our own copy.
    fetchSchoolDocuments.mockResolvedValue(page([doc({
      sourceType: 'PDF',
      originalUrl: 'https://www.kilmorieschool.co.uk/attachments/download.asp?file=819',
      hasAttachment: false,
    })]))
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    const link = await screen.findByRole('link', { name: /View PDF/ })
    expect(link).toHaveAttribute(
      'href',
      'https://www.kilmorieschool.co.uk/attachments/download.asp?file=819',
    )
  })

  it('shows the total count across every page, not just the rows on this one', async () => {
    fetchSchoolDocuments.mockResolvedValue(page([doc()], 137))
    render(<SchoolDocumentsAdmin heading="Documents" subtitle="x" />)

    expect(await screen.findByText(/137 total/)).toBeInTheDocument()
  })
})
