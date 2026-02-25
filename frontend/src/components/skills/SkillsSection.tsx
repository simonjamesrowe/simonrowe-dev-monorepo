import { SkillGroupGrid } from './SkillGroupGrid'

interface SkillsSectionProps {
  onGroupClick: (groupId: string) => void
}

export function SkillsSection({ onGroupClick }: SkillsSectionProps) {
  return (
    <section className="panel tour-skills" id="skills">
      <h3>My Skills</h3>
      <SkillGroupGrid onGroupClick={onGroupClick} />
    </section>
  )
}
