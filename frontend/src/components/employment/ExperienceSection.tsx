import { Timeline } from './Timeline'

interface ExperienceSectionProps {
  onJobClick: (jobId: string) => void
}

export function ExperienceSection({ onJobClick }: ExperienceSectionProps) {
  return (
    <section className="panel" id="experience">
      <h3>My Experience</h3>
      <Timeline onJobClick={onJobClick} />
    </section>
  )
}
