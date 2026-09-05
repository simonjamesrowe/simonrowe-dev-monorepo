import { describe, expect, it } from 'vitest'

import { STEP_ACTIONS, STEP_CLEANUP } from '../../../src/components/tour/tourActions'

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
})
