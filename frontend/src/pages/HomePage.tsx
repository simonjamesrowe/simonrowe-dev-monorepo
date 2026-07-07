import { useEffect } from 'react'

import { HeroSection } from '../components/home/HeroSection'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { useProfile } from '../hooks/useProfile'
import { trackPageView } from '../services/analytics'

export function HomePage() {
  const { profile, loading: profileLoading, error: profileError, retry } = useProfile()

  useEffect(() => {
    trackPageView(window.location.pathname)
  }, [])

  useEffect(() => {
    if (profile) {
      document.title = `${profile.name} | Software Engineering Leader`
    }
  }, [profile])

  if (profileLoading) {
    return <LoadingIndicator message="Loading profile..." />
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
        backgroundImageUrl={profile.backgroundImage?.url}
      />
    </>
  )
}
