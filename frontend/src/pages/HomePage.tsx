import { useEffect } from 'react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { MobileMenu } from '../components/layout/MobileMenu'
import { ScrollToTop } from '../components/layout/ScrollToTop'
import { Sidebar, type NavigationItem } from '../components/layout/Sidebar'
import { AboutSection } from '../components/profile/AboutSection'
import { ContactDetails } from '../components/profile/ContactDetails'
import { ProfileBanner } from '../components/profile/ProfileBanner'
import { SocialLinks } from '../components/profile/SocialLinks'
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

  useEffect(() => {
    trackPageView(window.location.pathname)
  }, [])

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
        <section className="panel" id="experience">
          <h3>Experience</h3>
          <p>
            Cross-functional delivery across cloud-native platforms, developer experience,
            and product engineering.
          </p>
          <ul className="section-list">
            <li>Scaled distributed systems and developer platforms.</li>
            <li>Led roadmap execution across engineering and product teams.</li>
            <li>Established delivery standards for reliability and observability.</li>
          </ul>
        </section>
        <section className="panel" id="skills">
          <h3>Skills</h3>
          <p>
            Applied strengths in architecture, technical leadership, and practical
            AI-assisted software delivery.
          </p>
          <ul className="section-list">
            <li>Java, TypeScript, Spring Boot, React</li>
            <li>Cloud-native systems, distributed tracing, metrics</li>
            <li>Team leadership, mentoring, delivery planning</li>
          </ul>
        </section>
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
    </div>
  )
}
