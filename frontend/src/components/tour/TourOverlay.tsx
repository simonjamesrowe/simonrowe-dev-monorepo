import { useEffect, useRef } from 'react'

import { useTour } from '../../hooks/useTour'
import { TourTooltip } from './TourTooltip'
import { SearchSimulation } from './SearchSimulation'

export function TourOverlay() {
  const { isActive, steps, currentStepIndex, exit } = useTour()
  const spotlightRef = useRef<Element | null>(null)

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
