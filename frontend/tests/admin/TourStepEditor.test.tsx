import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { TourStepEditor } from '../../src/pages/admin/TourStepEditor'

vi.mock('@mdxeditor/editor', async () => (await import('../testUtils/mdxEditorMock')).mdxEditorMock)

vi.mock('../../src/services/adminApi', () => ({
  fetchAdminTourStepById: vi.fn(),
  createAdminTourStep: vi.fn(),
  updateAdminTourStep: vi.fn(),
  uploadAdminMedia: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

vi.mock('../../src/hooks/useUnsavedChanges', () => ({
  useUnsavedChanges: vi.fn(),
}))

import {
  createAdminTourStep,
  fetchAdminTourStepById,
  type AdminTourStep,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<TourStepEditor />} path="/admin/tour-steps/:id" />
        <Route element={<p>Tour steps list</p>} path="/admin/tour-steps" />
      </Routes>
    </MemoryRouter>,
  )
}

describe('TourStepEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    vi.mocked(createAdminTourStep).mockResolvedValue({} as AdminTourStep)
  })

  it('names every field, the image picker included, by its visible label', async () => {
    renderAt('/admin/tour-steps/new')
    await screen.findByRole('heading', { name: 'New Tour Step' })

    expect(screen.getByLabelText('Title')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('CSS Selector')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Position')).toHaveValue('')
    expect(screen.getByLabelText('Route')).toHaveValue('/')
    expect(screen.getByLabelText('Order')).toHaveValue(0)
    expect(screen.getByLabelText('Pause before advancing (seconds)')).toHaveValue(null)
    expect(screen.getByRole('group', { name: 'Title Image' })).toBeInTheDocument()
  })

  it('loads an existing step with its title image', async () => {
    vi.mocked(fetchAdminTourStepById).mockResolvedValue({
      id: 'step-1',
      title: 'Search',
      selector: '.tour-search',
      description: null,
      titleImage: '/uploads/search.png',
      position: 'bottom',
      order: 2,
      route: '/blogs',
      autoAdvanceMs: 3000,
      createdAt: '',
      updatedAt: '',
    })
    renderAt('/admin/tour-steps/step-1')
    await screen.findByRole('heading', { name: 'Edit Tour Step' })

    expect(screen.getByLabelText('Title')).toHaveValue('Search')
    expect(screen.getByLabelText('Pause before advancing (seconds)')).toHaveValue(3)
    expect(screen.getByRole('img', { name: 'Selected image' })).toHaveAttribute('src', '/uploads/search.png')
  })

  it('creates a step from the labelled fields', async () => {
    renderAt('/admin/tour-steps/new')
    await screen.findByRole('heading', { name: 'New Tour Step' })

    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Ask' } })
    fireEvent.change(screen.getByLabelText('CSS Selector'), { target: { value: '.ask' } })
    fireEvent.change(screen.getByLabelText('Position'), { target: { value: 'left' } })
    fireEvent.change(screen.getByLabelText('Route'), { target: { value: '/about' } })
    fireEvent.change(screen.getByLabelText('Pause before advancing (seconds)'), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create Step' }))

    await waitFor(() => expect(createAdminTourStep).toHaveBeenCalledTimes(1))
    expect(vi.mocked(createAdminTourStep).mock.calls[0][1]).toMatchObject({
      title: 'Ask',
      selector: '.ask',
      position: 'left',
      route: '/about',
      autoAdvanceMs: 2000,
      titleImage: null,
    })
  })
})
