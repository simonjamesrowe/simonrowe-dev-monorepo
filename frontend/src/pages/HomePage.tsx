import { useCallback, useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { ResumeDownloadButton } from '../components/common/ResumeDownloadButton'
import { ExperienceSection } from '../components/employment/ExperienceSection'
import { JobDetail } from '../components/employment/JobDetail'
import { MobileMenu } from '../components/layout/MobileMenu'
import { ScrollToTop } from '../components/layout/ScrollToTop'
import { Sidebar, type NavigationItem } from '../components/layout/Sidebar'
import { HomepageBlogPreview } from '../components/blog/HomepageBlogPreview'
import { ContactSection } from '../components/contact/ContactSection'
import { AboutSection } from '../components/profile/AboutSection'
import { ProfileBanner } from '../components/profile/ProfileBanner'
import { SkillGroupDetail } from '../components/skills/SkillGroupDetail'
import { SkillsSection } from '../components/skills/SkillsSection'
import { TourButton } from '../components/tour/TourButton'
import { TourOverlay } from '../components/tour/TourOverlay'
import { useProfile } from '../hooks/useProfile'
import { trackHomepageEvent, trackPageView } from '../services/analytics'

const navigationItems: NavigationItem[] = [
  { id: 'profile', label: 'Profile', route: '/' },
  { id: 'about', label: 'About' },
  { id: 'experience', label: 'Experience' },
  { id: 'skills', label: 'Skills' },
  { id: 'blog', label: 'Blog', route: '/blogs' },
  { id: 'contact', label: 'Contact' },
]

export function HomePage() {
  const { profile, loading, error, retry } = useProfile()
  const navigate = useNavigate()
  const params = useParams<{ groupId?: string; jobId?: string }>()

  const groupId = params.groupId
  const jobId = params.jobId

  useEffect(() => {
    trackPageView(window.location.pathname)
  }, [])

  useEffect(() => {
    if (profile) {
      document.title = `${profile.name} | ${profile.title}`
    }
  }, [profile])

  const handleGroupClick = useCallback((id: string) => {
    trackHomepageEvent('skill_group_click', id)
    void navigate(`/skills-groups/${id}`)
  }, [navigate])

  const handleJobClick = useCallback((id: string) => {
    trackHomepageEvent('job_click', id)
    void navigate(`/jobs/${id}`)
  }, [navigate])

  const handleSkillClick = useCallback((skillGroupId: string, skillId: string) => {
    trackHomepageEvent('skill_click', `${skillGroupId}#${skillId}`)
    void navigate(`/skills-groups/${skillGroupId}#${skillId}`)
  }, [navigate])

  const handleDrawerClose = useCallback(() => {
    void navigate('/')
  }, [navigate])

  if (loading) {
    return <LoadingIndicator />
  }

  if (error || !profile) {
    return <ErrorMessage message={error ?? 'Unable to load profile data.'} onRetry={retry} />
  }

  return (
    <div className="homepage">
      <Sidebar
        aboutImageUrl={profile.sidebarImage.url}
        items={navigationItems}
        onNavigate={(section) => trackHomepageEvent('navigate_section', section)}
        socialLinks={profile.socialMediaLinks}
        onSocialClick={(type) => trackHomepageEvent('social_media_click', type)}
      />
      <MobileMenu
        aboutImageUrl={profile.sidebarImage.url}
        items={navigationItems}
        onNavigate={(section) => trackHomepageEvent('navigate_section', section)}
      />
      <TourButton />
      <main className="homepage__content">
        <ProfileBanner
          onDownloadCv={() => trackHomepageEvent('download_cv', profile.cvUrl ?? 'missing')}
          profile={profile}
        />
        <AboutSection
          description={profile.description}
          profileImageUrl={profile.profileImage.url}
          profileName={profile.name}
          location={profile.location}
          primaryEmail={profile.primaryEmail}
          secondaryEmail={profile.secondaryEmail}
          phoneNumber={profile.phoneNumber}
          socialLinks={profile.socialMediaLinks}
          onSocialClick={(type) => trackHomepageEvent('social_media_click', type)}
        />
        <ExperienceSection onJobClick={handleJobClick} />
        <SkillsSection onGroupClick={handleGroupClick} />
        <div className="resume-section">
          <ResumeDownloadButton
            onDownload={() => trackHomepageEvent('download_resume', 'homepage')}
          />
        </div>
        <HomepageBlogPreview />
        <ContactSection />
      </main>
      <ScrollToTop onScrollToTop={() => trackHomepageEvent('scroll_to_top', 'homepage')} />
      <TourOverlay />

      {groupId && (
        <SkillGroupDetail
          groupId={groupId}
          onClose={handleDrawerClose}
          onJobClick={handleJobClick}
        />
      )}

      {jobId && (
        <JobDetail
          jobId={jobId}
          onClose={handleDrawerClose}
          onSkillClick={handleSkillClick}
        />
      )}
    </div>
  )
}
