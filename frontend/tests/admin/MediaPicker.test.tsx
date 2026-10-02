import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/services/adminApi', () => ({
  uploadAdminMedia: vi.fn(),
  fetchAdminMedia: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 }),
}))
const getAccessToken = vi.fn().mockResolvedValue('token')
vi.mock('../../src/auth/useAuth', () => ({ useAuth: () => ({ getAccessToken }) }))

import { MediaPicker } from '../../src/components/admin/MediaPicker'
import { uploadAdminMedia, type MediaAsset } from '../../src/services/adminApi'

const asset = (originalPath: string, mimeType: string): MediaAsset => ({
  id: 'a', fileName: 'f', mimeType, fileSize: 1, originalPath, variants: {}, createdAt: '', updatedAt: '',
})

function choose(file: File) {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  fireEvent.change(input, { target: { files: [file] } })
}

describe('MediaPicker', () => {
  beforeEach(() => vi.mocked(uploadAdminMedia).mockReset())

  it('uploads a video into the library and stores its path', async () => {
    const onChange = vi.fn()
    vi.mocked(uploadAdminMedia).mockResolvedValue(asset('/uploads/v/original.mp4', 'video/mp4'))
    render(<MediaPicker kind="video" label="Demo video" onChange={onChange} value={null} />)

    choose(new File(['x'], 'demo.mp4', { type: 'video/mp4' }))

    await waitFor(() => expect(onChange).toHaveBeenCalledWith('/uploads/v/original.mp4'))
  })

  it('refuses a file of the wrong kind before uploading it', async () => {
    render(<MediaPicker kind="video" label="Demo video" onChange={vi.fn()} value={null} />)

    choose(new File(['x'], 'photo.png', { type: 'image/png' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('MP4 video')
    expect(uploadAdminMedia).not.toHaveBeenCalled()
  })

  it('sends a .vtt the browser left untyped as text/vtt', async () => {
    vi.mocked(uploadAdminMedia).mockResolvedValue(asset('/uploads/c/original.vtt', 'text/vtt'))
    render(<MediaPicker kind="captions" label="Captions" onChange={vi.fn()} value={null} />)

    choose(new File(['WEBVTT'], 'demo.vtt', { type: '' }))

    await waitFor(() => expect(uploadAdminMedia).toHaveBeenCalled())
    expect(vi.mocked(uploadAdminMedia).mock.calls[0][1].type).toBe('text/vtt')
  })

  it('shows a chosen video as a player', () => {
    render(<MediaPicker kind="video" label="Demo video" onChange={vi.fn()} value="/uploads/v/original.mp4" />)
    expect(screen.getByLabelText('Selected video')).toHaveAttribute('controls')
  })
})
