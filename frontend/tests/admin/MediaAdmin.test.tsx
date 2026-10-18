import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { MediaAdmin } from '../../src/pages/admin/MediaAdmin'

vi.mock('../../src/services/adminApi', () => ({
  fetchAdminMedia: vi.fn(),
  uploadAdminMedia: vi.fn(),
  deleteAdminMedia: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => {
  // One stable token getter: a new one per render would re-run the page's load effect.
  const auth = { getAccessToken: () => Promise.resolve('test-token') }
  return { useAuth: () => auth }
})

import { fetchAdminMedia, type MediaAsset } from '../../src/services/adminApi'

const asset: MediaAsset = {
  id: 'asset-1',
  fileName: 'photo.jpg',
  mimeType: 'image/jpeg',
  fileSize: 2048,
  originalPath: '/uploads/photo.jpg',
  variants: {},
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

describe('MediaAdmin', () => {
  beforeEach(() => {
    vi.mocked(fetchAdminMedia).mockResolvedValue({
      content: [asset],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    } as unknown as Awaited<ReturnType<typeof fetchAdminMedia>>)
  })

  it('selects and deselects a card from its file-name button', async () => {
    render(<MediaAdmin />)

    const select = await screen.findByRole('button', { name: 'photo.jpg' })
    expect(select).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(select)
    expect(select).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('/uploads/photo.jpg')).toBeInTheDocument()

    fireEvent.click(select)
    expect(select).toHaveAttribute('aria-pressed', 'false')
  })

  it('asks to confirm a delete without selecting the card', async () => {
    render(<MediaAdmin />)

    fireEvent.click(await screen.findByRole('button', { name: 'Delete photo.jpg' }))

    expect(screen.getByRole('button', { name: 'photo.jpg' })).toHaveAttribute('aria-pressed', 'false')
    // hidden: ConfirmDialog sets aria-hidden on its own backdrop (a known, separate bug).
    expect(screen.getByRole('dialog', { hidden: true })).toBeInTheDocument()
  })
})
