import { useEffect } from 'react'

import { AIChatModule } from '../components/home/AIChatModule'
import { CTASection } from '../components/home/CTASection'
import { HeroSection } from '../components/home/HeroSection'
import { StatsGrid } from '../components/home/StatsGrid'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { useProfile } from '../hooks/useProfile'
import { trackPageView } from '../services/analytics'

export function HomePage() {
  const { profile, loading, error, retry } = useProfile()

  useEffect(() => {
    trackPageView(window.location.pathname)
  }, [])

  useEffect(() => {
    if (profile) {
      document.title = `${profile.name} | The Digital Architect`
    }
  }, [profile])

  if (loading) {
    return <LoadingIndicator />
  }

  if (error || !profile) {
    return <ErrorMessage message={error ?? 'Unable to load profile data.'} onRetry={retry} />
  }

  return (
    <>
      <HeroSection
        name={profile.name}
        tagline={profile.headline}
        cvUrl={profile.cvUrl}
      >
        <AIChatModule />
      </HeroSection>
      <StatsGrid profile={profile} />
      <CTASection />
    </>
  )
}
