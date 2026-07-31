import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { CurrentlyStrip } from '../../../src/components/home/CurrentlyStrip'
import type { IJob } from '../../../src/types/job'

function job(overrides: Partial<IJob> = {}): IJob {
  return {
    id: 'job-1',
    title: 'Head of Engineering',
    company: 'Global',
    startDate: '2021-08-01',
    endDate: null,
    location: 'London',
    shortDescription: 'Leading the engineering function across three product areas.',
    isEducation: false,
    includeOnResume: true,
    ...overrides,
  }
}

describe('CurrentlyStrip', () => {
  it('renders the current role — the job with no endDate', () => {
    render(
      <CurrentlyStrip
        jobs={[
          job({ id: 'past', company: 'Y-Tree', title: 'CTO', endDate: '2021-07-01' }),
          job(),
        ]}
      />,
    )

    expect(screen.getByRole('heading', { name: 'Currently' })).toBeInTheDocument()
    expect(screen.getByText('Head of Engineering')).toBeInTheDocument()
    expect(screen.getByText('Global')).toBeInTheDocument()
    expect(screen.queryByText('Y-Tree')).not.toBeInTheDocument()
  })

  it('renders prose from the job short description', () => {
    render(<CurrentlyStrip jobs={[job()]} />)

    expect(
      screen.getByText('Leading the engineering function across three product areas.'),
    ).toBeInTheDocument()
  })

  it('renders the location and start month from the job data', () => {
    render(<CurrentlyStrip jobs={[job()]} />)

    const text = screen.getByRole('heading', { name: 'Currently' }).parentElement?.textContent ?? ''
    expect(text).toContain('London')
    expect(text).toContain('August 2021')
  })

  it('hardcodes no facts — all copy comes from the supplied data', () => {
    const { container } = render(
      <CurrentlyStrip
        jobs={[
          job({
            title: 'Principal Engineer',
            company: 'Acme',
            location: 'Leeds',
            shortDescription: 'Something else entirely.',
          }),
        ]}
      />,
    )

    const text = container.textContent ?? ''
    expect(text).toContain('Principal Engineer')
    expect(text).toContain('Acme')
    expect(text).toContain('Leeds')
    expect(text).toContain('Something else entirely.')
    // Nothing from the default fixture leaks in, so no fact is baked into the component.
    expect(text).not.toContain('Global')
    expect(text).not.toContain('Head of Engineering')
    expect(text).not.toContain('three product areas')
  })

  it('does not repeat the profile headline — the hero and footer already carry it', () => {
    // Guards against the duplication spotted during the local preview, where the same
    // headline appeared in the hero, this strip and the footer on one screen.
    const { container } = render(<CurrentlyStrip jobs={[job()]} />)

    expect(container.querySelector('.currently-strip__headline')).toBeNull()
  })

  it('renders nothing when there is no current job', () => {
    const { container } = render(<CurrentlyStrip jobs={[job({ endDate: '2021-07-01' })]} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when the jobs data is missing altogether', () => {
    const { container } = render(<CurrentlyStrip jobs={[]} />)
    expect(container).toBeEmptyDOMElement()

    const { container: undefinedJobs } = render(<CurrentlyStrip />)
    expect(undefinedJobs).toBeEmptyDOMElement()
  })

  it('omits the summary when the job has no short description', () => {
    render(<CurrentlyStrip jobs={[job({ shortDescription: '   ' })]} />)

    expect(screen.getByText('Head of Engineering')).toBeInTheDocument()
    expect(document.querySelector('.currently-strip__summary')).toBeNull()
  })
})
