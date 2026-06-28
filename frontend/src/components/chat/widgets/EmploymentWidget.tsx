import type { EmploymentWidgetPayload } from '../chatTypes'

interface EmploymentWidgetProps {
  payload: EmploymentWidgetPayload
}

export function EmploymentWidget({ payload }: EmploymentWidgetProps) {
  if (!payload.jobs?.length) return null

  return (
    <div className="chat-widget chat-widget--employment">
      {payload.jobs.map(job => (
        <article className="chat-widget__item" key={`${job.company}-${job.title}-${job.start}`}>
          <div className="chat-widget__item-head">
            <h4 className="chat-widget__title">{job.title}</h4>
            <span className="chat-widget__meta">{job.company}</span>
          </div>
          {(job.start || job.end) && (
            <p className="chat-widget__date">{job.start ?? ''} - {job.end ?? 'Present'}</p>
          )}
          {job.summary && <p className="chat-widget__summary">{job.summary}</p>}
          {!!job.skills?.length && (
            <div className="chat-widget__chips">
              {job.skills.map(skill => (
                <span className="chat-widget__chip" key={skill}>{skill}</span>
              ))}
            </div>
          )}
        </article>
      ))}
    </div>
  )
}
