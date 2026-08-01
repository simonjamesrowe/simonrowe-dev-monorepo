import { useMemo, useState } from 'react'
import ReactMarkdown from 'react-markdown'

import { API_BASE_URL } from '../../config/api'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import type { Profile } from '../../types/Profile'

interface AboutSectionProps {
  profile: Profile
  onContact: () => void
}

const PREVIEW_PARAGRAPHS = 2

export function AboutSection({ profile, onContact }: AboutSectionProps) {
  const isMobile = useMediaQuery('(max-width: 768px)')
  const [expanded, setExpanded] = useState(false)

  const paragraphs = useMemo(
    () => profile.description.split(/\n\s*\n/).filter(p => p.trim().length > 0),
    [profile.description],
  )

  const isTruncatable = isMobile && paragraphs.length > PREVIEW_PARAGRAPHS
  const visibleMarkdown = isTruncatable && !expanded
    ? paragraphs.slice(0, PREVIEW_PARAGRAPHS).join('\n\n')
    : profile.description

  return (
    <section className="about-section tour-about">
      <div className="about-section__inner">
        <div className="about-section__image-panel">
          <img
            src={`${API_BASE_URL}${profile.profileImage.url}`}
            alt={profile.name}
            className="about-section__photo"
          />
        </div>
        <div className="about-section__text-panel">
          <h2 className="about-section__heading headline-lg">
            About <span className="about-section__accent">{profile.firstName}</span>
          </h2>
          <div className="about-section__description body-lg">
            <ReactMarkdown>{visibleMarkdown}</ReactMarkdown>
          </div>
          {isTruncatable && (
            <button
              type="button"
              className="about-section__read-more"
              onClick={() => setExpanded(prev => !prev)}
              aria-expanded={expanded}
            >
              {expanded ? 'Read less' : 'Read more'}
            </button>
          )}
          <button type="button" className="button button--primary about-section__cta" onClick={onContact}>
            Get in touch
          </button>
        </div>
      </div>
    </section>
  )
}
