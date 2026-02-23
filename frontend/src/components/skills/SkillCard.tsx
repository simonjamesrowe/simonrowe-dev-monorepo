import type { IImage } from '../../types/image'
import { SkillRatingBar } from './SkillRatingBar'

interface SkillCardProps {
  id: string
  name: string
  rating: number
  description?: string | null
  image?: IImage | null
}

export function SkillCard({ id, name, rating, description, image }: SkillCardProps) {
  const thumbnailUrl = image?.formats?.thumbnail?.url ?? image?.url

  return (
    <div className="skill-card" id={`skill-${id}`}>
      <div className="skill-card__header">
        {thumbnailUrl ? (
          <img alt={name} className="skill-card__image" src={thumbnailUrl} />
        ) : (
          <div className="skill-card__image-placeholder">{name.charAt(0)}</div>
        )}
        <div className="skill-card__info">
          <span className="skill-card__name">{name}</span>
          <SkillRatingBar rating={rating} skillName={name} />
        </div>
      </div>
      {description && (
        <p className="skill-card__description">{description}</p>
      )}
    </div>
  )
}
