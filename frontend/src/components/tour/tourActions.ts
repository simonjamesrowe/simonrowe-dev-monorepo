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
 *
 * Every key must be the selector of a seeded default step or one of
 * `GLOBAL_ACTION_SELECTORS`; `tourActions.test.ts` enforces it. A key matching no step
 * never fires, and nothing else would say so: the experience step's click-to-open action
 * outlived the only element carrying its class.
 */
export const STEP_ACTIONS: Record<string, TourActionDef> = {}

/**
 * Maps tour step target selectors to cleanup actions when leaving a step.
 * Uses 'openChat' to close the chat again, and clickElement on close buttons for drawers.
 */
export const STEP_CLEANUP: Record<string, TourActionDef> = {
  '.top-nav__ask-ai': { type: 'openChat' },
}

/**
 * Selectors an action may key on without a seeded step behind it: site chrome present on
 * every public page, which an operator can point a step at from the tour editor. The
 * "Ask AI" button is the tour's chat step, which `TourOverlay` also special-cases.
 */
export const GLOBAL_ACTION_SELECTORS: readonly string[] = ['.top-nav__ask-ai']
