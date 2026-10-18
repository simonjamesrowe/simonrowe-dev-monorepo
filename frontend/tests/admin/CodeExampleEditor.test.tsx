import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CodeExampleEditor } from '../../src/pages/admin/CodeExampleEditor'

vi.mock('@mdxeditor/editor', async () => (await import('../testUtils/mdxEditorMock')).mdxEditorMock)

vi.mock('../../src/services/adminApi', () => ({
  fetchAdminCodeExampleById: vi.fn(),
  createAdminCodeExample: vi.fn(),
  updateAdminCodeExample: vi.fn(),
  fetchAdminSkills: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

vi.mock('../../src/hooks/useUnsavedChanges', () => ({
  useUnsavedChanges: vi.fn(),
}))

import {
  createAdminCodeExample,
  fetchAdminCodeExampleById,
  fetchAdminSkills,
  updateAdminCodeExample,
  type AdminCodeExample,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

const example: AdminCodeExample = {
  id: 'ex-1',
  title: 'Virtual threads',
  description: 'Loom in practice.',
  language: 'kotlin',
  code: 'fun main() {}',
  skills: ['skill-java'],
  createdAt: '',
  updatedAt: '',
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<CodeExampleEditor />} path="/admin/code-examples/:id" />
        <Route element={<p>Code examples list</p>} path="/admin/code-examples" />
      </Routes>
    </MemoryRouter>,
  )
}

describe('CodeExampleEditor', () => {
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
    vi.mocked(fetchAdminCodeExampleById).mockResolvedValue(example)
    vi.mocked(createAdminCodeExample).mockResolvedValue(example)
    vi.mocked(updateAdminCodeExample).mockResolvedValue(example)
  })

  it('names every field by its visible label', async () => {
    renderAt('/admin/code-examples/new')
    await screen.findByRole('heading', { name: 'New Code Example' })

    expect(screen.getByLabelText('Title')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Language')).toHaveValue('java')
    expect(screen.getByLabelText('Skills')).toHaveRole('combobox')
  })

  it('creates an example from the labelled fields, including a skill picked by label', async () => {
    renderAt('/admin/code-examples/new')
    await screen.findByRole('heading', { name: 'New Code Example' })

    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Records' } })
    fireEvent.change(screen.getByLabelText('Language'), { target: { value: 'go' } })
    fireEvent.focus(screen.getByLabelText('Skills'))
    fireEvent.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'Java' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(createAdminCodeExample).toHaveBeenCalledTimes(1))
    expect(vi.mocked(createAdminCodeExample).mock.calls[0][1]).toEqual({
      title: 'Records',
      description: '',
      language: 'go',
      code: '',
      skills: ['skill-java'],
    })
    expect(await screen.findByText('Code examples list')).toBeInTheDocument()
  })

  it('loads an existing example and saves every field back unchanged', async () => {
    renderAt('/admin/code-examples/ex-1')
    await screen.findByRole('heading', { name: 'Edit Code Example' })

    expect(screen.getByLabelText('Title')).toHaveValue('Virtual threads')
    expect(screen.getByLabelText('Language')).toHaveValue('kotlin')
    expect(screen.getByRole('button', { name: 'Remove Java' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(updateAdminCodeExample).toHaveBeenCalledTimes(1))
    expect(vi.mocked(updateAdminCodeExample).mock.calls[0].slice(1)).toEqual([
      'ex-1',
      {
        title: 'Virtual threads',
        description: 'Loom in practice.',
        language: 'kotlin',
        code: 'fun main() {}',
        skills: ['skill-java'],
      },
    ])
  })
})
