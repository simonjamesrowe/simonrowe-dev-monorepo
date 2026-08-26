/**
 * How long a narration is, in words.
 *
 * Extracted from `NarrationPanel`, which had it as a private helper, because the listing cards
 * need the same number. The two surfaces word it differently — the detail-page player has room
 * for a sentence, a card next to a play glyph does not — so both formatters share one minute
 * calculation. That is the point of the extraction: the wording may differ, the *number* cannot
 * drift.
 */

/** Minutes, rounded, never zero: a 20-second clip is still "1 min" rather than "0 min". */
function approximateMinutes(durationSeconds: number): number {
  return Math.max(1, Math.round(durationSeconds / 60))
}

/** The detail-page player's wording, e.g. `About 12 min`. */
export function formatApproximateDuration(durationSeconds: number): string {
  return `About ${approximateMinutes(durationSeconds)} min`
}

/** The card's wording, e.g. `12 min` — it sits beside a play glyph and has no room for prose. */
export function formatCompactDuration(durationSeconds: number): string {
  return `${approximateMinutes(durationSeconds)} min`
}
