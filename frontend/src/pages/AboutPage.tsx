import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'

import { AboutSection } from '../components/home/AboutSection'
import { ContactDrawer } from '../components/contact/ContactDrawer'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { RoleTimeline } from '../components/experience/RoleTimeline'
import { SkillGroupGrid } from '../components/skills/SkillGroupGrid'
import { useDrawer } from '../hooks/useDrawer'
import { usePageTitle } from '../hooks/usePageTitle'
import { useProfile } from '../hooks/useProfile'
import { useScrollToHash } from '../hooks/useScrollToHash'
import { trackPageView } from '../services/analytics'

/**
 * About: the profile, then roles, then skills — what used to be the separate Profile and
 * Experience & Skills pages. `/profile` and `/experience` redirect here (see `App.tsx`).
 *
 * Every section owns its own failure. `RoleTimeline` and `SkillGroupGrid` each own their
 * request, loading skeleton and error frame, and the profile's loading and error states
 * render inline in the profile slot rather than replacing the page — so a profile outage
 * never blanks the roles and skills below it. There is deliberately no page-level
 * `ErrorMessage`.
 */
export function AboutPage() {
  const { profile, loading: profileLoading, error: profileError, retry } = useProfile()
  const { hash, key } = useLocation()
  const [contactOpen, setContactOpen] = useState(false)
  const { openJob, openSkillGroup, selectedJobId, selectedGroupId } = useDrawer()
  const [searchParams, setSearchParams] = useSearchParams()
  const jobParam = searchParams.get('job')
  const groupParam = searchParams.get('skillGroup')
  const jobDrawerOpenedRef = useRef(false)
  const groupDrawerOpenedRef = useRef(false)

  // The profile renders above #roles and #skills, so scrolling before it settles would
  // land short of the target once the profile pushes the sections down.
  useScrollToHash(!profileLoading)
  usePageTitle('About')

  useEffect(() => {
    trackPageView('/about')
  }, [])

  // The full-width Connect section was replaced by a drawer, so `#contact` no longer has
  // an element to scroll to — it opens the drawer instead. That keeps every existing
  // "Get in touch" link (the footer bar, the home CTA band) working after the change.
  //
  // Keyed on `location.key` as well as the hash: a visitor already on /about#contact who
  // closes the drawer and clicks "Get in touch" again produces the same hash, so watching
  // the hash alone would never re-fire and the drawer would stay shut.
  useEffect(() => {
    if (hash === '#contact') {
      setContactOpen(true)
    }
  }, [hash, key])

  const openContact = useCallback(() => setContactOpen(true), [])
  const closeContact = useCallback(() => setContactOpen(false), [])

  // Open the matching drawer from the URL (item-level deep link). A stale/unknown id
  // degrades gracefully: the drawer component simply renders nothing.
  useEffect(() => {
    if (jobParam) {
      openJob(jobParam)
    } else if (groupParam) {
      openSkillGroup(groupParam)
    }
  }, [jobParam, groupParam, openJob, openSkillGroup])

  // When a deep-linked drawer is closed by the user, clear its query param so
  // browser back/refresh behave. Guarded so the initial open is not mistaken for a close.
  useEffect(() => {
    if (selectedJobId && selectedJobId === jobParam) {
      jobDrawerOpenedRef.current = true
    }
    if (jobDrawerOpenedRef.current && selectedJobId === null && jobParam) {
      jobDrawerOpenedRef.current = false
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          next.delete('job')
          return next
        },
        { replace: true },
      )
    }

    if (selectedGroupId && selectedGroupId === groupParam) {
      groupDrawerOpenedRef.current = true
    }
    if (groupDrawerOpenedRef.current && selectedGroupId === null && groupParam) {
      groupDrawerOpenedRef.current = false
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          next.delete('skillGroup')
          return next
        },
        { replace: true },
      )
    }
  }, [selectedJobId, selectedGroupId, jobParam, groupParam, setSearchParams])

  let profileSlot: React.ReactNode
  if (profileLoading) {
    profileSlot = <LoadingIndicator message="Loading profile..." />
  } else if (profileError || !profile) {
    profileSlot = <ErrorMessage message={profileError ?? 'Unable to load profile data.'} onRetry={retry} />
  } else {
    profileSlot = <AboutSection profile={profile} onContact={openContact} />
  }

  return (
    <div className="about-page">
      <div className="about-page__profile tour-profile">
        {profileSlot}
      </div>

      <section id="roles" className="about-page__section about-page__section--roles tour-experience">
        <div className="about-page__roles-header">
          <h2 className="about-page__heading">Experience</h2>
        </div>
        <RoleTimeline onJobClick={openJob} />
      </section>

      <section id="skills" className="about-page__section tour-skills">
        <h2 className="about-page__heading">Skills</h2>
        <p className="body-lg" style={{ color: 'var(--on-surface-variant)', marginBottom: '2rem' }}>
          Click a skill group to explore individual skills and see where they&apos;ve been used.
        </p>
        <SkillGroupGrid onGroupClick={openSkillGroup} />
      </section>

      {profile && (
        <ContactDrawer
          cvUrl={profile.cvUrl}
          onClose={closeContact}
          open={contactOpen}
          socialMediaLinks={profile.socialMediaLinks}
        />
      )}
    </div>
  )
}
