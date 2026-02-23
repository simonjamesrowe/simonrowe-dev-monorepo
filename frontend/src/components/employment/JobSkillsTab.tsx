import type { ISkillReference } from '../../types/job'
import { SkillRatingBar } from '../skills/SkillRatingBar'

interface JobSkillsTabProps {
  skills: ISkillReference[]
  onSkillClick: (skillGroupId: string, skillId: string) => void
}

export function JobSkillsTab({ skills, onSkillClick }: JobSkillsTabProps) {
  if (skills.length === 0) {
    return (
      <div className="job-skills-tab">
        <p className="job-skills-tab__empty">No skills recorded for this position.</p>
      </div>
    )
  }

  return (
    <div className="job-skills-tab">
      <div className="job-skills-tab__grid">
        {skills.map((skill) => {
          const thumbnailUrl = skill.image?.formats?.thumbnail?.url ?? skill.image?.url

          return (
            <button
              className="job-skills-tab__card"
              key={skill.id}
              onClick={() => onSkillClick(skill.skillGroupId, skill.id)}
              type="button"
            >
              {thumbnailUrl ? (
                <img alt={skill.name} className="job-skills-tab__image" src={thumbnailUrl} />
              ) : (
                <div className="job-skills-tab__image-placeholder">{skill.name.charAt(0)}</div>
              )}
              <div className="job-skills-tab__info">
                <span className="job-skills-tab__name">{skill.name}</span>
                <SkillRatingBar rating={skill.rating} skillName={skill.name} />
              </div>
            </button>
          )
        })}
      </div>
    </div>
  )
}
