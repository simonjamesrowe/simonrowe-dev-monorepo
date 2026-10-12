import { describe, expect, it } from 'vitest'

import { planJourney } from '../../../src/components/playlist/journey'

describe('planJourney', () => {
  const durations = [300, 60, 600]

  it('counts what is left from the playhead', () => {
    const plan = planJourney(durations, 0, 100, 1, null)

    expect(plan.remainingSeconds).toBe(860)
    expect(plan.playheadFraction).toBeCloseTo(100 / 960)
    expect(plan.stopFraction).toBeNull()
    expect(plan.stopIndex).toBe(-1)
  })

  it('counts the whole queue when nothing is playing', () => {
    const plan = planJourney(durations, -1, 0, 1, null)

    expect(plan.remainingSeconds).toBe(960)
    expect(plan.playheadFraction).toBeNull()
  })

  it('ends the journey part way through the item it runs out in', () => {
    // 200s left of the first, all 60 of the second, then 40 into the third.
    const plan = planJourney(durations, 0, 100, 1, 300)

    expect(plan.stopIndex).toBe(2)
    expect(plan.stopFraction).toBeCloseTo(400 / 960)
  })

  // A 20-minute journey at 1.5x covers 30 minutes of audio, which is what the listener hears.
  it('stretches the budget by the playback speed', () => {
    const plan = planJourney(durations, 0, 100, 1.5, 200)

    expect(plan.stopIndex).toBe(2)
    expect(plan.stopFraction).toBeCloseTo(400 / 960)
  })

  it('puts no stop on the bar when the journey outlasts the queue', () => {
    const plan = planJourney(durations, 0, 0, 1, 5000)

    expect(plan.stopIndex).toBe(-1)
    expect(plan.stopFraction).toBeNull()
  })
})
