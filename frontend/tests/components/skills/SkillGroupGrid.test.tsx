import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SkillGroupGrid } from '../../../src/components/skills/SkillGroupGrid'
import type { ISkillGroup } from '../../../src/types/skill'

vi.mock('../../../src/services/skillsApi', () => ({
  fetchSkillGroups: vi.fn(),
}))

import { fetchSkillGroups } from '../../../src/services/skillsApi'

const groups: ISkillGroup[] = [
  { id: 'g-1', name: 'Backend', rating: 8.6, displayOrder: 1, skills: [] },
  { id: 'g-2', name: 'Cloud', rating: 6.9, displayOrder: 2, skills: [] },
]

describe('SkillGroupGrid', () => {
  beforeEach(() => {
    vi.mocked(fetchSkillGroups).mockReset()
  })

  it('renders a card per skill group once loaded', async () => {
    vi.mocked(fetchSkillGroups).mockResolvedValue(groups)

    render(<SkillGroupGrid onGroupClick={vi.fn()} />)

    await waitFor(() => {
      expect(screen.getByText('Backend')).toBeInTheDocument()
    })
    expect(screen.getByText('Cloud')).toBeInTheDocument()
  })

  it('renders an ErrorMessage frame with a retry action on failure', async () => {
    vi.mocked(fetchSkillGroups).mockRejectedValue(new Error('Unable to load skills data.'))

    render(<SkillGroupGrid onGroupClick={vi.fn()} />)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByRole('heading', { name: 'Unable to load skills' })).toBeInTheDocument()
    expect(screen.getByText('Unable to load skills data.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    // The bare paragraph it replaced is gone.
    expect(document.querySelector('.skill-group-grid__error')).toBeNull()
  })

  it('reissues the request and clears the error when Retry is pressed', async () => {
    vi.mocked(fetchSkillGroups).mockRejectedValueOnce(new Error('Unable to load skills data.'))

    render(<SkillGroupGrid onGroupClick={vi.fn()} />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    })

    vi.mocked(fetchSkillGroups).mockResolvedValueOnce(groups)
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => {
      expect(screen.getByText('Backend')).toBeInTheDocument()
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(vi.mocked(fetchSkillGroups)).toHaveBeenCalledTimes(2)
  })
})
