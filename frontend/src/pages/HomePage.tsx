import { useCallback, useEffect, useState } from 'react'

import { AboutSection } from '../components/home/AboutSection'
import { CTASection } from '../components/home/CTASection'
import { HeroSection } from '../components/home/HeroSection'
import { ChatPanel } from '../components/chat/ChatPanel'
import { RecaptchaGate } from '../components/chat/RecaptchaGate'
import { ContactDrawer } from '../components/contact/ContactDrawer'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { useProfile } from '../hooks/useProfile'
import { trackPageView } from '../services/analytics'
import { API_BASE_URL } from '../config/api'

export function HomePage() {
  const { profile, loading: profileLoading, error: profileError, retry } = useProfile()
  const [contactOpen, setContactOpen] = useState(false)
  const [chatQuery, setChatQuery] = useState<string | null>(null)
  const [chatOpen, setChatOpen] = useState(false)
  const [recaptchaVerified, setRecaptchaVerified] = useState(false)
  const [showRecaptcha, setShowRecaptcha] = useState(false)

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

  const handleChatOpen = useCallback((query: string) => {
    setChatQuery(query)
    if (recaptchaVerified) {
      setChatOpen(true)
    } else {
      setShowRecaptcha(true)
    }
  }, [recaptchaVerified])

  const handleRecaptchaVerified = useCallback(() => {
    setRecaptchaVerified(true)
    setShowRecaptcha(false)
    setChatOpen(true)
  }, [])

  const handleRecaptchaCancel = useCallback(() => {
    setShowRecaptcha(false)
    setChatQuery(null)
  }, [])

  const handleChatClose = useCallback(() => {
    setChatOpen(false)
    setChatQuery(null)
  }, [])

  const profileImageUrl = profile?.profileImage?.url
    ? `${API_BASE_URL}${profile.profileImage.url}`
    : undefined

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
        onChatOpen={handleChatOpen}
      />
      <AboutSection profile={profile} onContact={openContact} />
      <CTASection onContact={openContact} />
      <ContactDrawer open={contactOpen} onClose={closeContact} />
      {showRecaptcha && (
        <RecaptchaGate onVerified={handleRecaptchaVerified} onCancel={handleRecaptchaCancel} />
      )}
      {chatOpen && chatQuery && (
        <ChatPanel
          initialQuery={chatQuery}
          onClose={handleChatClose}
          profileImageUrl={profileImageUrl}
          visible={chatOpen}
        />
      )}
    </>
  )
}
