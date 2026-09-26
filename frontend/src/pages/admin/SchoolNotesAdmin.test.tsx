import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuth } from '../../auth/useAuth'
import {
  fetchSchoolNotes,
  transcribeSchoolNoteImage,
} from '../../services/adminApi'
import { prepareSchoolNoteImage } from './schoolNoteImage'
import { SchoolNotesAdmin } from './SchoolNotesAdmin'

vi.mock('../../auth/useAuth', () => ({ useAuth: vi.fn() }))
vi.mock('../../services/adminApi', () => ({
  createSchoolNote: vi.fn(),
  fetchSchoolNote: vi.fn(),
  fetchSchoolNotes: vi.fn(),
  transcribeSchoolNoteImage: vi.fn(),
}))
vi.mock('./schoolNoteImage', () => ({ prepareSchoolNoteImage: vi.fn() }))

const getAccessToken = vi.fn().mockResolvedValue('token')
const mockedUseAuth = vi.mocked(useAuth)
const mockedFetchNotes = vi.mocked(fetchSchoolNotes)
const mockedTranscribe = vi.mocked(transcribeSchoolNoteImage)
const mockedPrepare = vi.mocked(prepareSchoolNoteImage)
const createObjectUrl = vi.fn<(object: Blob | MediaSource) => string>()
const revokeObjectUrl = vi.fn<(url: string) => void>()

describe('SchoolNotesAdmin photo transcription', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedUseAuth.mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    mockedFetchNotes.mockResolvedValue([])
    mockedPrepare.mockResolvedValue(new Blob(['small'], { type: 'image/jpeg' }))
    createObjectUrl.mockReturnValueOnce('blob:first').mockReturnValueOnce('blob:second')
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: createObjectUrl,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      value: revokeObjectUrl,
    })
  })

  it('appends transcribed text to what was already typed', async () => {
    const user = userEvent.setup()
    mockedTranscribe.mockResolvedValue({ text: 'Autumn Week 2\nplayed', title: 'Spellings' })
    render(<SchoolNotesAdmin />)

    await user.type(screen.getByLabelText('The messages'), 'Context from the parent')
    await user.upload(screen.getByLabelText('Choose a photo to transcribe'), photo('one.png'))

    await waitFor(() => {
      expect(screen.getByLabelText('The messages')).toHaveValue(
        'Context from the parent\n\nAutumn Week 2\nplayed',
      )
    })
  })

  it('fills an empty title but never overwrites one the operator typed', async () => {
    const user = userEvent.setup()
    mockedTranscribe.mockResolvedValue({ text: 'First page', title: 'Suggested title' })
    const { unmount } = render(<SchoolNotesAdmin />)

    await user.upload(screen.getByLabelText('Choose a photo to transcribe'), photo('one.png'))
    await waitFor(() => expect(screen.getByLabelText('Title (optional)')).toHaveValue(
      'Suggested title',
    ))
    unmount()

    render(<SchoolNotesAdmin />)
    fireEvent.change(screen.getByLabelText('Title (optional)'), {
      target: { value: 'My precise title' },
    })
    await user.upload(screen.getByLabelText('Choose a photo to transcribe'), photo('two.png'))
    await waitFor(() => expect(screen.getByLabelText('Title (optional)')).toHaveValue(
      'My precise title',
    ))
  })

  it('shows a transcription failure without changing the textarea', async () => {
    const user = userEvent.setup()
    mockedTranscribe.mockRejectedValue(new Error('Nothing readable came back from that photo.'))
    render(<SchoolNotesAdmin />)

    await user.type(screen.getByLabelText('The messages'), 'Keep this text')
    await user.upload(screen.getByLabelText('Choose a photo to transcribe'), photo('one.png'))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Nothing readable came back from that photo.',
    )
    expect(screen.getByLabelText('The messages')).toHaveValue('Keep this text')
  })

  it('revokes the previous object URL when the selected image is replaced', async () => {
    const user = userEvent.setup()
    mockedTranscribe
      .mockResolvedValueOnce({ text: 'First page', title: 'First' })
      .mockResolvedValueOnce({ text: 'Second page', title: 'Second' })
    render(<SchoolNotesAdmin />)

    const input = screen.getByLabelText('Choose a photo to transcribe')
    await user.upload(input, photo('one.png'))
    await waitFor(() => expect(mockedTranscribe).toHaveBeenCalledTimes(1))
    await user.upload(input, photo('two.png'))

    await waitFor(() => expect(revokeObjectUrl).toHaveBeenCalledWith('blob:first'))
  })
})

function photo(name: string): File {
  return new File(['pixels'], name, { type: 'image/png' })
}
