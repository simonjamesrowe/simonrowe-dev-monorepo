import ReactMarkdown from 'react-markdown'
import { API_BASE_URL } from '../../config/api'
import type { Profile } from '../../types/Profile'

interface AboutSectionProps {
  profile: Profile
  onContact: () => void
}

export function AboutSection({ profile, onContact }: AboutSectionProps) {
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
            <ReactMarkdown>{profile.description}</ReactMarkdown>
          </div>
          <button type="button" className="button button--primary about-section__cta" onClick={onContact}>
            Get In Touch
          </button>
        </div>
      </div>
    </section>
  )
}
