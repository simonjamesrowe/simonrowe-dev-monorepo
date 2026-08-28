/**
 * Reads a pull request number out of whatever an operator actually pastes.
 *
 * The two pull-request actions send a number, but "which pull request" arrives in a browser as a
 * URL far more often than as a bare integer — copying the address bar is the natural gesture. So
 * both are accepted and the field says so, rather than the page silently rejecting the more
 * likely input.
 *
 * Returns null for anything it cannot read as a positive pull request number, which the caller
 * treats as "not ready to submit".
 */
export function parsePullNumber(input: string): number | null {
  const trimmed = input.trim()
  if (trimmed === '') return null

  // A bare number, with or without a leading #.
  const bare = /^#?(\d+)$/.exec(trimmed)
  if (bare) return positive(bare[1])

  // A GitHub pull request URL. `/files`, `/commits`, an anchor or a query string may follow the
  // number, and `/pull/` and `/pulls/` are both seen in the wild.
  const url = /github\.com\/[^/\s]+\/[^/\s]+\/pulls?\/(\d+)(?:[/?#]|$)/i.exec(trimmed)
  if (url) return positive(url[1])

  // owner/repo#123, the form GitHub itself renders for a cross-repository reference.
  const shorthand = /^[^/\s]+\/[^/\s#]+#(\d+)$/.exec(trimmed)
  if (shorthand) return positive(shorthand[1])

  return null
}

function positive(digits: string): number | null {
  const value = Number(digits)
  return Number.isSafeInteger(value) && value > 0 ? value : null
}
