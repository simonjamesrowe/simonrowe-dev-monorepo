import { useCallback, useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'

import { AboutSection } from '../components/home/AboutSection'
import { ContactDrawer } from '../components/contact/ContactDrawer'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { usePageTitle } from '../hooks/usePageTitle'
import { useProfile } from '../hooks/useProfile'
import { trackPageView } from '../services/analytics'

export function ProfilePage() {
  const { profile, loading, error, retry } = useProfile()
  const { hash, key } = useLocation()
  const [contactOpen, setContactOpen] = useState(false)

  usePageTitle('Profile')

  useEffect(() => {
    trackPageView(window.location.pathname)
  }, [])

  // The full-width Connect section was replaced by a drawer, so `#contact` no longer has
  // an element to scroll to — it opens the drawer instead. That keeps every existing
  // "Get in touch" link (the footer bar, the home CTA band) working after the change.
  //
  // Keyed on `location.key` as well as the hash: a visitor already on /profile#contact who
  // closes the drawer and clicks "Get in touch" again produces the same hash, so watching
  // the hash alone would never re-fire and the drawer would stay shut.
  useEffect(() => {
    if (hash === '#contact') {
      setContactOpen(true)
    }
  }, [hash, key])

  const openContact = useCallback(() => setContactOpen(true), [])
  const closeContact = useCallback(() => setContactOpen(false), [])

  if (loading) {
    return <LoadingIndicator message="Loading profile..." />
  }

  if (error || !profile) {
    return <ErrorMessage message={error ?? 'Unable to load profile data.'} onRetry={retry} />
  }

  return (
    <div className="profile-page">
      <div className="tour-profile">
        <AboutSection profile={profile} onContact={openContact} />
      </div>

      <ContactDrawer
        cvUrl={profile.cvUrl}
        onClose={closeContact}
        open={contactOpen}
        socialMediaLinks={profile.socialMediaLinks}
      />
    </div>
  )
}
