import type { IJob } from '../../types/job'

interface TimelineEntryProps {
  job: IJob
  onClick: (jobId: string) => void
  extraClassName?: string
}

function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  return date.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })
}

export function TimelineEntry({ job, onClick, extraClassName }: TimelineEntryProps) {
  const thumbnailUrl = job.companyImage?.formats?.thumbnail?.url ?? job.companyImage?.url
  const dateRange = `${formatDate(job.startDate)} — ${job.endDate ? formatDate(job.endDate) : 'Present'}`

  return (
    <button
      aria-label={`${job.title} at ${job.company}`}
      className={`timeline-entry${job.isEducation ? ' timeline-entry--education' : ''}${extraClassName ? ` ${extraClassName}` : ''}`}
      onClick={() => onClick(job.id)}
      role="listitem"
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
        <span className="timeline-entry__date-range">{dateRange}</span>
        {job.isEducation && <span className="timeline-entry__badge">Education</span>}
      </div>
    </button>
  )
}
