import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  createSchoolNote,
  fetchSchoolNote,
  fetchSchoolNotes,
  type SchoolNote,
} from '../../src/services/adminApi'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as Response
}

const NOTE: SchoolNote = {
  id: 'note-1',
  title: 'Secondary open evenings',
  body: 'Trinity - open morning Sat 19 Sept',
  publishedAt: '2026-09-14T19:00:00Z',
  yearGroups: ['Year 6'],
  events: [],
  links: [],
  fetching: false,
}

describe('school notes API', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    getAccessToken.mockClear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const fetchMock = () => vi.mocked(globalThis.fetch)

  it('posts the pasted text, title and year groups as JSON', async () => {
    fetchMock().mockResolvedValue(jsonResponse(NOTE))

    await createSchoolNote(getAccessToken, {
      text: 'Trinity - open morning Sat 19 Sept',
      title: 'Secondary open evenings',
      yearGroups: ['Year 6'],
    })

    const [url, init] = fetchMock().mock.calls[0] as [string, RequestInit]
    expect(url).toMatch(/\/admin\/school\/notes$/)
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({
      text: 'Trinity - open morning Sat 19 Sept',
      title: 'Secondary open evenings',
      yearGroups: ['Year 6'],
    })
  })

  it('defaults an omitted title and year-group selection rather than sending undefined', async () => {
    // `undefined` drops the key from the JSON body entirely, and the server's record
    // normalisation would then decide for us. Sending the empty values keeps the two ends
    // agreeing about what "no title" and "whole-school" mean.
    fetchMock().mockResolvedValue(jsonResponse(NOTE))

    await createSchoolNote(getAccessToken, { text: 'Some messages' })

    const [, init] = fetchMock().mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(init.body as string)).toEqual({
      text: 'Some messages',
      title: '',
      yearGroups: [],
    })
  })

  it('sends the admin bearer token on every call', async () => {
    fetchMock().mockResolvedValue(jsonResponse(NOTE))

    await createSchoolNote(getAccessToken, { text: 'x' })

    const [, init] = fetchMock().mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer test-token')
  })

  it('reads one note back by id, for polling', async () => {
    fetchMock().mockResolvedValue(jsonResponse({ ...NOTE, fetching: true }))

    const note = await fetchSchoolNote(getAccessToken, 'note-1')

    expect(fetchMock().mock.calls[0][0]).toMatch(/\/admin\/school\/notes\/note-1$/)
    expect(note.fetching).toBe(true)
  })

  it('reads the recent list with an explicit limit', async () => {
    fetchMock().mockResolvedValue(jsonResponse([NOTE]))

    const notes = await fetchSchoolNotes(getAccessToken, 5)

    expect(fetchMock().mock.calls[0][0]).toMatch(/\/admin\/school\/notes\?limit=5$/)
    expect(notes).toHaveLength(1)
  })

  it('defaults the recent limit rather than asking for everything', async () => {
    fetchMock().mockResolvedValue(jsonResponse([]))

    await fetchSchoolNotes(getAccessToken)

    expect(fetchMock().mock.calls[0][0]).toMatch(/limit=20$/)
  })

  it('throws on a failed response instead of returning a half-read note', async () => {
    fetchMock().mockResolvedValue(jsonResponse({ detail: 'nope' }, 400))

    await expect(createSchoolNote(getAccessToken, { text: '   ' })).rejects.toThrow()
  })
})
