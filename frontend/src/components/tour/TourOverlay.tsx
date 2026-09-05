import { useEffect, useRef, useState } from 'react'

import { useTour } from '../../hooks/useTour'
import { useChat } from '../../contexts/ChatContext'
import { TourTooltip } from './TourTooltip'
import { SearchSimulation } from './SearchSimulation'
import { STEP_ACTIONS, STEP_CLEANUP } from './tourActions'
import { getFocusBounds, type FocusBounds } from './tourFocusBounds'

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

    const element = targetReady
      ? document.querySelector(currentStep.targetSelector)
      : null
    const updateFocusBounds = () => {
      setFocusBounds(element ? getFocusBounds(element) : null)
    }

    updateFocusBounds()
    window.addEventListener('resize', updateFocusBounds)
    window.addEventListener('scroll', updateFocusBounds, true)

    // A target can change shape while its step is open — most visibly the search step, whose
    // autocomplete panel appears a moment after the query is typed. Without re-measuring, the
    // spotlight keeps the size the target had when the step began and the results it exists to
    // show are left in the dark.
    let mutationObserver: MutationObserver | null = null
    let resizeObserver: ResizeObserver | null = null
    if (element) {
      mutationObserver = new MutationObserver(updateFocusBounds)
      mutationObserver.observe(element, { childList: true, subtree: true, attributes: true })
      // Feature-detected rather than assumed: jsdom does not implement it, and losing it
      // only costs a re-measure on resize, which must not take the overlay down.
      if (typeof ResizeObserver === 'function') {
        resizeObserver = new ResizeObserver(updateFocusBounds)
        resizeObserver.observe(element)
      }
    }

    return () => {
      window.removeEventListener('resize', updateFocusBounds)
      window.removeEventListener('scroll', updateFocusBounds, true)
      mutationObserver?.disconnect()
      resizeObserver?.disconnect()
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
            {/*
              Four shades tiling the viewport around the focus box, with no gap and no
              overlap. Both matter: the right-hand shade previously set only `left`, and a
              fixed-position empty div with no `right` and no `width` shrinks to nothing, so
              the whole band beside the focus stayed undimmed and read as a full-width
              highlight. The side shades also used to run to the bottom of the viewport and
              overlap the bottom shade, and two semi-transparent layers darken where they
              meet — a visibly heavier strip down each edge.
            */}
            <div
              className="tour-overlay__shade"
              style={{ top: 0, right: 0, left: 0, height: focusBounds.top }}
            />
            <div
              className="tour-overlay__shade"
              style={{
                top: focusBounds.top,
                left: 0,
                width: focusBounds.left,
                height: focusBounds.bottom - focusBounds.top,
              }}
            />
            <div
              className="tour-overlay__shade"
              style={{
                top: focusBounds.top,
                left: focusBounds.right,
                right: 0,
                height: focusBounds.bottom - focusBounds.top,
              }}
            />
            <div
              className="tour-overlay__shade"
              style={{ top: focusBounds.bottom, right: 0, bottom: 0, left: 0 }}
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
