/** Journey lengths offered, in minutes. */
export const JOURNEY_CHOICES = [15, 20, 30, 45]

/**
 * Where a journey ends in the queue.
 *
 * Everything is in *audio* seconds except the budget, which is wall-clock: at 1.25x a
 * 20-minute journey covers 25 minutes of audio, and that is what the listener cares about.
 */
export interface JourneyPlan {
  /** Audio seconds still to play, from the playhead to the end of the queue. */
  remainingSeconds: number
  /** Where the playhead sits along the whole queue, 0 to 1, or null with nothing playing. */
  playheadFraction: number | null
  /**
   * Where the journey ends along the whole queue, 0 to 1, or null when it outlasts the queue
   * or no journey is set.
   */
  stopFraction: number | null
  /** The item the journey ends during, or -1. Items after it will not be reached. */
  stopIndex: number
}

export function planJourney(
  durations: number[],
  currentIndex: number,
  positionSeconds: number,
  rate: number,
  budgetWallSeconds: number | null,
): JourneyPlan {
  const total = durations.reduce((sum, seconds) => sum + seconds, 0)
  const start = Math.max(currentIndex, 0)
  const offset = currentIndex >= 0 ? Math.min(positionSeconds, durations[currentIndex] ?? 0) : 0
  const before = durations.slice(0, start).reduce((sum, seconds) => sum + seconds, 0)
  const remainingSeconds = Math.max(0, total - before - offset)

  let stopIndex = -1
  let stopAt: number | null = null
  if (budgetWallSeconds !== null) {
    let budget = Math.max(0, budgetWallSeconds) * rate
    let elapsed = before
    for (let index = start; index < durations.length; index += 1) {
      const consumed = index === start ? offset : 0
      const left = durations[index] - consumed
      if (budget <= left) {
        stopIndex = index
        stopAt = elapsed + consumed + budget
        break
      }
      budget -= left
      elapsed += durations[index]
    }
  }

  return {
    remainingSeconds,
    playheadFraction: currentIndex >= 0 && total > 0 ? (before + offset) / total : null,
    stopFraction: stopAt !== null && total > 0 ? stopAt / total : null,
    stopIndex,
  }
}
