import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SkillEditorModal } from '../../src/components/admin/SkillEditorModal'

vi.mock('@mdxeditor/editor', async () => (await import('../testUtils/mdxEditorMock')).mdxEditorMock)

vi.mock('../../src/services/adminApi', () => ({
  createAdminSkill: vi.fn(),
  updateAdminSkill: vi.fn(),
  fetchAdminSkillById: vi.fn(),
  uploadAdminMedia: vi.fn(),
}))

// MediaPicker, inside ImagePicker, reads the token itself.
vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import {
  createAdminSkill,
  fetchAdminSkillById,
  updateAdminSkill,
  type AdminSkill,
} from '../../src/services/adminApi'
import { useAuth } from '../../src/auth/useAuth'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

const skill: AdminSkill = {
  id: 'skill-1',
  name: 'Kotlin',
  rating: 7,
  description: 'JVM, but nicer.',
  image: { url: '/uploads/kotlin.png' },
  order: 3,
  createdAt: '',
  updatedAt: '',
}

function renderModal(props: Partial<React.ComponentProps<typeof SkillEditorModal>> = {}) {
  const onSave = vi.fn()
  const onClose = vi.fn()
  const view = render(
    <SkillEditorModal
      getAccessToken={getAccessToken}
      onClose={onClose}
      onSave={onSave}
      open
      skillId={null}
      {...props}
    />,
  )
  return { ...view, onSave, onClose }
}

describe('SkillEditorModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    vi.mocked(fetchAdminSkillById).mockResolvedValue(skill)
    vi.mocked(createAdminSkill).mockResolvedValue({ ...skill, id: 'skill-new' })
    vi.mocked(updateAdminSkill).mockResolvedValue(skill)
  })

  it('renders nothing while closed', () => {
    const { container } = renderModal({ open: false })

    expect(container).toBeEmptyDOMElement()
  })

  it('names every field by its visible label', () => {
    renderModal()

    expect(screen.getByRole('heading', { name: 'New Skill' })).toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Rating (0-10)')).toHaveAttribute('type', 'number')
    expect(screen.getByRole('group', { name: 'Image' })).toBeInTheDocument()
  })

  it('creates a skill from the labelled fields', async () => {
    const { onSave } = renderModal()

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Rust' } })
    fireEvent.change(screen.getByLabelText('Rating (0-10)'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ ...skill, id: 'skill-new' }))
    expect(vi.mocked(createAdminSkill).mock.calls[0][1]).toMatchObject({ name: 'Rust', rating: 5 })
  })

  it('loads an existing skill into the labelled fields and saves it as an update', async () => {
    const { onSave } = renderModal({ skillId: 'skill-1' })

    expect(await screen.findByLabelText('Name')).toHaveValue('Kotlin')
    expect(screen.getByRole('heading', { name: 'Edit Skill' })).toBeInTheDocument()
    expect(screen.getByLabelText('Rating (0-10)')).toHaveValue(7)

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(onSave).toHaveBeenCalledWith(skill))
    expect(vi.mocked(updateAdminSkill).mock.calls[0][1]).toBe('skill-1')
    expect(vi.mocked(updateAdminSkill).mock.calls[0][2]).toMatchObject({
      name: 'Kotlin',
      rating: 7,
      description: 'JVM, but nicer.',
      image: '/uploads/kotlin.png',
      order: 3,
    })
  })

  it('stays open when the click lands inside the drawer', () => {
    const { onClose } = renderModal()

    fireEvent.click(screen.getByLabelText('Name'))
    fireEvent.click(screen.getByRole('heading', { name: 'New Skill' }))

    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes on a click on the backdrop', () => {
    const { onClose } = renderModal()

    fireEvent.click(screen.getByRole('dialog'))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes on Escape and from Cancel', () => {
    const { onClose } = renderModal()

    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onClose).toHaveBeenCalledTimes(2)
  })
})
