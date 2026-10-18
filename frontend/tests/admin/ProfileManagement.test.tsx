import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ProfileManagement } from '../../src/components/admin/ProfileManagement'

vi.mock('../../src/hooks/useProfile', () => ({
  useProfile: vi.fn(),
}))

import { useProfile } from '../../src/hooks/useProfile'
import type { Profile } from '../../src/types/Profile'

describe('ProfileManagement', () => {
  beforeEach(() => {
    vi.mocked(useProfile).mockReturnValue({
      profile: {
        description: 'Engineering leader.',
        cvUrl: 'https://example.com/cv.pdf',
        profileImage: { url: '/uploads/me.jpg' },
      } as unknown as Profile,
      loading: false,
      error: null,
      retry: vi.fn(),
    })
  })

  it('names every field by its visible label and fills it from the profile', () => {
    render(<ProfileManagement />)

    expect(screen.getByLabelText('Short Biography')).toHaveValue('Engineering leader.')
    expect(screen.getByLabelText('Primary CV Link')).toHaveValue('https://example.com/cv.pdf')
    expect(screen.getByLabelText('Technical Portfolio PDF')).toHaveAttribute('readonly')
  })

  it('lets the biography be edited through its label', () => {
    render(<ProfileManagement />)

    fireEvent.change(screen.getByLabelText('Short Biography'), { target: { value: 'New bio.' } })

    expect(screen.getByLabelText('Short Biography')).toHaveValue('New bio.')
  })
})
