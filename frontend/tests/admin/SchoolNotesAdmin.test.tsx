import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { SchoolNotesAdmin } from '../../src/pages/admin/SchoolNotesAdmin'
import type { SchoolNote } from '../../src/services/adminApi'

// Stable across renders: a fresh object changes getAccessToken's identity, which re-fires the
// load effect and silently undoes anything the test just did.
const auth = { getAccessToken: async () => 'token' }
vi.mock('../../src/auth/useAuth', () => ({ useAuth: () => auth }))

const createSchoolNote = vi.fn()
const fetchSchoolNote = vi.fn()
const fetchSchoolNotes = vi.fn()
vi.mock('../../src/services/adminApi', () => ({
  createSchoolNote: (...a: unknown[]) => createSchoolNote(...a),
  fetchSchoolNote: (...a: unknown[]) => fetchSchoolNote(...a),
  fetchSchoolNotes: (...a: unknown[]) => fetchSchoolNotes(...a),
}))

const WHATSAPP = [
  'Trinity C of E school - open morning Sat 19 Sept: https://www.trinity.lewisham.sch.uk/Secondary',
  'Harris Boys - East Dulwich - 17 Sept - https://www.harrisdulwichboys.org.uk/open-events',
].join('\n')

function note(overrides: Partial<SchoolNote> = {}): SchoolNote {
  return {
    id: 'note-1',
    title: 'Secondary open evenings',
    body: WHATSAPP,
    publishedAt: '2026-09-14T19:00:00Z',
    yearGroups: ['Year 6'],
    events: [],
    links: [],
    fetching: false,
    ...overrides,
  }
}

function event(overrides: Record<string, unknown> = {}) {
  return {
    id: 'e-1',
    title: 'Trinity C of E School open morning',
    startDate: '2026-09-19',
    endDate: '2026-09-19',
    eventType: 'OTHER',
    yearGroups: ['Year 6'],
    academicYear: '2026/27',
    sourceType: 'PASTED_NOTE',
    visibility: 'PUBLIC',
    ...overrides,
  }
}

describe('SchoolNotesAdmin', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    createSchoolNote.mockReset()
    fetchSchoolNote.mockReset()
    fetchSchoolNotes.mockReset()
    fetchSchoolNotes.mockResolvedValue([])
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('will not save an empty note', async () => {
    render(<SchoolNotesAdmin />)

    // Not a validation message after the fact: there is nothing to read out of blank text, and
    // an enabled button here would cost a round trip to be told so.
    expect(await screen.findByRole('button', { name: /save note/i })).toBeDisabled()
  })

  it('sends the pasted text with the selected year groups', async () => {
    createSchoolNote.mockResolvedValue(note())
    render(<SchoolNotesAdmin />)

    fireEvent.change(screen.getByLabelText(/the messages/i), { target: { value: WHATSAPP } })
    fireEvent.click(screen.getByRole('button', { name: /save note/i }))

    await waitFor(() =>
      expect(createSchoolNote).toHaveBeenCalledWith(expect.anything(), {
        text: WHATSAPP,
        title: '',
        // Year 6 by default: the whole reason this screen exists is secondary transfer, and
        // an unticked default would put four secondary open days into every year's calendar.
        yearGroups: ['Year 6'],
      }),
    )
  })

  it('shows the dates that were read out of the note', async () => {
    createSchoolNote.mockResolvedValue(note({ events: [event()] }))
    render(<SchoolNotesAdmin />)

    fireEvent.change(screen.getByLabelText(/the messages/i), { target: { value: WHATSAPP } })
    fireEvent.click(screen.getByRole('button', { name: /save note/i }))

    // The extraction is a model call over somebody's WhatsApp messages, so the operator has to
    // be able to see what it understood. A silent success here is a silent wrong date.
    expect(await screen.findByText(/1 date found/i)).toBeInTheDocument()
    expect(screen.getByText(/Trinity C of E School open morning/)).toBeInTheDocument()
    expect(screen.getByText('2026-09-19')).toBeInTheDocument()
  })

  it('polls while the linked pages are still being read, then stops', async () => {
    createSchoolNote.mockResolvedValue(note({ fetching: true }))
    fetchSchoolNote.mockResolvedValue(note({ fetching: false, events: [event()] }))
    render(<SchoolNotesAdmin />)

    fireEvent.change(screen.getByLabelText(/the messages/i), { target: { value: WHATSAPP } })
    fireEvent.click(screen.getByRole('button', { name: /save note/i }))
    expect(await screen.findByText(/reading the linked pages/i)).toBeInTheDocument()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })
    await waitFor(() => expect(fetchSchoolNote).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/1 date found/i)).toBeInTheDocument()

    // Once the server reports it has finished, the page must stop asking. The flag is held in
    // memory server-side, so a restart mid-fetch reports false with links still pending — a
    // loop that kept going on link status alone would never terminate.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(9000)
    })
    expect(fetchSchoolNote).toHaveBeenCalledTimes(1)
  })

  it('reports a failed save without losing what was typed', async () => {
    createSchoolNote.mockRejectedValue(new Error('Nothing to save'))
    render(<SchoolNotesAdmin />)

    fireEvent.change(screen.getByLabelText(/the messages/i), { target: { value: WHATSAPP } })
    fireEvent.click(screen.getByRole('button', { name: /save note/i }))

    expect(await screen.findByText('Nothing to save')).toBeInTheDocument()
    // The text is only cleared on success. Losing a transcription because the server was down
    // means retyping it from the phone it came from.
    expect(screen.getByLabelText(/the messages/i)).toHaveValue(WHATSAPP)
  })

  it('still renders the form when past notes cannot be loaded', async () => {
    fetchSchoolNotes.mockRejectedValue(new Error('down'))
    render(<SchoolNotesAdmin />)

    expect(await screen.findByLabelText(/the messages/i)).toBeInTheDocument()
    expect(screen.queryByText(/recent notes/i)).not.toBeInTheDocument()
  })
})
