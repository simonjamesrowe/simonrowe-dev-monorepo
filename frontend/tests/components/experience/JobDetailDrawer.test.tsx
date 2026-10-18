import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { JobDetailDrawer } from '../../../src/components/experience/JobDetailDrawer'

vi.mock('../../../src/services/jobsApi', () => ({
  fetchJob: vi.fn(),
}))

import { fetchJob } from '../../../src/services/jobsApi'

async function renderDrawer() {
  const onClose = vi.fn()
  const onSkillGroupClick = vi.fn()
  const view = render(
    <JobDetailDrawer jobId="job-1" onClose={onClose} onSkillGroupClick={onSkillGroupClick} />,
  )
  await screen.findByRole('heading', { name: 'Head of Engineering' })
  return { ...view, onClose, onSkillGroupClick }
}

describe('JobDetailDrawer', () => {
  beforeEach(() => {
    vi.mocked(fetchJob).mockResolvedValue({
      id: 'job-1',
      title: 'Head of Engineering',
      company: 'Acme',
      startDate: '2020-01-01',
      endDate: null,
      location: 'London',
      shortDescription: 'Led the platform teams.',
      isEducation: false,
      includeOnResume: true,
      longDescription: 'Built the platform.',
      skills: [{ id: 'skill-java', name: 'Java', rating: 9, image: null, skillGroupId: 'group-backend' }],
    })
  })

  it('stays open when the click lands inside the drawer', async () => {
    const { onClose } = await renderDrawer()

    fireEvent.click(screen.getByText('Built the platform.'))
    fireEvent.click(screen.getByRole('heading', { name: 'Head of Engineering' }))

    expect(onClose).not.toHaveBeenCalled()
  })

  /**
   * A skill inside the drawer is itself clickable. Its click must reach its own handler and
   * must not be read as a click outside the drawer.
   */
  it('opens a skill group from inside the drawer without closing it', async () => {
    const { onClose, onSkillGroupClick } = await renderDrawer()

    fireEvent.click(screen.getByText('Java'))

    expect(onSkillGroupClick).toHaveBeenCalledWith('group-backend')
    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes on a click on the backdrop', async () => {
    const { container, onClose } = await renderDrawer()

    fireEvent.click(container.querySelector('.drawer-overlay')!)

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes from its Close button', async () => {
    const { onClose } = await renderDrawer()

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
