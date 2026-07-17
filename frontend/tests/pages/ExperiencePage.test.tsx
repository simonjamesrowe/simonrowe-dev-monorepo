import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const drawerState = {
  selectedJobId: null as string | null,
  selectedGroupId: null as string | null,
  openJob: vi.fn(),
  openSkillGroup: vi.fn(),
  closeJob: vi.fn(),
  closeSkillGroup: vi.fn(),
}

vi.mock('../../src/hooks/useDrawer', () => ({
  useDrawer: () => drawerState,
}))

vi.mock('../../src/services/analytics', () => ({
  trackPageView: vi.fn(),
}))

vi.mock('../../src/components/experience/RoleTimeline', () => ({
  RoleTimeline: () => <div data-testid="role-timeline" />,
}))

vi.mock('../../src/components/skills/SkillGroupGrid', () => ({
  SkillGroupGrid: () => <div data-testid="skill-group-grid" />,
}))

import { ExperiencePage } from '../../src/pages/ExperiencePage'

Element.prototype.scrollIntoView = vi.fn()

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ExperiencePage />
    </MemoryRouter>,
  )
}

describe('ExperiencePage deep links', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    drawerState.selectedJobId = null
    drawerState.selectedGroupId = null
  })

  it('opens the job drawer for /experience?job=<id>', () => {
    renderAt('/experience?job=job-1')

    expect(drawerState.openJob).toHaveBeenCalledWith('job-1')
    expect(drawerState.openSkillGroup).not.toHaveBeenCalled()
  })

  it('opens the skill-group drawer for /experience?skillGroup=<id>', () => {
    renderAt('/experience?skillGroup=group-1')

    expect(drawerState.openSkillGroup).toHaveBeenCalledWith('group-1')
    expect(drawerState.openJob).not.toHaveBeenCalled()
  })

  it('does not throw for an unknown id (degrades gracefully)', () => {
    expect(() => renderAt('/experience?job=does-not-exist')).not.toThrow()
    expect(drawerState.openJob).toHaveBeenCalledWith('does-not-exist')
  })

  it('renders stable section ids for hash navigation', () => {
    renderAt('/experience')

    expect(document.getElementById('roles')).not.toBeNull()
    expect(document.getElementById('skills')).not.toBeNull()
  })

  it('scrolls to the #skills section when a hash is present', () => {
    renderAt('/experience#skills')

    const skills = document.getElementById('skills')
    expect(skills).not.toBeNull()
    expect(skills!.scrollIntoView).toHaveBeenCalled()
  })
})
