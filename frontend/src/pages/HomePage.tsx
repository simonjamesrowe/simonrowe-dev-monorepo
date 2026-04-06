import { useCallback, useEffect, useState } from 'react'

import { AboutSection } from '../components/home/AboutSection'
import { CTASection } from '../components/home/CTASection'
import { HeroSection } from '../components/home/HeroSection'
import { ContactDrawer } from '../components/contact/ContactDrawer'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { useProfile } from '../hooks/useProfile'
import { trackPageView } from '../services/analytics'

export function HomePage() {
  const { profile, loading: profileLoading, error: profileError, retry } = useProfile()
  const [contactOpen, setContactOpen] = useState(false)

  useEffect(() => {
    trackPageView(window.location.pathname)
  }, [])

  useEffect(() => {
    if (profile) {
      document.title = `${profile.name} | Software Engineering Leader`
    }
  }, [profile])

  const openContact = useCallback(() => setContactOpen(true), [])
  const closeContact = useCallback(() => setContactOpen(false), [])

  if (profileLoading) {
    return <LoadingIndicator />
  }

  if (profileError || !profile) {
    return <ErrorMessage message={profileError ?? 'Unable to load profile data.'} onRetry={retry} />
  }

  return (
    <>
      <HeroSection
        name={profile.name}
        title={profile.title}
        tagline={profile.headline}
        cvUrl={profile.cvUrl}
        backgroundImageUrl={profile.backgroundImage?.url}
        socialMediaLinks={profile.socialMediaLinks}
      />
      <AboutSection profile={profile} onContact={openContact} />
      <CTASection onContact={openContact} />
      <ContactDrawer open={contactOpen} onClose={closeContact} />
    </>
  )
}
