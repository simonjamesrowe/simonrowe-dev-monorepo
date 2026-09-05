export type TourActionType = 'openChat' | 'clickElement'

export interface TourActionDef {
  type: TourActionType
  /** CSS selector of element to click (for 'clickElement') */
  clickTarget?: string
  /** Query to pass to openChat (for 'openChat') */
  chatQuery?: string
}

/**
 * Maps tour step target selectors to actions that should be executed
 * when that step becomes active.
 */
export const STEP_ACTIONS: Record<string, TourActionDef> = {
  '.tour-experience-1': { type: 'clickElement', clickTarget: '.tour-experience-1 .role-timeline__card' },
}

/**
 * Maps tour step target selectors to cleanup actions when leaving a step.
 * Uses 'closeChat' for chat, and clickElement on close buttons for drawers.
 */
export const STEP_CLEANUP: Record<string, TourActionDef> = {
  '.top-nav__ask-ai': { type: 'openChat' },
  '.tour-experience-1': { type: 'clickElement', clickTarget: '.drawer__close' },
}
