import { useEffect } from 'react'

import { BioSection } from '../components/profile/BioSection'
import { SocialLinks } from '../components/profile/SocialLinks'
import { ContactSection } from '../components/contact/ContactSection'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { useProfile } from '../hooks/useProfile'
import { trackPageView } from '../services/analytics'

export function ProfilePage() {
  const { profile, loading, error, retry } = useProfile()

  useEffect(() => {
    trackPageView(window.location.pathname)
  }, [])

  useEffect(() => {
    if (profile) {
      document.title = `${profile.name} | Profile`
    }
  }, [profile])

  if (loading) {
    return <LoadingIndicator />
  }

  if (error || !profile) {
    return <ErrorMessage message={error ?? 'Unable to load profile data.'} onRetry={retry} />
  }

  return (
    <div className="profile-page">
      <BioSection profile={profile} />

      <section className="profile-page__connect">
        <h2 className="profile-page__connect-heading">Connect</h2>
        <p className="body-lg">
          Whether you have a project in mind, a question, or just want to talk
          architecture — reach out. I read every message.
        </p>

        <div className="profile-page__connect-layout">
          <div className="profile-page__connect-info">
            <SocialLinks links={profile.socialMediaLinks} />
          </div>

          <div className="profile-page__connect-form">
            <ContactSection />
          </div>
        </div>
      </section>
    </div>
  )
}
