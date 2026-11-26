import { useCallback, useState, type MouseEvent, type ReactNode } from 'react'

interface TooltipState {
  x: number
  y: number
  content: ReactNode
}

/**
 * One floating tooltip per panel, positioned at the pointer. Marks call `show` on hover and
 * focus, so every value is reachable without a mouse too (focus passes the element's box).
 */
export function useChartTooltip() {
  const [state, setState] = useState<TooltipState | null>(null)

  const show = useCallback((event: MouseEvent<Element>, content: ReactNode) => {
    setState({ x: event.clientX, y: event.clientY, content })
  }, [])

  const showAt = useCallback((element: Element, content: ReactNode) => {
    const box = element.getBoundingClientRect()
    setState({ x: box.left + box.width / 2, y: box.bottom, content })
  }, [])

  const hide = useCallback(() => setState(null), [])

  const tooltip = state ? (
    <div
      className="admin-dash__tooltip"
      role="tooltip"
      style={{ left: Math.min(state.x + 14, window.innerWidth - 240), top: state.y + 14 }}
    >
      {state.content}
    </div>
  ) : null

  return { show, showAt, hide, tooltip }
}
