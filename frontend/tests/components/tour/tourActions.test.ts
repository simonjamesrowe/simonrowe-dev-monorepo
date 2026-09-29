import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import {
  GLOBAL_ACTION_SELECTORS,
  STEP_ACTIONS,
  STEP_CLEANUP,
} from '../../../src/components/tour/tourActions'

/**
 * The target selectors of the seeded default tour, read from the backend seeder itself so
 * the two cannot drift: the tour's copy and selectors live there, not in the frontend.
 */
function seededSelectors(): Set<string> {
  const seeder = readFileSync(
    resolve(__dirname, '../../../../backend/src/main/java/com/simonrowe/tour/TourStepSeeder.java'),
    'utf8',
  )
  return new Set([...seeder.matchAll(/"(\.tour-[a-z0-9-]+)"/g)].map((match) => match[1]))
}

describe('tourActions', () => {
  it('does not open the navigation assistant during the guided tour', () => {
    expect(STEP_ACTIONS['.top-nav__ask-ai']).toBeUndefined()
  })

  it('does not click or clean up the old homepage contact drawer for profile contact', () => {
    expect(STEP_ACTIONS['.tour-contact']).toBeUndefined()
    expect(STEP_CLEANUP['.tour-contact']).toBeUndefined()
    expect(Object.values(STEP_ACTIONS)).not.toContainEqual(
      expect.objectContaining({ clickTarget: '.cta-section__btn-primary' }),
    )
    expect(Object.values(STEP_CLEANUP)).not.toContainEqual(
      expect.objectContaining({ clickTarget: '.contact-drawer__close' }),
    )
  })

  it('keys every action and cleanup on a selector a tour step can actually target', () => {
    const selectors = seededSelectors()
    // Guard the guard: a regex that stopped matching would pass everything below.
    expect(selectors).toContain('.tour-about')
    expect(selectors).toContain('.tour-experience-highlight')

    const allowed = new Set([...selectors, ...GLOBAL_ACTION_SELECTORS])
    const keys = [...Object.keys(STEP_ACTIONS), ...Object.keys(STEP_CLEANUP)]
    expect(keys.filter((key) => !allowed.has(key))).toEqual([])
  })
})
