import type { MouseEvent } from 'react'

/**
 * Click handler for a modal backdrop that wraps its panel: it closes only when the click
 * landed on the backdrop itself. A click inside the panel bubbles up with the panel as its
 * target and is ignored, so the panel needs no `stopPropagation` handler of its own, and a
 * nested modal's backdrop (a different element) never closes the one behind it.
 *
 * The backdrop is a mouse convenience. Each modal's keyboard route out is its close button,
 * and Escape where it handles one.
 */
export function closeOnBackdropClick(onClose: () => void) {
  return (event: MouseEvent<HTMLElement>) => {
    if (event.target === event.currentTarget) {
      onClose()
    }
  }
}
