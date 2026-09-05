import { useEffect, useRef, useState } from 'react'

import { useTour } from '../../hooks/useTour'
import { useChat } from '../../contexts/ChatContext'
import { TourTooltip } from './TourTooltip'
import { SearchSimulation } from './SearchSimulation'
import { STEP_ACTIONS, STEP_CLEANUP } from './tourActions'

interface FocusBounds {
  top: number
  right: number
  bottom: number
  left: number
}

const FOCUS_PADDING = 8

function getFocusBounds(element: Element): FocusBounds {
  const rect = element.getBoundingClientRect()
  return {
    top: Math.max(0, rect.top - FOCUS_PADDING),
    right: Math.min(window.innerWidth, rect.right + FOCUS_PADDING),
    bottom: Math.min(window.innerHeight, rect.bottom + FOCUS_PADDING),
    left: Math.max(0, rect.left - FOCUS_PADDING),
  }
}

export function TourOverlay() {
  const {
    isActive,
    steps,
    currentStepIndex,
    exit,
    targetReady,
    setAgentResponsePending,
  } = useTour()
  const { closeChat, cancelRecaptcha, openChatBypassRecaptcha, tourChatAwaitingResponse } = useChat()
  const [focusBounds, setFocusBounds] = useState<FocusBounds | null>(null)
  const prevStepRef = useRef<number>(-1)

  useEffect(() => {
    if (!isActive || steps.length === 0) {
      setFocusBounds(null)
      return
    }

    const currentStep = steps[currentStepIndex]
    if (!currentStep) {
      return
    }

    const updateFocusBounds = () => {
      const element = targetReady ? document.querySelector(currentStep.targetSelector) : null
      setFocusBounds(element ? getFocusBounds(element) : null)
    }

    updateFocusBounds()
    window.addEventListener('resize', updateFocusBounds)
    window.addEventListener('scroll', updateFocusBounds, true)

    return () => {
      window.removeEventListener('resize', updateFocusBounds)
      window.removeEventListener('scroll', updateFocusBounds, true)
    }
  }, [isActive, steps, currentStepIndex, targetReady])

  useEffect(() => {
    const isAiStep = steps[currentStepIndex]?.targetSelector === '.top-nav__ask-ai'
    const waitingForResponse = isActive && isAiStep && tourChatAwaitingResponse
    setAgentResponsePending?.(waitingForResponse)
    if (!waitingForResponse) return

    // A failed stream must not strand a visitor on the tour indefinitely. This gives the
    // assistant a generous window, after which the normal per-step timer takes over.
    const fallbackTimer = window.setTimeout(() => setAgentResponsePending?.(false), 45000)
    return () => window.clearTimeout(fallbackTimer)
  }, [isActive, steps, currentStepIndex, tourChatAwaitingResponse, setAgentResponsePending])

  // Execute step actions on enter & cleanup on leave
  useEffect(() => {
    if (!isActive || steps.length === 0) {
      // Tour exited — cleanup the step that was active
      const exitIndex = prevStepRef.current
      prevStepRef.current = -1
      if (exitIndex >= 0 && exitIndex < steps.length) {
        const exitStep = steps[exitIndex]
        const cleanup = STEP_CLEANUP[exitStep.targetSelector]
        if (cleanup) {
          if (cleanup.type === 'openChat') {
            closeChat()
            cancelRecaptcha()
          } else if (cleanup.type === 'clickElement' && cleanup.clickTarget) {
            const el = document.querySelector<HTMLElement>(cleanup.clickTarget)
            if (el) el.click()
          }
        }
      }
      return
    }

    const prevIndex = prevStepRef.current
    prevStepRef.current = currentStepIndex

    // Cleanup previous step
    if (prevIndex >= 0 && prevIndex < steps.length) {
      const prevStep = steps[prevIndex]
      const cleanup = STEP_CLEANUP[prevStep.targetSelector]
      if (cleanup) {
        if (cleanup.type === 'openChat') {
          closeChat()
          cancelRecaptcha()
        } else if (cleanup.type === 'clickElement' && cleanup.clickTarget) {
          const el = document.querySelector<HTMLElement>(cleanup.clickTarget)
          if (el) el.click()
        }
      }
    }

    // Execute action for current step
    const currentStep = steps[currentStepIndex]
    const action = STEP_ACTIONS[currentStep.targetSelector]
    if (!action) return

    const timer = setTimeout(() => {
      if (action.type === 'openChat') {
        openChatBypassRecaptcha(action.chatQuery)
      } else if (action.type === 'clickElement' && action.clickTarget) {
        const el = document.querySelector<HTMLElement>(action.clickTarget)
        if (el) el.click()
      }
    }, 500)

    return () => clearTimeout(timer)
  }, [isActive, currentStepIndex, steps, openChatBypassRecaptcha, closeChat, cancelRecaptcha])

  if (!isActive || steps.length === 0) {
    return null
  }

  const currentStep = steps[currentStepIndex]
  const isSearchStep = currentStep?.targetSelector === '.tour-search'

  return (
    <>
      <div className="tour-overlay" data-testid="tour-overlay" onClick={exit} role="presentation">
        {focusBounds ? (
          <>
            <div
              className="tour-overlay__shade"
              style={{ height: focusBounds.top, inset: '0 0 auto' }}
            />
            <div
              className="tour-overlay__shade"
              style={{ bottom: 0, left: 0, top: focusBounds.top, width: focusBounds.left }}
            />
            <div
              className="tour-overlay__shade"
              style={{ bottom: 0, left: focusBounds.right, top: focusBounds.top }}
            />
            <div
              className="tour-overlay__shade"
              style={{ inset: `${focusBounds.bottom}px 0 0` }}
            />
            <div
              aria-hidden="true"
              className="tour-overlay__focus"
              style={{
                height: focusBounds.bottom - focusBounds.top,
                left: focusBounds.left,
                top: focusBounds.top,
                width: focusBounds.right - focusBounds.left,
              }}
            />
          </>
        ) : (
          <div className="tour-overlay__shade" style={{ inset: 0 }} />
        )}
      </div>
      <TourTooltip />
      {isSearchStep && <SearchSimulation />}
    </>
  )
}
