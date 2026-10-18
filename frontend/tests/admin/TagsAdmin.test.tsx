import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { TagsAdmin } from '../../src/pages/admin/TagsAdmin'

vi.mock('../../src/services/adminApi', () => ({
  fetchAdminTags: vi.fn(),
  createAdminTag: vi.fn(),
  bulkCreateAdminTags: vi.fn(),
  updateAdminTag: vi.fn(),
  deleteAdminTag: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import { fetchAdminTags } from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

describe('TagsAdmin inline editing', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    vi.mocked(fetchAdminTags).mockResolvedValue([
      { id: 'tag-1', name: 'java', createdAt: '2026-01-01T00:00:00Z', updatedAt: '' },
    ])
  })

  it('moves focus into the editor the user just opened', async () => {
    render(<TagsAdmin />)
    await screen.findByText('java')

    fireEvent.click(screen.getByTitle('Edit'))

    expect(screen.getByDisplayValue('java')).toHaveFocus()
  })

  /**
   * Focus is taken once, on mount. A later re-render (here, typing in another field) must
   * not pull it back into the editor, which a ref recreated on each render would do.
   */
  it('does not take focus back on later re-renders', async () => {
    render(<TagsAdmin />)
    await screen.findByText('java')
    fireEvent.click(screen.getByTitle('Edit'))

    const other = screen.getByPlaceholderText('Tag name')
    other.focus()
    fireEvent.change(other, { target: { value: 'kotlin' } })

    expect(other).toHaveFocus()
    expect(screen.getByDisplayValue('java')).not.toHaveFocus()
  })
})
