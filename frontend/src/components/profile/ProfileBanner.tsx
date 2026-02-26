import type { CSSProperties } from 'react'
import { useState } from 'react'
import { Download } from 'lucide-react'

import type { Profile } from '../../types/Profile'
import { SiteSearch } from '../search/SiteSearch'
import { ChatPanel } from '../chat/ChatPanel'

interface ProfileBannerProps {
  profile: Profile
  onDownloadCv?: () => void
}

export function ProfileBanner({ profile, onDownloadCv }: ProfileBannerProps) {
  const [chatOpen, setChatOpen] = useState(false)
  const [chatQuery, setChatQuery] = useState('')

  const style = {
    '--desktop-bg': `url(${profile.backgroundImage.url})`,
    '--mobile-bg': `url(${profile.mobileBackgroundImage.url || profile.backgroundImage.url})`,
  } as CSSProperties

  const handleChatStart = (query: string) => {
    setChatQuery(query)
    setChatOpen(true)
  }

  return (
    <section className="profile-banner" data-testid="profile-banner" style={style}>
      <div className="profile-banner__content">
        <h1>{profile.name}</h1>
        <h2>{profile.title}</h2>
        <p className="profile-banner__headline">{profile.headline}</p>
        <div className="profile-banner__search-row">
          <SiteSearch onChatStart={handleChatStart} />
        </div>
        {profile.cvUrl ? (
          <a
            className="profile-banner__cv-link tour-download-cv"
            href={profile.cvUrl}
            onClick={onDownloadCv}
            target="_blank"
            rel="noopener noreferrer"
          >
            <Download size={14} />
            Download CV
          </a>
        ) : null}
      </div>
      {chatOpen && (
        <ChatPanel
          initialQuery={chatQuery}
          onClose={() => setChatOpen(false)}
        />
      )}
    </section>
  )
}
