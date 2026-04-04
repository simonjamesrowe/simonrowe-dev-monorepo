import { useCallback, useEffect, useState } from 'react'

import { RoleTimeline } from '../components/experience/RoleTimeline'
import { JobDetailDrawer } from '../components/experience/JobDetailDrawer'
import { SkillGroupGrid } from '../components/skills/SkillGroupGrid'
import { SkillGroupDetail } from '../components/skills/SkillGroupDetail'
import { trackPageView } from '../services/analytics'

export function ExperiencePage() {
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null)
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null)

  useEffect(() => {
    trackPageView('/experience')
    document.title = 'Experience & Skills'
  }, [])

  const handleGroupClick = useCallback((groupId: string) => {
    setSelectedGroupId(groupId)
  }, [])

  const handleCloseSkillDrawer = useCallback(() => {
    setSelectedGroupId(null)
  }, [])

  const handleJobClick = useCallback((jobId: string) => {
    setSelectedJobId(jobId)
  }, [])

  const handleCloseJobDrawer = useCallback(() => {
    setSelectedJobId(null)
  }, [])

  const handleSkillGroupFromJob = useCallback((groupId: string) => {
    setSelectedJobId(null)
    setSelectedGroupId(groupId)
  }, [])

  const handleJobFromSkill = useCallback((jobId: string) => {
    setSelectedGroupId(null)
    setSelectedJobId(jobId)
  }, [])

  return (
    <div className="experience-page">
      <section className="experience-page__section tour-experience">
        <h2 className="headline-lg" style={{ color: 'white', marginBottom: '2rem' }}>Experience</h2>
        <RoleTimeline onJobClick={handleJobClick} />
      </section>

      <section className="experience-page__section tour-skills">
        <h2 className="headline-lg" style={{ color: 'white', marginBottom: '0.5rem' }}>Skills</h2>
        <p className="body-lg" style={{ color: 'var(--on-surface-variant)', marginBottom: '2rem' }}>
          Click a skill group to explore individual skills and see where they&apos;ve been used.
        </p>
        <SkillGroupGrid onGroupClick={handleGroupClick} />
      </section>

      {selectedGroupId && (
        <SkillGroupDetail
          groupId={selectedGroupId}
          onClose={handleCloseSkillDrawer}
          onJobClick={handleJobFromSkill}
        />
      )}

      {selectedJobId && (
        <JobDetailDrawer
          jobId={selectedJobId}
          onClose={handleCloseJobDrawer}
          onSkillGroupClick={handleSkillGroupFromJob}
        />
      )}
    </div>
  )
}
