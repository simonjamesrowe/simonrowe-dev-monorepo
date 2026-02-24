import { createContext, useCallback, useEffect, useReducer, type ReactNode } from 'react'

import { fetchTourSteps } from '../../services/tourApi'
import type { TourStep } from '../../types/tour'

export interface TourState {
  isActive: boolean
  currentStepIndex: number
  steps: TourStep[]
  searchValue: string
}

export interface TourContextValue extends TourState {
  start: () => void
  next: () => void
  prev: () => void
  exit: () => void
  setSearchValue: (value: string) => void
}

type TourAction =
  | { type: 'START'; steps: TourStep[] }
  | { type: 'NEXT' }
  | { type: 'PREV' }
  | { type: 'EXIT' }
  | { type: 'SET_SEARCH_VALUE'; value: string }

const initialState: TourState = {
  isActive: false,
  currentStepIndex: 0,
  steps: [],
  searchValue: '',
}

function tourReducer(state: TourState, action: TourAction): TourState {
  switch (action.type) {
    case 'START':
      return { ...state, isActive: true, currentStepIndex: 0, steps: action.steps, searchValue: '' }
    case 'NEXT':
      if (state.currentStepIndex >= state.steps.length - 1) {
        return { ...initialState }
      }
      return { ...state, currentStepIndex: state.currentStepIndex + 1, searchValue: '' }
    case 'PREV':
      if (state.currentStepIndex <= 0) {
        return state
      }
      return { ...state, currentStepIndex: state.currentStepIndex - 1, searchValue: '' }
    case 'EXIT':
      return { ...initialState }
    case 'SET_SEARCH_VALUE':
      return { ...state, searchValue: action.value }
    default:
      return state
  }
}

export const TourContext = createContext<TourContextValue | null>(null)

interface TourProviderProps {
  children: ReactNode
}

export function TourProvider({ children }: TourProviderProps) {
  const [state, dispatch] = useReducer(tourReducer, initialState)

  const exit = useCallback(() => {
    dispatch({ type: 'EXIT' })
  }, [])

  const start = useCallback(async () => {
    try {
      const steps = await fetchTourSteps()
      if (steps.length > 0) {
        dispatch({ type: 'START', steps })
      }
    } catch {
      // Silently fail - tour is non-critical
    }
  }, [])

  const next = useCallback(() => {
    dispatch({ type: 'NEXT' })
  }, [])

  const prev = useCallback(() => {
    dispatch({ type: 'PREV' })
  }, [])

  const setSearchValue = useCallback((value: string) => {
    dispatch({ type: 'SET_SEARCH_VALUE', value })
  }, [])

  // T015: matchMedia listener for responsive exit
  useEffect(() => {
    const mediaQuery = window.matchMedia('(min-width: 768px)')

    const handleChange = (event: MediaQueryListEvent) => {
      if (!event.matches) {
        exit()
      }
    }

    mediaQuery.addEventListener('change', handleChange)
    return () => {
      mediaQuery.removeEventListener('change', handleChange)
    }
  }, [exit])

  // T014: Scroll target element into view on step change
  useEffect(() => {
    if (!state.isActive || state.steps.length === 0) {
      return
    }

    const currentStep = state.steps[state.currentStepIndex]
    if (!currentStep) {
      return
    }

    const element = document.querySelector(currentStep.targetSelector)
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }, [state.isActive, state.currentStepIndex, state.steps])

  const contextValue: TourContextValue = {
    ...state,
    start,
    next,
    prev,
    exit,
    setSearchValue,
  }

  return (
    <TourContext.Provider value={contextValue}>
      {children}
    </TourContext.Provider>
  )
}
