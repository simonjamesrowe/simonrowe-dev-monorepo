/** One word of a narration script and when it is spoken, as the backend writes it. */
export interface WordTiming {
  /** The word, punctuation included. */
  w: string
  /** Seconds from the start of the audio. */
  s: number
  /** Which synthesised sentence it belongs to. */
  n: number
}

/**
 * How far ahead in the timings to look for a rendered word. The script carries words the page
 * does not show: the title before the body, and "Code example omitted." where a block was.
 */
export const MATCH_LOOKAHEAD = 24

const normalise = (word: string) => word.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')

/**
 * For each rendered word, the index of its timing, or -1 when it has none.
 *
 * Walks both lists in order. A rendered word matches the first timing within
 * {@link MATCH_LOOKAHEAD} whose letters and digits are the same, ignoring case and punctuation,
 * so "Langfuse," on the page matches "Langfuse" in the script. A word with no match is left out
 * rather than given its neighbour's time, which would highlight two words at once.
 */
export function matchTimings(rendered: string[], timings: WordTiming[]): number[] {
  const keys = timings.map((timing) => normalise(timing.w))
  let cursor = 0
  return rendered.map((word) => {
    const target = normalise(word)
    if (!target) return -1
    const end = Math.min(keys.length, cursor + MATCH_LOOKAHEAD)
    for (let index = cursor; index < end; index += 1) {
      if (keys[index] === target) {
        cursor = index + 1
        return index
      }
    }
    return -1
  })
}

/** The index of the last word that has started by `time`, or -1 before the first. */
export function wordAt(starts: number[], time: number): number {
  let low = 0
  let high = starts.length - 1
  let answer = -1
  while (low <= high) {
    const middle = (low + high) >> 1
    if (starts[middle] <= time) {
      answer = middle
      low = middle + 1
    } else {
      high = middle - 1
    }
  }
  return answer
}
