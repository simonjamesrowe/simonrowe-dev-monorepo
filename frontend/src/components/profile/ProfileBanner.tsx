import { useState, type CSSProperties } from 'react'

import type { Profile } from '../../types/Profile'
import { SiteSearch } from '../search/SiteSearch'

interface ProfileBannerProps {
  profile: Profile
  onDownloadCv?: () => void
}

export function ProfileBanner({ profile, onDownloadCv }: ProfileBannerProps) {
  const [photoVisible, setPhotoVisible] = useState(true)
  const style = {
    '--desktop-bg': `url(${profile.backgroundImage.url})`,
    '--mobile-bg': `url(${profile.mobileBackgroundImage.url || profile.backgroundImage.url})`,
  } as CSSProperties

  return (
    <section className="profile-banner" data-testid="profile-banner" style={style}>
      <div className="profile-banner__content">
        <p className="profile-banner__eyebrow">Profile</p>
        <h1>{profile.name}</h1>
        <h2>{profile.title}</h2>
        <p className="profile-banner__headline">{profile.headline}</p>
        <SiteSearch />
        {profile.cvUrl ? (
          <a
            className="button"
            href={profile.cvUrl}
            onClick={onDownloadCv}
            target="_blank"
            rel="noopener noreferrer"
          >
            Download CV
          </a>
        ) : null}
      </div>
      {photoVisible ? (
        <div className="profile-banner__photo-wrap">
          <img
            alt={`${profile.name} profile`}
            className="profile-banner__photo"
            onError={() => setPhotoVisible(false)}
            src={profile.profileImage.url}
          />
        </div>
      ) : (
        <div aria-label="Profile image unavailable" className="profile-banner__photo-fallback">
          {profile.firstName.charAt(0)}
          {profile.lastName.charAt(0)}
        </div>
      )}
    </section>
  )
}
