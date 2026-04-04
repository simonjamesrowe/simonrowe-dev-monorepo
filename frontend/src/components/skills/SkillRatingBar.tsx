interface SkillRatingBarProps {
  rating: number | null | undefined
  skillName?: string
}

export function SkillRatingBar({ rating, skillName }: SkillRatingBarProps) {
  const safeRating = typeof rating === 'number' && rating >= 0 && rating <= 10 ? rating : 0
  const widthPercent = safeRating * 10

  return (
    <div
      aria-label={`${skillName ?? 'Skill'} proficiency: ${safeRating} out of 10`}
      aria-valuemax={10}
      aria-valuemin={0}
      aria-valuenow={safeRating}
      className="skill-rating-bar"
      role="progressbar"
    >
      <div
        className="skill-rating-bar__fill"
        style={{ width: `${widthPercent}%`, backgroundColor: 'var(--primary)' }}
      />
    </div>
  )
}
