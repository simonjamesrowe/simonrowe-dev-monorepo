import { createContext, useCallback, useEffect, useReducer, useRef, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { fetchTourSteps } from '../../services/tourApi'
import type { TourStep } from '../../types/tour'

const DEFAULT_AUTO_ADVANCE_MS = 7000

export interface TourState {
  isActive: boolean
  currentStepIndex: number
  steps: TourStep[]
  searchValue: string
  autoAdvancePaused: boolean
}

export interface TourContextValue extends TourState {
  start: () => void
  next: () => void
  prev: () => void
  exit: () => void
  setSearchValue: (value: string) => void
  pauseAutoAdvance: () => void
  resumeAutoAdvance: () => void
}

type TourAction =
  | { type: 'START'; steps: TourStep[] }
  | { type: 'NEXT' }
  | { type: 'PREV' }
  | { type: 'EXIT' }
  | { type: 'SET_SEARCH_VALUE'; value: string }
  | { type: 'PAUSE_AUTO_ADVANCE' }
  | { type: 'RESUME_AUTO_ADVANCE' }

const initialState: TourState = {
  isActive: false,
  currentStepIndex: 0,
  steps: [],
  searchValue: '',
  autoAdvancePaused: false,
}

function tourReducer(state: TourState, action: TourAction): TourState {
  switch (action.type) {
    case 'START':
      return { ...state, isActive: true, currentStepIndex: 0, steps: action.steps, searchValue: '', autoAdvancePaused: false }
    case 'NEXT':
      if (state.currentStepIndex >= state.steps.length - 1) {
        return { ...initialState }
      }
      return { ...state, currentStepIndex: state.currentStepIndex + 1, searchValue: '', autoAdvancePaused: false }
    case 'PREV':
      if (state.currentStepIndex <= 0) {
        return state
      }
      return { ...state, currentStepIndex: state.currentStepIndex - 1, searchValue: '', autoAdvancePaused: false }
    case 'EXIT':
      return { ...initialState }
    case 'SET_SEARCH_VALUE':
      return { ...state, searchValue: action.value }
    case 'PAUSE_AUTO_ADVANCE':
      return { ...state, autoAdvancePaused: true }
    case 'RESUME_AUTO_ADVANCE':
      return { ...state, autoAdvancePaused: false }
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
  const autoAdvanceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const autoAdvanceStepRef = useRef<number>(-1)
  const lastAdvanceTimeRef = useRef<number>(0)

  const clearAutoAdvanceTimer = useCallback(() => {
    if (autoAdvanceTimerRef.current) {
      clearTimeout(autoAdvanceTimerRef.current)
      autoAdvanceTimerRef.current = null
    }
    autoAdvanceStepRef.current = -1
  }, [])

  const exit = useCallback(() => {
    clearAutoAdvanceTimer()
    dispatch({ type: 'EXIT' })
  }, [clearAutoAdvanceTimer])

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
    const now = Date.now()
    if (now - lastAdvanceTimeRef.current < 300) return
    lastAdvanceTimeRef.current = now
    clearAutoAdvanceTimer()
    const nextIndex = state.currentStepIndex + 1
    if (nextIndex < state.steps.length) {
      const nextStep = state.steps[nextIndex]
      if (nextStep.route && nextStep.route !== location.pathname) {
        navigate(nextStep.route)
      }
    }
    dispatch({ type: 'NEXT' })
  }, [state.currentStepIndex, state.steps, location.pathname, navigate, clearAutoAdvanceTimer])

  const prev = useCallback(() => {
    clearAutoAdvanceTimer()
    const prevIndex = state.currentStepIndex - 1
    if (prevIndex >= 0) {
      const prevStep = state.steps[prevIndex]
      if (prevStep.route && prevStep.route !== location.pathname) {
        navigate(prevStep.route)
      }
    }
    dispatch({ type: 'PREV' })
  }, [state.currentStepIndex, state.steps, location.pathname, navigate, clearAutoAdvanceTimer])

  const setSearchValue = useCallback((value: string) => {
    dispatch({ type: 'SET_SEARCH_VALUE', value })
  }, [])

  const pauseAutoAdvance = useCallback(() => {
    dispatch({ type: 'PAUSE_AUTO_ADVANCE' })
  }, [])

  const resumeAutoAdvance = useCallback(() => {
    dispatch({ type: 'RESUME_AUTO_ADVANCE' })
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

  // Scroll target element into view on step change (with polling for cross-page nav)
  useEffect(() => {
    if (!state.isActive || state.steps.length === 0) {
      return
    }

    const currentStep = state.steps[state.currentStepIndex]
    if (!currentStep) {
      return
    }

    let attempts = 0
    const maxAttempts = 20 // 20 * 100ms = 2 seconds
    const poll = setInterval(() => {
      const element = document.querySelector(currentStep.targetSelector)
      if (element) {
        element.scrollIntoView({ behavior: 'smooth', block: 'center' })
        clearInterval(poll)
      } else if (++attempts >= maxAttempts) {
        clearInterval(poll)
      }
    }, 100)

    return () => clearInterval(poll)
  }, [state.isActive, state.currentStepIndex, state.steps])

  // Auto-advance timer
  useEffect(() => {
    if (!state.isActive || state.autoAdvancePaused || state.steps.length === 0) {
      return
    }

    const currentStep = state.steps[state.currentStepIndex]
    if (!currentStep) {
      return
    }

    const delayMs = currentStep.autoAdvanceMs ?? DEFAULT_AUTO_ADVANCE_MS
    const stepAtSet = state.currentStepIndex
    autoAdvanceStepRef.current = stepAtSet

    autoAdvanceTimerRef.current = setTimeout(() => {
      // Guard: only fire if this timer's step is still the current step
      if (autoAdvanceStepRef.current !== stepAtSet) return
      const now = Date.now()
      if (now - lastAdvanceTimeRef.current < 300) return
      lastAdvanceTimeRef.current = now

      if (stepAtSet >= state.steps.length - 1) {
        dispatch({ type: 'EXIT' })
      } else {
        const nextStep = state.steps[stepAtSet + 1]
        if (nextStep.route && nextStep.route !== location.pathname) {
          navigate(nextStep.route)
        }
        dispatch({ type: 'NEXT' })
      }
    }, delayMs)

    return () => {
      clearAutoAdvanceTimer()
    }
  }, [state.isActive, state.currentStepIndex, state.steps, state.autoAdvancePaused, location.pathname, navigate, clearAutoAdvanceTimer])

  const contextValue: TourContextValue = {
    ...state,
    start,
    next,
    prev,
    exit,
    setSearchValue,
    pauseAutoAdvance,
    resumeAutoAdvance,
  }

  return (
    <TourContext.Provider value={contextValue}>
      {children}
    </TourContext.Provider>
  )
}
