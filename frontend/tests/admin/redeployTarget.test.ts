import { describe, expect, it } from 'vitest'

import { redeployTarget } from '../../src/pages/admin/redeployTarget'

const BACKEND = { service: 'backend', commit: 'b'.repeat(40), commitTime: '2026-10-02T09:00:00Z' }
const FACTORY = {
  service: 'software-factory',
  commit: 'f'.repeat(40),
  commitTime: '2026-10-01T09:00:00Z',
}

describe('redeployTarget', () => {
  it('picks the frontend after a frontend-only merge', () => {
    expect(redeployTarget([BACKEND, FACTORY], 'a'.repeat(40), '2026-10-03T09:00:00Z')?.service)
      .toBe('frontend')
  })

  it('picks the factory when it was built last, rather than rolling it back', () => {
    const newerFactory = { ...FACTORY, commitTime: '2026-10-04T09:00:00Z' }
    expect(redeployTarget([BACKEND, newerFactory], 'a'.repeat(40), '2026-10-03T09:00:00Z'))
      .toEqual(newerFactory)
  })

  it('prefers the backend on equal times, matching the server', () => {
    expect(redeployTarget([BACKEND, FACTORY], BACKEND.commit, BACKEND.commitTime)).toEqual(BACKEND)
  })

  it('returns null when any of the three commits is unknown', () => {
    expect(redeployTarget([BACKEND], 'a'.repeat(40), '2026-10-03T09:00:00Z')).toBeNull()
    expect(redeployTarget([BACKEND, FACTORY], 'unknown', '2026-10-03T09:00:00Z')).toBeNull()
    expect(redeployTarget([BACKEND, FACTORY], 'a'.repeat(40), null)).toBeNull()
  })
})
