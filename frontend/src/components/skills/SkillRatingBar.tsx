export type SkillLevel = 'Expert' | 'Advanced' | 'Proficient' | 'Familiar'

/**
 * Maps a 0–10 rating to the level word shown beside the bar (FR-027).
 *
 * The bands are deliberately expressed as *continuous* lower bounds rather than the
 * integer ranges the design doc uses ("9–10 / 7–8 / 5–6"). Live skill-group ratings
 * are decimals (9.5, 8.6, 8.3, 8.0, 7.6, 7.3, 7.2, 6.9), so integer sets would leave
 * values such as 8.6 and 6.9 unclassified.
 */
export function skillLevel(rating: number): SkillLevel {
  if (rating >= 9) return 'Expert'
  if (rating >= 7) return 'Advanced'
  if (rating >= 5) return 'Proficient'
  return 'Familiar'
}

interface SkillRatingBarProps {
  rating: number | null | undefined
  skillName?: string
}

export function SkillRatingBar({ rating, skillName }: SkillRatingBarProps) {
  const safeRating = typeof rating === 'number' && rating >= 0 && rating <= 10 ? rating : 0
  const widthPercent = safeRating * 10
  const level = skillLevel(safeRating)

  return (
    <div className="skill-rating-bar-row">
      {/*
        The level word is a sibling *outside* the progressbar: text inside an element
        with role="progressbar" is invalid ARIA, and the label below already announces
        the same word so the reading matches what is on screen (FR-028).
      */}
      <div
        aria-label={`${skillName ?? 'Skill'} proficiency: ${level} (${safeRating} out of 10)`}
        aria-valuemax={10}
        aria-valuemin={0}
        aria-valuenow={safeRating}
        className="skill-rating-bar"
        role="progressbar"
      >
        <div className="skill-rating-bar__fill" style={{ width: `${widthPercent}%` }} />
      </div>
      <span className="skill-rating-bar__level">{level}</span>
    </div>
  )
}
