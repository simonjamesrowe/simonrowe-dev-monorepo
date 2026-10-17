/**
 * A callback ref that focuses its element once, when it mounts. Used instead of the
 * `autoFocus` attribute for a field that appears in response to the user's own action
 * (an inline editor they just opened), where moving focus into it is the expected result.
 *
 * Module-level, so its identity is stable and React calls it only on mount and unmount,
 * never on a re-render that would pull focus back while the user is elsewhere.
 */
export function focusOnMount(element: HTMLElement | null): void {
  element?.focus()
}
