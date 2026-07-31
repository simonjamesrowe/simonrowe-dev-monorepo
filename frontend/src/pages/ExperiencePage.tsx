import { useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'

import { RoleTimeline } from '../components/experience/RoleTimeline'
import { SkillGroupGrid } from '../components/skills/SkillGroupGrid'
import { useDrawer } from '../hooks/useDrawer'
import { usePageTitle } from '../hooks/usePageTitle'
import { useScrollToHash } from '../hooks/useScrollToHash'
import { trackPageView } from '../services/analytics'

/**
 * Experience & Skills.
 *
 * The page has no fetch of its own — `RoleTimeline` and `SkillGroupGrid` each own
 * their request, loading skeleton and error frame, so a failure in one section never
 * blanks the other. There is therefore deliberately no page-level `ErrorMessage`.
 */
export function ExperiencePage() {
  const { openJob, openSkillGroup, selectedJobId, selectedGroupId } = useDrawer()
  const [searchParams, setSearchParams] = useSearchParams()
  const jobParam = searchParams.get('job')
  const groupParam = searchParams.get('skillGroup')
  const jobDrawerOpenedRef = useRef(false)
  const groupDrawerOpenedRef = useRef(false)

  useScrollToHash()
  usePageTitle('Experience & Skills')

  useEffect(() => {
    trackPageView('/experience')
  }, [])

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

  return (
    <div className="experience-page">
      <section id="roles" className="experience-page__section tour-experience">
        <RoleTimeline onJobClick={openJob} />
      </section>

      <section id="skills" className="experience-page__section tour-skills">
        <h2 className="experience-page__heading">Skills</h2>
        <p className="body-lg" style={{ color: 'var(--on-surface-variant)', marginBottom: '2rem' }}>
          Click a skill group to explore individual skills and see where they&apos;ve been used.
        </p>
        <SkillGroupGrid onGroupClick={openSkillGroup} />
      </section>
    </div>
  )
}
