export interface Position {
  top: number
  left: number
}

const GAP = 12
const EDGE_PADDING = 16

export function calculatePosition(
  element: Element,
  tooltip: HTMLElement,
  placement: string
): Position {
  const rect = element.getBoundingClientRect()
  const tooltipRect = tooltip.getBoundingClientRect()
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight

  let top: number
  let left: number

  let resolvedPlacement = placement
  if (placement === 'top' && rect.top - tooltipRect.height - GAP < EDGE_PADDING) {
    resolvedPlacement = 'bottom'
  } else if (placement === 'bottom' && rect.bottom + tooltipRect.height + GAP
      > viewportHeight - EDGE_PADDING) {
    resolvedPlacement = 'top'
  } else if (placement === 'left' && rect.left - tooltipRect.width - GAP < EDGE_PADDING) {
    resolvedPlacement = 'right'
  } else if (placement === 'right' && rect.right + tooltipRect.width + GAP
      > viewportWidth - EDGE_PADDING) {
    resolvedPlacement = 'left'
  }

  switch (resolvedPlacement) {
    case 'top':
      top = rect.top - tooltipRect.height - GAP
      left = rect.left + (rect.width - tooltipRect.width) / 2
      break
    case 'bottom':
      top = rect.bottom + GAP
      left = rect.left + (rect.width - tooltipRect.width) / 2
      break
    case 'left':
      top = rect.top + (rect.height - tooltipRect.height) / 2
      left = rect.left - tooltipRect.width - GAP
      break
    case 'right':
      top = rect.top + (rect.height - tooltipRect.height) / 2
      left = rect.right + GAP
      break
    case 'center':
      top = rect.top + (rect.height - tooltipRect.height) / 2
      left = rect.left + (rect.width - tooltipRect.width) / 2
      break
    default:
      top = rect.bottom + GAP
      left = rect.left + (rect.width - tooltipRect.width) / 2
  }

  return {
    top: Math.max(EDGE_PADDING, Math.min(top, viewportHeight - tooltipRect.height - EDGE_PADDING)),
    left: Math.max(EDGE_PADDING, Math.min(left, viewportWidth - tooltipRect.width - EDGE_PADDING)),
  }
}
