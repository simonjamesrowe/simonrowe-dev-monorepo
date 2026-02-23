interface SkillRatingBarProps {
  rating: number | null | undefined
  skillName?: string
}

function getRatingColor(rating: number): string {
  if (rating >= 9) return 'var(--rating-green, #28a745)'
  if (rating >= 8.5) return 'var(--rating-blue, #17a2b8)'
  return 'var(--rating-orange, #ffc107)'
}

export function SkillRatingBar({ rating, skillName }: SkillRatingBarProps) {
  const safeRating = typeof rating === 'number' && rating >= 0 && rating <= 10 ? rating : 0
  const widthPercent = safeRating * 10
  const color = getRatingColor(safeRating)

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
        style={{ width: `${widthPercent}%`, backgroundColor: color }}
      />
    </div>
  )
}
