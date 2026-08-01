import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { EmployerLogoStrip } from '../../../src/components/home/EmployerLogoStrip'
import type { IJob } from '../../../src/types/job'

function job(overrides: Partial<IJob> = {}): IJob {
  return {
    id: 'job-1',
    title: 'Head of Engineering',
    company: 'Global',
    companyImage: { url: '/uploads/global/original.png' },
    startDate: '2021-08-01',
    endDate: null,
    location: 'London',
    shortDescription: 'Leading engineering.',
    isEducation: false,
    includeOnResume: true,
    ...overrides,
  }
}

/** Only the real chips — the marquee's duplicate set is aria-hidden and untabbable. */
function visibleChips() {
  return screen.getAllByRole('button')
}

describe('EmployerLogoStrip', () => {
  it('renders one chip per employer under the heading', () => {
    render(
      <EmployerLogoStrip
        jobs={[job(), job({ id: 'job-2', company: 'Y-Tree', title: 'Senior Developer' })]}
      />,
    )

    expect(screen.getByRole('heading', { name: /where i.ve worked/i })).toBeInTheDocument()
    expect(visibleChips()).toHaveLength(2)
  })

  it('opens the role in place instead of navigating to the experience page', async () => {
    const onEmployerClick = vi.fn()
    render(<EmployerLogoStrip jobs={[job()]} onEmployerClick={onEmployerClick} />)

    // No links at all now: selecting a logo must open the job drawer.
    expect(screen.queryByRole('link')).not.toBeInTheDocument()

    await userEvent.click(
      screen.getByRole('button', { name: 'View the Head of Engineering role at Global' }),
    )

    expect(onEmployerClick).toHaveBeenCalledTimes(1)
    expect(onEmployerClick).toHaveBeenCalledWith('job-1')
  })

  it('duplicates the row for the marquee but hides the copy from assistive tech', () => {
    const { container } = render(<EmployerLogoStrip jobs={[job()]} />)

    // Two DOM chips so the -50% translate loops seamlessly...
    expect(container.querySelectorAll('.employer-logo-strip__item')).toHaveLength(2)
    // ...but only one is announced or reachable by keyboard.
    expect(visibleChips()).toHaveLength(1)
    const clone = container.querySelector('.employer-logo-strip__item[aria-hidden="true"]')
    expect(clone).not.toBeNull()
    expect(clone?.querySelector('button')).toHaveAttribute('tabindex', '-1')
  })

  it('de-duplicates by company', () => {
    render(
      <EmployerLogoStrip
        jobs={[
          job(),
          job({ id: 'job-2', title: 'Senior Engineer' }),
          job({ id: 'job-3', company: 'SAS' }),
        ]}
      />,
    )

    expect(visibleChips()).toHaveLength(2)
  })

  it('excludes education entries', () => {
    render(
      <EmployerLogoStrip
        jobs={[job(), job({ id: 'uni', company: 'University of Newcastle', isEducation: true })]}
      />,
    )

    expect(visibleChips()).toHaveLength(1)
    expect(screen.queryByAltText('University of Newcastle')).not.toBeInTheDocument()
  })

  it('falls back to the company name when there is no logo', () => {
    render(<EmployerLogoStrip jobs={[job({ companyImage: undefined })]} />)

    // The name is in the DOM twice because of the marquee, so scope to the real chip.
    expect(visibleChips()[0]).toHaveTextContent('Global')
  })

  it('renders nothing on empty input', () => {
    const { container } = render(<EmployerLogoStrip jobs={[]} />)
    expect(container).toBeEmptyDOMElement()

    const { container: undefinedJobs } = render(<EmployerLogoStrip />)
    expect(undefinedJobs).toBeEmptyDOMElement()
  })

  it('renders nothing when every job is an education entry', () => {
    const { container } = render(<EmployerLogoStrip jobs={[job({ isEducation: true })]} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('does not throw when no click handler is supplied', async () => {
    render(<EmployerLogoStrip jobs={[job()]} />)

    await userEvent.click(visibleChips()[0])

    expect(visibleChips()[0]).toBeInTheDocument()
  })
})
