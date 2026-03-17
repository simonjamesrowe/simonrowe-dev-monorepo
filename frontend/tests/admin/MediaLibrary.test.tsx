import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { MediaLibrary } from '../../src/components/admin/MediaLibrary'

vi.mock('../../src/services/adminApi', () => ({
  fetchAdminMedia: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import { fetchAdminMedia } from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const mockFetchAdminMedia = vi.mocked(fetchAdminMedia)
const mockUseAuth = vi.mocked(useAuth)

const mockGetAccessToken = vi.fn().mockResolvedValue('test-token')

function makeAsset(id: string, fileName: string, mimeType = 'image/jpeg'): import('../../src/services/adminApi').MediaAsset {
  return {
    id,
    fileName,
    mimeType,
    fileSize: 1024,
    originalPath: `/uploads/${fileName}`,
    variants: {
      thumbnail: { path: `/uploads/thumb_${fileName}`, width: 150, height: 150, fileSize: 256 },
    },
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
  }
}

const mockAssets = [
  makeAsset('asset-1', 'photo.jpg'),
  makeAsset('asset-2', 'banner.png', 'image/png'),
  makeAsset('asset-3', 'logo.svg', 'image/svg+xml'),
]

describe('MediaLibrary', () => {
  let onSelect: ReturnType<typeof vi.fn>
  let onClose: ReturnType<typeof vi.fn>

  beforeEach(() => {
    onSelect = vi.fn()
    onClose = vi.fn()
    mockUseAuth.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: undefined,
      login: vi.fn(),
      logout: vi.fn(),
      getAccessToken: mockGetAccessToken,
    })
    mockFetchAdminMedia.mockReset()
  })

  it('renders loading state initially', () => {
    mockFetchAdminMedia.mockImplementation(() => new Promise(() => {}))

    render(<MediaLibrary onSelect={onSelect} onClose={onClose} />)

    expect(screen.getByText('Loading media...')).toBeInTheDocument()
  })

  it('renders grid of media thumbnails after loading', async () => {
    mockFetchAdminMedia.mockResolvedValue({
      content: mockAssets,
      totalElements: 3,
      totalPages: 1,
      size: 20,
      number: 0,
    })

    render(<MediaLibrary onSelect={onSelect} onClose={onClose} />)

    await waitFor(() => {
      expect(screen.getByAltText('photo.jpg')).toBeInTheDocument()
      expect(screen.getByAltText('banner.png')).toBeInTheDocument()
      expect(screen.getByAltText('logo.svg')).toBeInTheDocument()
    })
  })

  it('calls onSelect when an image is clicked', async () => {
    mockFetchAdminMedia.mockResolvedValue({
      content: mockAssets,
      totalElements: 3,
      totalPages: 1,
      size: 20,
      number: 0,
    })

    render(<MediaLibrary onSelect={onSelect} onClose={onClose} />)

    await waitFor(() => {
      expect(screen.getByAltText('photo.jpg')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByAltText('photo.jpg').closest('button')!)

    expect(onSelect).toHaveBeenCalledWith(mockAssets[0])
    expect(onClose).toHaveBeenCalled()
  })

  it('calls onClose when close button is clicked', async () => {
    mockFetchAdminMedia.mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 0,
      size: 20,
      number: 0,
    })

    render(<MediaLibrary onSelect={onSelect} onClose={onClose} />)

    await waitFor(() => {
      expect(screen.queryByText('Loading media...')).not.toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: 'Close media library' }))

    expect(onClose).toHaveBeenCalled()
  })

  it('search input filters results by file name', async () => {
    mockFetchAdminMedia.mockResolvedValue({
      content: mockAssets,
      totalElements: 3,
      totalPages: 1,
      size: 20,
      number: 0,
    })

    render(<MediaLibrary onSelect={onSelect} onClose={onClose} />)

    await waitFor(() => {
      expect(screen.getByAltText('photo.jpg')).toBeInTheDocument()
    })

    fireEvent.change(screen.getByPlaceholderText('Search by file name...'), {
      target: { value: 'photo' },
    })

    expect(screen.getByAltText('photo.jpg')).toBeInTheDocument()
    expect(screen.queryByAltText('banner.png')).not.toBeInTheDocument()
    expect(screen.queryByAltText('logo.svg')).not.toBeInTheDocument()
  })

  it('shows empty state message when no assets are found', async () => {
    mockFetchAdminMedia.mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 0,
      size: 20,
      number: 0,
    })

    render(<MediaLibrary onSelect={onSelect} onClose={onClose} />)

    await waitFor(() => {
      expect(screen.getByText('No media assets found.')).toBeInTheDocument()
    })
  })
})
