import type { ISkillGroup } from '../../types/skill'
import { SkillRatingBar } from './SkillRatingBar'

interface SkillGroupCardProps {
  group: ISkillGroup
  onClick: (groupId: string) => void
}

export function SkillGroupCard({ group, onClick }: SkillGroupCardProps) {
  const thumbnailUrl = group.image?.formats?.thumbnail?.url ?? group.image?.url

  return (
    <button
      className="skill-group-card"
      onClick={() => onClick(group.id)}
      type="button"
    >
      <div className="skill-group-card__image-container">
        {thumbnailUrl ? (
          <img
            alt={group.name}
            className="skill-group-card__image"
            src={thumbnailUrl}
          />
        ) : (
          <div className="skill-group-card__image-placeholder">
            {group.name.charAt(0)}
          </div>
        )}
      </div>
      <div className="skill-group-card__content">
        <h4 className="skill-group-card__name">{group.name}</h4>
        <SkillRatingBar rating={group.rating} skillName={group.name} />
      </div>
    </button>
  )
}
