import type { IJob } from '../../types/job'

interface CurrentlyStripProps {
  jobs?: IJob[] | null
}

/** "August 2021" from an ISO date, or null when the date is unusable. */
function formatMonthYear(isoDate: string | undefined): string | null {
  if (!isoDate) {
    return null
  }
  const parsed = new Date(isoDate)
  if (Number.isNaN(parsed.getTime())) {
    return null
  }
  return parsed.toLocaleDateString('en-GB', { month: 'long', year: 'numeric' })
}

/**
 * A short prose summary of the role Simon is in right now.
 *
 * Every fact comes from the jobs data (FR-005) — the current role is the one job with
 * no `endDate`. Renders nothing at all when that job is absent, so a failed
 * `fetchJobs()` quietly drops the section instead of erroring the page.
 *
 * Deliberately does NOT repeat `profile.headline`: the hero already leads with it and
 * the footer closes with it, so a third copy here read as padding.
 */
export function CurrentlyStrip({ jobs }: CurrentlyStripProps) {
  const currentJob = jobs?.find((job) => !job.endDate)

  if (!currentJob) {
    return null
  }

  const since = formatMonthYear(currentJob.startDate)
  const summary = currentJob.shortDescription?.trim()

  return (
    <section className="currently-strip tour-currently" aria-labelledby="currently-strip-heading">
      <div className="currently-strip__inner">
        <h2 className="currently-strip__heading" id="currently-strip-heading">
          Currently
        </h2>
        <div className="currently-strip__role-row">
          {currentJob.companyImage?.url ? (
            <span aria-hidden="true" className="currently-strip__logo-chip">
              <img
                alt=""
                className="currently-strip__logo"
                src={currentJob.companyImage.url}
              />
            </span>
          ) : null}
          <div className="currently-strip__role-text">
            <p className="currently-strip__role">
              <strong className="currently-strip__title">{currentJob.title}</strong>
              {' at '}
              <span className="currently-strip__company">{currentJob.company}</span>
            </p>
            <p className="currently-strip__meta">
              {[currentJob.location, since ? `since ${since}` : null]
                .filter(Boolean)
                .join(' · ')}
            </p>
          </div>
        </div>
        {summary ? <p className="currently-strip__summary">{summary}</p> : null}
      </div>
    </section>
  )
}
