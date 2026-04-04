import { createContext, useCallback, useEffect, useReducer, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

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
  const navigate = useNavigate()
  const location = useLocation()

  const exit = useCallback(() => {
    dispatch({ type: 'EXIT' })
  }, [])

  const start = useCallback(async () => {
    try {
      const steps = await fetchTourSteps()
      if (steps.length > 0) {
        const firstStep = steps[0]
        if (firstStep.route && firstStep.route !== location.pathname) {
          navigate(firstStep.route)
        }
        dispatch({ type: 'START', steps })
      }
    } catch {
      // Silently fail - tour is non-critical
    }
  }, [location.pathname, navigate])

  const next = useCallback(() => {
    const nextIndex = state.currentStepIndex + 1
    if (nextIndex < state.steps.length) {
      const nextStep = state.steps[nextIndex]
      if (nextStep.route && nextStep.route !== location.pathname) {
        navigate(nextStep.route)
      }
    }
    dispatch({ type: 'NEXT' })
  }, [state.currentStepIndex, state.steps, location.pathname, navigate])

  const prev = useCallback(() => {
    const prevIndex = state.currentStepIndex - 1
    if (prevIndex >= 0) {
      const prevStep = state.steps[prevIndex]
      if (prevStep.route && prevStep.route !== location.pathname) {
        navigate(prevStep.route)
      }
    }
    dispatch({ type: 'PREV' })
  }, [state.currentStepIndex, state.steps, location.pathname, navigate])

  const setSearchValue = useCallback((value: string) => {
    dispatch({ type: 'SET_SEARCH_VALUE', value })
  }, [])

  // Responsive exit
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

  // Scroll target element into view on step change
  useEffect(() => {
    if (!state.isActive || state.steps.length === 0) {
      return
    }

    const currentStep = state.steps[state.currentStepIndex]
    if (!currentStep) {
      return
    }

    // Wait a tick for route navigation to settle before scrolling
    const timer = setTimeout(() => {
      const element = document.querySelector(currentStep.targetSelector)
      if (element) {
        element.scrollIntoView({ behavior: 'smooth', block: 'center' })
      }
    }, 100)

    return () => clearTimeout(timer)
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
