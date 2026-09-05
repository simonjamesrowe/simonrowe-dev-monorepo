import { useCallback, useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'

import { useTour } from '../../hooks/useTour'
import { calculatePosition, type Position } from './tourTooltipPosition'

const DEFAULT_AUTO_ADVANCE_MS = 7000

export function TourTooltip() {
  const {
    steps,
    currentStepIndex,
    next,
    prev,
    exit,
    autoAdvancePaused,
    agentResponsePending,
    targetReady,
    pauseAutoAdvance,
    resumeAutoAdvance,
  } = useTour()
  const tooltipRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<Position>({ top: 0, left: 0 })

  const currentStep = steps[currentStepIndex]
  const isFirstStep = currentStepIndex === 0
  const isLastStep = currentStepIndex === steps.length - 1
  const autoAdvanceMs = currentStep?.autoAdvanceMs ?? DEFAULT_AUTO_ADVANCE_MS

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
  }, [updatePosition, targetReady])

  useEffect(() => {
    window.addEventListener('resize', updatePosition)
    window.addEventListener('scroll', updatePosition, true)
    return () => {
      window.removeEventListener('resize', updatePosition)
      window.removeEventListener('scroll', updatePosition, true)
    }
  }, [updatePosition])

  if (!currentStep) {
    return null
  }

  return (
    <div
      className="tour-tooltip"
      data-testid="tour-tooltip"
      onMouseEnter={pauseAutoAdvance}
      onMouseLeave={resumeAutoAdvance}
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
      <div
        className={`tour-tooltip__progress-bar${autoAdvancePaused || agentResponsePending ? ' tour-tooltip__progress-bar--paused' : ''}`}
        key={`${currentStepIndex}-${agentResponsePending ? 'waiting' : 'ready'}`}
        style={{ '--auto-advance-duration': `${autoAdvanceMs}ms` } as React.CSSProperties}
      />
    </div>
  )
}
