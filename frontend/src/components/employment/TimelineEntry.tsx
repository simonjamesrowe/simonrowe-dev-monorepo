import type { IJob } from '../../types/job'

interface TimelineEntryProps {
  job: IJob
  side: 'left' | 'right'
  onClick: (jobId: string) => void
}

function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  return date.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })
}

export function TimelineEntry({ job, side, onClick }: TimelineEntryProps) {
  const thumbnailUrl = job.companyImage?.formats?.thumbnail?.url ?? job.companyImage?.url
  const dateRange = `${formatDate(job.startDate)} — ${job.endDate ? formatDate(job.endDate) : 'Present'}`

  const dateBlock = (
    <div className="timeline-entry__dates">
      <span className="timeline-entry__date-range">{dateRange}</span>
    </div>
  )

  const contentBlock = (
    <button
      aria-label={`${job.title} at ${job.company}`}
      className={`timeline-entry__content ${job.isEducation ? 'timeline-entry__content--education' : ''}`}
      onClick={() => onClick(job.id)}
      type="button"
    >
      {thumbnailUrl ? (
        <img alt={job.company} className="timeline-entry__image" src={thumbnailUrl} />
      ) : (
        <div className="timeline-entry__image-placeholder">{job.company.charAt(0)}</div>
      )}
      <div className="timeline-entry__info">
        <span className="timeline-entry__title">{job.title}</span>
        <span className="timeline-entry__company">{job.company}</span>
        {job.isEducation && <span className="timeline-entry__badge">Education</span>}
      </div>
    </button>
  )

  return (
    <div className={`timeline-entry timeline-entry--${side}`} role="listitem">
      {side === 'left' ? (
        <>
          {dateBlock}
          {contentBlock}
        </>
      ) : (
        <>
          {contentBlock}
          {dateBlock}
        </>
      )}
    </div>
  )
}
