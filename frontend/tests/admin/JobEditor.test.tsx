import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { JobEditor } from '../../src/pages/admin/JobEditor'

vi.mock('@mdxeditor/editor', async () => (await import('../testUtils/mdxEditorMock')).mdxEditorMock)

vi.mock('../../src/services/adminApi', () => ({
  createAdminJob: vi.fn(),
  updateAdminJob: vi.fn(),
  fetchAdminJobById: vi.fn(),
  fetchAdminSkills: vi.fn(),
  uploadAdminMedia: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

vi.mock('../../src/hooks/useUnsavedChanges', () => ({
  useUnsavedChanges: vi.fn(),
}))

import {
  createAdminJob,
  fetchAdminJobById,
  fetchAdminSkills,
  updateAdminJob,
  type AdminJob,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

const job: AdminJob = {
  id: 'job-1',
  title: 'Head of Engineering',
  company: 'Acme',
  companyUrl: 'https://acme.example',
  companyImage: { url: '/uploads/acme.png' },
  startDate: '2020-01-01',
  endDate: '2023-06-30',
  location: 'London',
  shortDescription: 'Led the platform teams.',
  longDescription: 'Long story.',
  education: false,
  includeOnResume: true,
  skills: ['Java'],
  createdAt: '2020-01-01T00:00:00Z',
  updatedAt: '2020-01-01T00:00:00Z',
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<JobEditor />} path="/admin/jobs/:id" />
        <Route element={<p>Jobs list</p>} path="/admin/jobs" />
      </Routes>
    </MemoryRouter>,
  )
}

describe('JobEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    vi.mocked(fetchAdminSkills).mockResolvedValue({
      content: [
        { id: 'skill-java', name: 'Java', rating: 9, description: null, image: null, order: 0, createdAt: '', updatedAt: '' },
        { id: 'skill-go', name: 'Go', rating: 6, description: null, image: null, order: 1, createdAt: '', updatedAt: '' },
      ],
      totalElements: 2,
      totalPages: 1,
      size: 100,
      number: 0,
    })
    vi.mocked(fetchAdminJobById).mockResolvedValue(job)
    vi.mocked(createAdminJob).mockResolvedValue(job)
    vi.mocked(updateAdminJob).mockResolvedValue(job)
  })

  /** Each visible label names its control, which is what the `htmlFor`/`id` pairs are for. */
  it('names every field by its visible label', async () => {
    renderAt('/admin/jobs/new')
    await screen.findByRole('heading', { name: 'New Job' })

    expect(screen.getByLabelText('Title')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Company')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Company URL')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Start Date')).toHaveAttribute('type', 'date')
    expect(screen.getByLabelText('End Date')).toHaveAttribute('type', 'date')
    expect(screen.getByLabelText('Location')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Skills')).toHaveRole('combobox')
    expect(screen.getByLabelText('Short Description').tagName).toBe('TEXTAREA')
    expect(screen.getByRole('group', { name: 'Company Image' })).toBeInTheDocument()
  })

  it('creates a job from what was typed into the labelled fields', async () => {
    renderAt('/admin/jobs/new')
    await screen.findByRole('heading', { name: 'New Job' })

    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Staff Engineer' } })
    fireEvent.change(screen.getByLabelText('Company'), { target: { value: 'Globex' } })
    fireEvent.change(screen.getByLabelText('Company URL'), { target: { value: 'https://globex.example' } })
    fireEvent.change(screen.getByLabelText('Start Date'), { target: { value: '2024-02-01' } })
    fireEvent.change(screen.getByLabelText('End Date'), { target: { value: '2025-03-31' } })
    fireEvent.change(screen.getByLabelText('Location'), { target: { value: 'Remote' } })
    fireEvent.change(screen.getByLabelText('Short Description'), { target: { value: 'Built things.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(createAdminJob).toHaveBeenCalledTimes(1))
    expect(vi.mocked(createAdminJob).mock.calls[0][1]).toMatchObject({
      title: 'Staff Engineer',
      company: 'Globex',
      companyUrl: 'https://globex.example',
      startDate: '2024-02-01',
      endDate: '2025-03-31',
      location: 'Remote',
      shortDescription: 'Built things.',
      skills: [],
    })
    expect(await screen.findByText('Jobs list')).toBeInTheDocument()
  })

  it('loads an existing job into the labelled fields and saves it as an update', async () => {
    renderAt('/admin/jobs/job-1')
    await screen.findByRole('heading', { name: 'Edit Job' })

    expect(screen.getByLabelText('Title')).toHaveValue('Head of Engineering')
    expect(screen.getByLabelText('Company')).toHaveValue('Acme')
    expect(screen.getByLabelText('Company URL')).toHaveValue('https://acme.example')
    expect(screen.getByLabelText('Start Date')).toHaveValue('2020-01-01')
    expect(screen.getByLabelText('End Date')).toHaveValue('2023-06-30')
    expect(screen.getByLabelText('Location')).toHaveValue('London')
    expect(screen.getByLabelText('Short Description')).toHaveValue('Led the platform teams.')

    fireEvent.change(screen.getByLabelText('Location'), { target: { value: 'Manchester' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(updateAdminJob).toHaveBeenCalledTimes(1))
    const [, id, payload] = vi.mocked(updateAdminJob).mock.calls[0]
    expect(id).toBe('job-1')
    expect(payload).toMatchObject({
      title: 'Head of Engineering',
      location: 'Manchester',
      companyImage: { url: '/uploads/acme.png' },
      longDescription: 'Long story.',
    })
    expect(createAdminJob).not.toHaveBeenCalled()
  })

  it('reports a save failure and stays on the form', async () => {
    vi.mocked(createAdminJob).mockRejectedValue(new Error('Title already exists'))
    renderAt('/admin/jobs/new')
    await screen.findByRole('heading', { name: 'New Job' })

    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Dup' } })
    fireEvent.change(screen.getByLabelText('Company'), { target: { value: 'Acme' } })
    fireEvent.change(screen.getByLabelText('Start Date'), { target: { value: '2024-01-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Title already exists')).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveValue('Dup')
  })
})
