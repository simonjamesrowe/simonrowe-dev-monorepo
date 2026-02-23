import type { IJobReference } from '../../types/skill'

interface JobReferenceCardProps {
  job: IJobReference
  onClick: (jobId: string) => void
}

function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  return date.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })
}

export function JobReferenceCard({ job, onClick }: JobReferenceCardProps) {
  const thumbnailUrl = job.companyImage?.formats?.thumbnail?.url ?? job.companyImage?.url

  return (
    <button
      className="job-reference-card"
      onClick={() => onClick(job.id)}
      type="button"
    >
      {thumbnailUrl && (
        <img alt={job.company} className="job-reference-card__image" src={thumbnailUrl} />
      )}
      <div className="job-reference-card__content">
        <span className="job-reference-card__title">{job.title}</span>
        <span className="job-reference-card__company">{job.company}</span>
        <span className="job-reference-card__dates">
          {formatDate(job.startDate)} — {job.endDate ? formatDate(job.endDate) : 'Present'}
        </span>
      </div>
    </button>
  )
}
