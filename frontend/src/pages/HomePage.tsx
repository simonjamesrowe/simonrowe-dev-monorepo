import { useEffect, useState } from 'react'

import { CTASection } from '../components/home/CTASection'
import { CurrentlyStrip } from '../components/home/CurrentlyStrip'
import { EmployerLogoStrip } from '../components/home/EmployerLogoStrip'
import { FeaturedWriting } from '../components/home/FeaturedWriting'
import { HeroSection } from '../components/home/HeroSection'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { useDrawer } from '../hooks/useDrawer'
import { usePageTitle } from '../hooks/usePageTitle'
import { useProfile } from '../hooks/useProfile'
import { trackPageView } from '../services/analytics'
import { fetchLatestBlogs } from '../services/blogApi'
import { fetchJobs } from '../services/jobsApi'
import type { BlogSummary } from '../types/blog'
import type { IJob } from '../types/job'

/** Posts fed to the Recent writing carousel. The API caps `limit` at 10. */
const HOME_POST_COUNT = 10

export function HomePage() {
  const { profile, loading: profileLoading, error: profileError, retry } = useProfile()
  const [jobs, setJobs] = useState<IJob[]>([])
  const [latestPosts, setLatestPosts] = useState<BlogSummary[]>([])
  // The job drawer is rendered globally by PublicLayout, so a logo can open a role in
  // place instead of sending the visitor off to /experience.
  const { openJob } = useDrawer()

  usePageTitle()

  useEffect(() => {
    trackPageView(window.location.pathname)
  }, [])

  // Jobs and posts feed the sections below the hero. Neither is allowed to break the
  // page: a rejected fetch leaves its section's data empty, and the section renders
  // nothing rather than an error or an empty scaffold.
  useEffect(() => {
    let cancelled = false

    const loadSections = async () => {
      const [jobsResult, postsResult] = await Promise.allSettled([
        fetchJobs(),
        fetchLatestBlogs(HOME_POST_COUNT, 'ENGINEERING'),
      ])

      if (cancelled) {
        return
      }
      if (jobsResult.status === 'fulfilled') {
        setJobs(jobsResult.value)
      }
      if (postsResult.status === 'fulfilled') {
        setLatestPosts(postsResult.value)
      }
    }

    void loadSections()

    return () => {
      cancelled = true
    }
  }, [])

  if (profileLoading) {
    return <LoadingIndicator message="Loading profile..." />
  }

  if (profileError || !profile) {
    return (
      <ErrorMessage
        message={profileError ?? 'Unable to load profile data.'}
        onRetry={retry}
        title="Unable to load the homepage"
      />
    )
  }

  return (
    <>
      <HeroSection
        name={profile.name}
        title={profile.title}
        tagline={profile.headline}
        backgroundImageUrl={profile.backgroundImage?.url}
      />
      <CurrentlyStrip jobs={jobs} />
      <EmployerLogoStrip jobs={jobs} onEmployerClick={openJob} />
      <FeaturedWriting blogs={latestPosts} />
      <CTASection />
    </>
  )
}
