/**
 * The playback rates offered by every narration player on the site.
 *
 * Lifted out of `NarrationPanel` so the docked player bar offers the identical set. Kept in its
 * own module rather than exported from `NarrationPanel.tsx`, which would make that file export
 * both a component and a constant and trip the `react-refresh/only-export-components` lint rule.
 */
export const PLAYBACK_SPEEDS = [0.75, 1, 1.25, 1.5, 2]
