import type { IJob } from '../../types/job'

interface EmployerLogoStripProps {
  jobs?: IJob[] | null
  /** Called with the job id when a logo is selected. */
  onEmployerClick?: (jobId: string) => void
}

/**
 * A continuously scrolling row of past and present employer logos.
 *
 * Selecting a logo reports the job id to `onEmployerClick` rather than navigating to the
 * experience page; the home page wires that to the global job drawer. Taking a callback
 * instead of reaching for `useDrawer()` directly follows `RoleTimeline`'s `onJobClick`
 * pattern and keeps this component renderable without the drawer provider.
 *
 * Education entries are excluded and employers are de-duplicated by company name
 * (FR-006). Logo URLs are used as-is: they are `/uploads/...` paths that nginx (prod)
 * and the Vite dev proxy both serve, so prefixing `API_BASE_URL` would be wrong.
 *
 * The marquee renders the list twice and translates by exactly -50%, so the second copy
 * lands where the first began and the loop is seamless. The duplicate set is hidden from
 * assistive technology and taken out of the tab order — it is the same nine employers.
 * `prefers-reduced-motion` turns the whole thing back into a static wrapped row.
 */
export function EmployerLogoStrip({ jobs, onEmployerClick }: EmployerLogoStripProps) {
  const employers = (jobs ?? [])
    .filter((job) => job.isEducation !== true)
    .filter((job, index, all) => all.findIndex((other) => other.company === job.company) === index)

  if (employers.length === 0) {
    return null
  }

  const renderChip = (job: IJob, isClone: boolean) => (
    <li
      aria-hidden={isClone ? 'true' : undefined}
      className="employer-logo-strip__item"
      key={`${isClone ? 'clone' : 'lead'}-${job.id}`}
    >
      <button
        aria-label={`View the ${job.title} role at ${job.company}`}
        className="employer-logo-strip__chip"
        onClick={() => onEmployerClick?.(job.id)}
        tabIndex={isClone ? -1 : undefined}
        type="button"
      >
        {job.companyImage?.url ? (
          <img
            alt={job.company}
            className="employer-logo-strip__logo"
            src={job.companyImage.url}
          />
        ) : (
          <span className="employer-logo-strip__name">{job.company}</span>
        )}
      </button>
    </li>
  )

  return (
    <section className="employer-logo-strip" aria-labelledby="employer-logo-strip-heading">
      <div className="employer-logo-strip__inner">
        <h2 className="employer-logo-strip__heading" id="employer-logo-strip-heading">
          Where I&rsquo;ve worked
        </h2>
        <div className="employer-logo-strip__viewport">
          <ul className="employer-logo-strip__track">
            {employers.map((job) => renderChip(job, false))}
            {employers.map((job) => renderChip(job, true))}
          </ul>
        </div>
      </div>
    </section>
  )
}
