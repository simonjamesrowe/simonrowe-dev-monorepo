import { useEffect, useRef } from 'react'

import { useTour } from '../../hooks/useTour'
import { useChat } from '../../contexts/ChatContext'
import { TourTooltip } from './TourTooltip'
import { SearchSimulation } from './SearchSimulation'
import { STEP_ACTIONS, STEP_CLEANUP } from './tourActions'

export function TourOverlay() {
  const { isActive, steps, currentStepIndex, exit } = useTour()
  const { closeChat, cancelRecaptcha, openChatBypassRecaptcha } = useChat()
  const spotlightRef = useRef<Element | null>(null)
  const prevStepRef = useRef<number>(-1)

  useEffect(() => {
    if (!isActive || steps.length === 0) {
      if (spotlightRef.current) {
        spotlightRef.current.classList.remove('tour-spotlight')
        spotlightRef.current = null
      }
      return
    }

    const currentStep = steps[currentStepIndex]
    if (!currentStep) {
      return
    }

    // Remove spotlight from previous element
    if (spotlightRef.current) {
      spotlightRef.current.classList.remove('tour-spotlight')
    }

    // Apply spotlight to new element
    const element = document.querySelector(currentStep.targetSelector)
    if (element) {
      element.classList.add('tour-spotlight')
      spotlightRef.current = element
    } else {
      spotlightRef.current = null
    }

    return () => {
      if (spotlightRef.current) {
        spotlightRef.current.classList.remove('tour-spotlight')
        spotlightRef.current = null
      }
    }
  }, [isActive, steps, currentStepIndex])

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
      <div
        className="tour-overlay"
        data-testid="tour-overlay"
        onClick={exit}
        role="presentation"
      />
      <TourTooltip />
      {isSearchStep && <SearchSimulation />}
    </>
  )
}
