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
import { AboutSection } from '../components/profile/AboutSection'
import { ContactDetails } from '../components/profile/ContactDetails'
import { ProfileBanner } from '../components/profile/ProfileBanner'
import { SocialLinks } from '../components/profile/SocialLinks'
import { SkillGroupDetail } from '../components/skills/SkillGroupDetail'
import { SkillsSection } from '../components/skills/SkillsSection'
import { useProfile } from '../hooks/useProfile'
import { trackHomepageEvent, trackPageView } from '../services/analytics'

const navigationItems: NavigationItem[] = [
  { id: 'about', label: 'About' },
  { id: 'experience', label: 'Experience' },
  { id: 'skills', label: 'Skills' },
  { id: 'blog', label: 'Blog' },
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
      />
      <MobileMenu
        aboutImageUrl={profile.sidebarImage.url}
        items={navigationItems}
        onNavigate={(section) => trackHomepageEvent('navigate_section', section)}
      />
      <main className="homepage__content">
        <ProfileBanner
          onDownloadCv={() => trackHomepageEvent('download_cv', profile.cvUrl ?? 'missing')}
          profile={profile}
        />
        <AboutSection description={profile.description} />
        <ExperienceSection onJobClick={handleJobClick} />
        <SkillsSection onGroupClick={handleGroupClick} />
        <div className="resume-section">
          <ResumeDownloadButton
            onDownload={() => trackHomepageEvent('download_resume', 'homepage')}
          />
        </div>
        <section className="panel" id="blog">
          <h3>Blog</h3>
          <p>
            Notes and case studies on engineering strategy, architecture decisions,
            and product outcomes.
          </p>
          <ul className="section-list">
            <li>Architecture trade-offs from real projects</li>
            <li>Delivery practices for high-performing teams</li>
            <li>Pragmatic AI-native development patterns</li>
          </ul>
        </section>
        <ContactDetails
          location={profile.location}
          onToggle={(expanded) => trackHomepageEvent('contact_expand', String(expanded))}
          phoneNumber={profile.phoneNumber}
          primaryEmail={profile.primaryEmail}
          secondaryEmail={profile.secondaryEmail}
        />
        <SocialLinks
          links={profile.socialMediaLinks}
          onSocialClick={(type) => trackHomepageEvent('social_media_click', type)}
        />
      </main>
      <ScrollToTop onScrollToTop={() => trackHomepageEvent('scroll_to_top', 'homepage')} />

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
