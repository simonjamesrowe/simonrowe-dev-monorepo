import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CodeExamplesAdmin } from '../../src/pages/admin/CodeExamplesAdmin'

vi.mock('../../src/services/adminApi', () => ({
  deleteAdminCodeExample: vi.fn(),
  fetchAdminCodeExamples: vi.fn(),
  fetchAdminSkills: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import { fetchAdminCodeExamples, fetchAdminSkills } from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

function renderPage() {
  return render(
    <MemoryRouter>
      <CodeExamplesAdmin />
    </MemoryRouter>,
  )
}

describe('CodeExamplesAdmin filters', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    vi.mocked(fetchAdminSkills).mockResolvedValue({
      content: [
        { id: 'skill-java', name: 'Java', rating: 9, description: null, image: null, order: 0, createdAt: '', updatedAt: '' },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 100,
      number: 0,
    })
    vi.mocked(fetchAdminCodeExamples).mockResolvedValue({
      content: [
        {
          id: 'ex-1',
          title: 'Virtual threads',
          description: '',
          language: 'java',
          code: '',
          skills: ['skill-java'],
          createdAt: '',
          updatedAt: '',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 20,
      number: 0,
    })
  })

  it('names each filter by its visible label', async () => {
    renderPage()
    await screen.findByText('Virtual threads')

    expect(screen.getByLabelText('Search')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Skill')).toHaveValue('')
    expect(screen.getByLabelText('Language')).toHaveValue('')
  })

  it('re-queries with each filter reached by its label', async () => {
    renderPage()
    await screen.findByText('Virtual threads')
    await screen.findByRole('option', { name: 'Java' })

    fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'loom' } })
    fireEvent.change(screen.getByLabelText('Skill'), { target: { value: 'skill-java' } })
    fireEvent.change(screen.getByLabelText('Language'), { target: { value: 'kotlin' } })

    await waitFor(() =>
      expect(fetchAdminCodeExamples).toHaveBeenLastCalledWith(getAccessToken, 0, 20, {
        search: 'loom',
        skill: 'skill-java',
        language: 'kotlin',
      }),
    )
  })
})
