import ReactMarkdown from 'react-markdown'

import type { IJobDetail } from '../../types/job'

interface JobAboutTabProps {
  job: IJobDetail
}

export function JobAboutTab({ job }: JobAboutTabProps) {
  return (
    <div className="job-about-tab">
      <div className="job-about-tab__meta">
        <span className="job-about-tab__company">{job.company}</span>
        <span className="job-about-tab__location">{job.location}</span>
        {job.companyUrl && (
          <a
            className="job-about-tab__link"
            href={job.companyUrl}
            rel="noopener noreferrer"
            target="_blank"
          >
            Visit website
          </a>
        )}
      </div>
      <div className="job-about-tab__description">
        <ReactMarkdown>{job.longDescription}</ReactMarkdown>
      </div>
    </div>
  )
}
