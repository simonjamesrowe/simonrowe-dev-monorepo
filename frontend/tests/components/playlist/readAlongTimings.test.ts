import { describe, expect, it } from 'vitest'

import {
  MATCH_LOOKAHEAD,
  matchTimings,
  wordAt,
  type WordTiming,
} from '../../../src/components/playlist/readAlongTimings'

const timings = (words: string): WordTiming[] =>
  words.split(' ').map((w, index) => ({ w, s: index, n: 0 }))

describe('matchTimings', () => {
  it('skips the spoken title, which the page shows apart from the text', () => {
    const script = timings('Agent observability. Anthropic has introduced tracing.')

    expect(matchTimings(['Anthropic', 'has', 'introduced', 'tracing.'], script))
      .toEqual([2, 3, 4, 5])
  })

  it('matches words whatever their punctuation and case', () => {
    const script = timings('Grafana, Langfuse and "PostHog".')

    expect(matchTimings(['grafana', 'Langfuse,', 'and', 'PostHog'], script))
      .toEqual([0, 1, 2, 3])
  })

  it('leaves a word with no timing untimed rather than borrowing its neighbour', () => {
    const script = timings('One two three')

    expect(matchTimings(['One', 'extra', 'two', 'three'], script)).toEqual([0, -1, 1, 2])
  })

  it('skips the narration stand-in for a code block', () => {
    const script = timings('Before it. Code example omitted. After it.')

    expect(matchTimings(['Before', 'it.', 'After', 'it.'], script)).toEqual([0, 1, 5, 6])
  })

  it('never matches punctuation that is a word on its own', () => {
    expect(matchTimings(['—', 'a'], timings('— a'))).toEqual([-1, 1])
  })

  it('gives up on a word further ahead than the lookahead', () => {
    const script = timings(`${'x '.repeat(MATCH_LOOKAHEAD)}target`)

    expect(matchTimings(['target'], script)).toEqual([-1])
  })
})

describe('wordAt', () => {
  it('finds the last word that has started', () => {
    expect(wordAt([0.4, 1.2, 2.5], 0)).toBe(-1)
    expect(wordAt([0.4, 1.2, 2.5], 0.4)).toBe(0)
    expect(wordAt([0.4, 1.2, 2.5], 2.4)).toBe(1)
    expect(wordAt([0.4, 1.2, 2.5], 99)).toBe(2)
    expect(wordAt([], 3)).toBe(-1)
  })
})
