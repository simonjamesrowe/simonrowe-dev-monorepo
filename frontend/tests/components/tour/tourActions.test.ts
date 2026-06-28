import { describe, expect, it } from 'vitest'

import { STEP_ACTIONS, STEP_CLEANUP } from '../../../src/components/tour/tourActions'

describe('tourActions', () => {
  it('opens Ask AI with the Spring Boot and Kafka prompt', () => {
    expect(STEP_ACTIONS['.top-nav__ask-ai']).toMatchObject({
      type: 'openChat',
      chatQuery: 'What Spring Boot and Kafka patterns does he use?',
    })
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
