import { useCallback, useEffect } from 'react'
import { Download } from 'lucide-react'

import { AboutSection } from '../components/home/AboutSection'
import { SocialLinks } from '../components/profile/SocialLinks'
import { ContactSection } from '../components/contact/ContactSection'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { API_BASE_URL } from '../config/api'
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

  const scrollToContact = useCallback(() => {
    document.getElementById('contact')?.scrollIntoView({ behavior: 'smooth' })
  }, [])

  if (loading) {
    return <LoadingIndicator message="Loading profile..." />
  }

  if (error || !profile) {
    return <ErrorMessage message={error ?? 'Unable to load profile data.'} onRetry={retry} />
  }

  return (
    <div className="profile-page">
      <div className="tour-profile">
        <AboutSection profile={profile} onContact={scrollToContact} />
      </div>

      <section className="profile-page__connect">
        <h2 className="profile-page__connect-heading">Connect</h2>
        <p className="body-lg">
          Whether you have a project in mind, a question, or just want to talk
          architecture — reach out. I read every message.
        </p>

        <div className="profile-page__connect-layout">
          <div className="profile-page__connect-info">
            <a
              href={`${API_BASE_URL}${profile.cvUrl ?? '/api/resume'}`}
              target="_blank"
              rel="noopener noreferrer"
              className="button button--primary profile-page__cv-link"
            >
              <Download size={16} />
              Download CV
            </a>
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
