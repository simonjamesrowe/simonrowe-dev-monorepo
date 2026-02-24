import { useCallback, useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'

import { useTour } from '../../hooks/useTour'

interface Position {
  top: number
  left: number
}

const GAP = 12
const EDGE_PADDING = 16

function calculatePosition(
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

  switch (placement) {
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

  // Viewport boundary clamping
  left = Math.max(EDGE_PADDING, Math.min(left, viewportWidth - tooltipRect.width - EDGE_PADDING))
  top = Math.max(EDGE_PADDING, Math.min(top, viewportHeight - tooltipRect.height - EDGE_PADDING))

  return { top, left }
}

export function TourTooltip() {
  const { steps, currentStepIndex, next, prev, exit } = useTour()
  const tooltipRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<Position>({ top: 0, left: 0 })

  const currentStep = steps[currentStepIndex]
  const isFirstStep = currentStepIndex === 0
  const isLastStep = currentStepIndex === steps.length - 1

  const updatePosition = useCallback(() => {
    if (!currentStep || !tooltipRef.current) {
      return
    }

    const element = document.querySelector(currentStep.targetSelector)
    if (element) {
      setPosition(calculatePosition(element, tooltipRef.current, currentStep.position))
    } else {
      // Center the tooltip if element not found
      const tooltipRect = tooltipRef.current.getBoundingClientRect()
      setPosition({
        top: (window.innerHeight - tooltipRect.height) / 2,
        left: (window.innerWidth - tooltipRect.width) / 2,
      })
    }
  }, [currentStep])

  useEffect(() => {
    // Allow DOM updates to settle before positioning
    const timer = requestAnimationFrame(updatePosition)
    return () => cancelAnimationFrame(timer)
  }, [updatePosition])

  useEffect(() => {
    window.addEventListener('resize', updatePosition)
    return () => window.removeEventListener('resize', updatePosition)
  }, [updatePosition])

  if (!currentStep) {
    return null
  }

  return (
    <div
      className="tour-tooltip"
      data-testid="tour-tooltip"
      ref={tooltipRef}
      style={{ top: position.top, left: position.left }}
    >
      <div className="tour-tooltip__header">
        {currentStep.titleImage && (
          <img
            alt=""
            className="tour-tooltip__image"
            src={currentStep.titleImage}
          />
        )}
        <h3 className="tour-tooltip__title">{currentStep.title}</h3>
      </div>
      <div className="tour-tooltip__body">
        <ReactMarkdown>{currentStep.description}</ReactMarkdown>
      </div>
      <div className="tour-tooltip__footer">
        <span className="tour-tooltip__progress">
          Step {currentStepIndex + 1} of {steps.length}
        </span>
        <div className="tour-tooltip__actions">
          {!isFirstStep && (
            <button
              className="tour-tooltip__btn tour-tooltip__btn--secondary"
              onClick={prev}
              type="button"
            >
              Previous
            </button>
          )}
          <button
            className="tour-tooltip__btn tour-tooltip__btn--secondary"
            onClick={exit}
            type="button"
          >
            Exit
          </button>
          <button
            className="tour-tooltip__btn tour-tooltip__btn--primary"
            onClick={isLastStep ? exit : next}
            type="button"
          >
            {isLastStep ? 'Finish' : 'Next'}
          </button>
        </div>
      </div>
    </div>
  )
}
