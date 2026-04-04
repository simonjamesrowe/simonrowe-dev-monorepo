import type { Profile } from '../../types/Profile'

interface BioSectionProps {
  profile: Profile
}

export function BioSection({ profile }: BioSectionProps) {
  return (
    <section className="bio-section">
      <div className="bio-section__photo-col">
        <img
          alt={profile.name}
          className="bio-section__photo"
          src={profile.profileImage.url}
        />
        <div className="bio-section__tags">
          {profile.socialMediaLinks.map((link) => (
            <span key={`${link.type}-tag`} className="bio-section__tag">
              {link.type}
            </span>
          ))}
        </div>
      </div>

      <div className="bio-section__content">
        <h1 className="bio-section__headline display-lg">
          The Architect of Precision{' '}
          <span className="bio-section__headline-accent">Systems</span>
        </h1>

        <div className="bio-section__bio">
          <p>{profile.description}</p>
        </div>

        <div className="bio-section__stats">
          <div className="bio-section__stat">
            <span className="bio-section__stat-value">12+</span>
            <span className="bio-section__stat-label">Years Leadership</span>
          </div>
          <div className="bio-section__stat">
            <span className="bio-section__stat-value">450M+</span>
            <span className="bio-section__stat-label">Scale Managed</span>
          </div>
        </div>
      </div>
    </section>
  )
}
