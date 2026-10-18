import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SkillGroupEditor } from '../../src/pages/admin/SkillGroupEditor'

vi.mock('@mdxeditor/editor', async () => (await import('../testUtils/mdxEditorMock')).mdxEditorMock)

vi.mock('../../src/services/adminApi', () => ({
  createAdminSkillGroup: vi.fn(),
  updateAdminSkillGroup: vi.fn(),
  fetchAdminSkillGroupById: vi.fn(),
  fetchAdminSkills: vi.fn(),
  deleteAdminSkill: vi.fn(),
  uploadAdminMedia: vi.fn(),
  createAdminSkill: vi.fn(),
  updateAdminSkill: vi.fn(),
  fetchAdminSkillById: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

vi.mock('../../src/hooks/useUnsavedChanges', () => ({
  useUnsavedChanges: vi.fn(),
}))

import {
  createAdminSkillGroup,
  fetchAdminSkillGroupById,
  fetchAdminSkills,
  updateAdminSkillGroup,
  type AdminSkill,
  type AdminSkillGroup,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

const java: AdminSkill = {
  id: 'skill-java',
  name: 'Java',
  rating: 9,
  description: null,
  image: null,
  order: 0,
  createdAt: '',
  updatedAt: '',
}

const group = {
  id: 'group-1',
  name: 'Backend',
  rating: 8,
  description: 'Server-side work.',
  image: { url: '/uploads/backend.png' },
  order: 2,
  skills: ['skill-java'],
  createdAt: '',
  updatedAt: '',
} as unknown as AdminSkillGroup

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<SkillGroupEditor />} path="/admin/skills/:id" />
        <Route element={<p>Skills list</p>} path="/admin/skills" />
      </Routes>
    </MemoryRouter>,
  )
}

describe('SkillGroupEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    vi.mocked(fetchAdminSkills).mockResolvedValue({
      content: [java],
      totalElements: 1,
      totalPages: 1,
      size: 100,
      number: 0,
    })
    vi.mocked(fetchAdminSkillGroupById).mockResolvedValue(group)
    vi.mocked(createAdminSkillGroup).mockResolvedValue(group)
    vi.mocked(updateAdminSkillGroup).mockResolvedValue(group)
  })

  it('names every field by its visible label', async () => {
    renderAt('/admin/skills/new')
    await screen.findByRole('heading', { name: 'New Skill Group' })

    expect(screen.getByLabelText('Name')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Rating (0-10)')).toHaveAttribute('type', 'number')
    expect(screen.getByRole('group', { name: 'Image' })).toBeInTheDocument()
    expect(screen.getByText('No skills linked to this group yet.')).toBeInTheDocument()
  })

  it('creates a group from the labelled fields', async () => {
    renderAt('/admin/skills/new')
    await screen.findByRole('heading', { name: 'New Skill Group' })

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Frontend' } })
    fireEvent.change(screen.getByLabelText('Rating (0-10)'), { target: { value: '6' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(createAdminSkillGroup).toHaveBeenCalledTimes(1))
    expect(vi.mocked(createAdminSkillGroup).mock.calls[0][1]).toMatchObject({
      name: 'Frontend',
      rating: 6,
      skills: [],
    })
    expect(await screen.findByText('Skills list')).toBeInTheDocument()
  })

  it('loads an existing group, lists its skills and saves it as an update', async () => {
    renderAt('/admin/skills/group-1')
    await screen.findByRole('heading', { name: 'Edit Skill Group' })

    expect(screen.getByLabelText('Name')).toHaveValue('Backend')
    expect(screen.getByLabelText('Rating (0-10)')).toHaveValue(8)
    expect(await screen.findByRole('cell', { name: 'Java' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Back end' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(updateAdminSkillGroup).toHaveBeenCalledTimes(1))
    const [, id, payload] = vi.mocked(updateAdminSkillGroup).mock.calls[0]
    expect(id).toBe('group-1')
    expect(payload).toMatchObject({
      name: 'Back end',
      rating: 8,
      image: '/uploads/backend.png',
      order: 2,
      skills: ['skill-java'],
      description: 'Server-side work.',
    })
  })

  /**
   * The skill drawer is a dialog over the page: its labels are distinct from the group's,
   * so both forms stay reachable by label while it is open.
   */
  it('opens the skill drawer from Add Skill, and its backdrop closes it', async () => {
    renderAt('/admin/skills/new')
    await screen.findByRole('heading', { name: 'New Skill Group' })

    fireEvent.click(screen.getByRole('button', { name: 'Add Skill' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'New Skill' })).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Name')).toHaveAttribute('id', 'skill-editor-name')

    fireEvent.click(within(dialog).getByRole('heading', { name: 'New Skill' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    fireEvent.click(dialog)
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
