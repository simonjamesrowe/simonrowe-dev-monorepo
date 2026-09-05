import { createContext, useCallback, useEffect, useReducer, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { fetchTourSteps } from '../../services/tourApi'
import type { TourStep } from '../../types/tour'
import { useTourNarration } from './useTourNarration'
import { readAutoplayPaused, readingTimeMs, writeAutoplayPaused } from './tourPreferences'

// How long a step waits for its target before offering the visitor a way past it.
const TARGET_STALL_MS = 6000

/**
 * The beat between a step finishing what it had to show and the tour moving on.
 *
 * Autoplay is driven by the step's own content rather than a clock: a step advances once its
 * target is anchored, its narration has finished speaking, and any demonstration it runs has
 * played out. This is the deliberate pause after all of that, so the tour reads as paced
 * rather than as jumping the moment the audio stops.
 */
const POST_CONTENT_PAUSE_MS = 1800

export interface TourState {
  isActive: boolean
  currentStepIndex: number
  steps: TourStep[]
  searchValue: string
  autoAdvancePaused: boolean
  targetReady: boolean
  targetStalled: boolean
  agentResponsePending: boolean
  /** A demonstration the step runs itself, such as the animated search. */
  stepActivityPending: boolean
  /** Visitor has stopped the tour advancing on its own. */
  autoplayPaused: boolean
  /** Milliseconds until the tour moves on, or null when nothing is scheduled. */
  advanceCountdownMs: number | null
}

export interface TourContextValue extends TourState {
  start: () => void
  next: () => void
  prev: () => void
  exit: () => void
  setSearchValue: (value: string) => void
  pauseAutoAdvance: () => void
  resumeAutoAdvance: () => void
  setAgentResponsePending: (pending: boolean) => void
  /** Reported by a step that runs its own demonstration, so autoplay waits for it. */
  setStepActivityPending: (pending: boolean) => void
  /** Stops or resumes the tour advancing on its own. Remembered between visits. */
  toggleAutoplay: () => void
  /** True when the current step's audio is playing. */
  narrationSpeaking: boolean
  /** True when this tour has any spoken audio, so the mute control is worth showing. */
  narrationAvailable: boolean
  narrationMuted: boolean
  toggleNarrationMuted: () => void
}

type TourAction =
  | { type: 'START'; steps: TourStep[] }
  | { type: 'NEXT' }
  | { type: 'PREV' }
  | { type: 'EXIT' }
  | { type: 'SET_SEARCH_VALUE'; value: string }
  | { type: 'PAUSE_AUTO_ADVANCE' }
  | { type: 'RESUME_AUTO_ADVANCE' }
  | { type: 'TARGET_STATUS'; ready: boolean }
  | { type: 'TARGET_STALLED' }
  | { type: 'SET_STEP_ACTIVITY_PENDING'; pending: boolean }
  | { type: 'SET_AUTOPLAY_PAUSED'; paused: boolean }
  | { type: 'SET_ADVANCE_COUNTDOWN'; ms: number | null }
  | { type: 'SET_AGENT_RESPONSE_PENDING'; pending: boolean }

const initialState: TourState = {
  isActive: false,
  currentStepIndex: 0,
  steps: [],
  searchValue: '',
  autoAdvancePaused: false,
  targetReady: false,
  targetStalled: false,
  agentResponsePending: false,
  stepActivityPending: false,
  autoplayPaused: readAutoplayPaused(),
  advanceCountdownMs: null,
}

function tourReducer(state: TourState, action: TourAction): TourState {
  switch (action.type) {
    case 'START':
      return {
        ...state,
        isActive: true,
        currentStepIndex: 0,
        steps: action.steps,
        searchValue: '',
        autoAdvancePaused: false,
        targetReady: false,
        targetStalled: false,
        agentResponsePending: false,
        stepActivityPending: false,
        advanceCountdownMs: null,
      }
    case 'NEXT':
      if (state.currentStepIndex >= state.steps.length - 1) {
        return { ...initialState }
      }
      return {
        ...state,
        currentStepIndex: state.currentStepIndex + 1,
        searchValue: '',
        autoAdvancePaused: false,
        targetReady: false,
        targetStalled: false,
        agentResponsePending: false,
        stepActivityPending: false,
        advanceCountdownMs: null,
      }
    case 'PREV':
      if (state.currentStepIndex <= 0) {
        return state
      }
      return {
        ...state,
        currentStepIndex: state.currentStepIndex - 1,
        searchValue: '',
        autoAdvancePaused: false,
        targetReady: false,
        targetStalled: false,
        agentResponsePending: false,
        stepActivityPending: false,
        advanceCountdownMs: null,
      }
    case 'EXIT':
      return { ...initialState }
    case 'SET_SEARCH_VALUE':
      return { ...state, searchValue: action.value }
    case 'PAUSE_AUTO_ADVANCE':
      return { ...state, autoAdvancePaused: true }
    case 'RESUME_AUTO_ADVANCE':
      return { ...state, autoAdvancePaused: false }
    case 'TARGET_STATUS':
      return { ...state, targetReady: action.ready, targetStalled: false }
    case 'TARGET_STALLED':
      return { ...state, targetStalled: true }
    case 'SET_STEP_ACTIVITY_PENDING':
      return state.stepActivityPending === action.pending
        ? state
        : { ...state, stepActivityPending: action.pending }
    case 'SET_AUTOPLAY_PAUSED':
      return { ...state, autoplayPaused: action.paused, advanceCountdownMs: null }
    case 'SET_ADVANCE_COUNTDOWN':
      // Identity-stable when unchanged: this is dispatched from the autoplay effect on every
      // run, and a fresh object each time would re-render the tooltip continuously.
      return state.advanceCountdownMs === action.ms
        ? state
        : { ...state, advanceCountdownMs: action.ms }
    case 'SET_AGENT_RESPONSE_PENDING':
      return { ...state, agentResponsePending: action.pending }
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
  const [pageVisible, setPageVisible] = useState(() => document.visibilityState === 'visible')
  const stepStartedAtRef = useRef<number>(Date.now())
  const autoplayPausedRef = useRef<boolean>(readAutoplayPaused())
  const narration = useTourNarration(state.isActive, state.steps, state.currentStepIndex)

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

  const setAgentResponsePending = useCallback((pending: boolean) => {
    dispatch({ type: 'SET_AGENT_RESPONSE_PENDING', pending })
  }, [])

  const setStepActivityPending = useCallback((pending: boolean) => {
    dispatch({ type: 'SET_STEP_ACTIVITY_PENDING', pending })
  }, [])

  const toggleAutoplay = useCallback(() => {
    const paused = !autoplayPausedRef.current
    autoplayPausedRef.current = paused
    writeAutoplayPaused(paused)
    dispatch({ type: 'SET_AUTOPLAY_PAUSED', paused })
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

  // A visitor who switches tabs should never return to a tour that has moved on without them.
  // The default tour is manual, but this also protects any future CMS step with autoplay enabled.
  useEffect(() => {
    const updatePageVisibility = () => setPageVisible(document.visibilityState === 'visible')
    document.addEventListener('visibilitychange', updatePageVisibility)
    return () => document.removeEventListener('visibilitychange', updatePageVisibility)
  }, [])

  // Wait for the real route target before showing a spotlight or starting an optional timer.
  // A slow Raspberry Pi response is a normal state, not a reason to advance to the next step.
  useEffect(() => {
    if (!state.isActive || state.steps.length === 0) {
      return
    }

    const currentStep = state.steps[state.currentStepIndex]
    if (!currentStep) {
      return
    }
    const targetRoute = currentStep.route?.split('#')[0]
    if (targetRoute && targetRoute !== location.pathname) {
      return
    }

    let resolved = false
    let observer: MutationObserver | null = null
    let animationFrame: number | null = null
    let stallTimer: number | null = null
    const resolveTarget = () => {
      const element = document.querySelector(currentStep.targetSelector)
      if (element) {
        resolved = true
        observer?.disconnect()
        if (stallTimer !== null) {
          window.clearTimeout(stallTimer)
          stallTimer = null
        }
        // A section taller than the screen is scrolled to its top, not its middle: the
        // spotlight caps its height, and centring would light an arbitrary slice of the
        // middle instead of the heading and first rows the step is describing.
        const tallerThanViewport = element.getBoundingClientRect().height > window.innerHeight
        element.scrollIntoView({
          behavior: 'auto',
          block: tallerThanViewport ? 'start' : 'center',
        })
        // Let the route paint at its final scroll position before the overlay measures it.
        animationFrame = requestAnimationFrame(() => {
          dispatch({ type: 'TARGET_STATUS', ready: true })
        })
      }
    }

    resolveTarget()
    if (!resolved) {
      observer = new MutationObserver(resolveTarget)
      observer.observe(document.body, {
        attributes: true,
        childList: true,
        subtree: true,
      })
      // Keep waiting for the real target indefinitely, but stop holding the visitor hostage.
      // A selector that never matches must never fake a spotlight or start a countdown; it
      // only restores the controls so the tour can be continued or left behind.
      // `resolved` is re-checked inside the callback as well as the timer being cancelled on
      // resolution: a timer that has already fired cannot be cancelled, only ignored.
      stallTimer = window.setTimeout(() => {
        if (!resolved) {
          dispatch({ type: 'TARGET_STALLED' })
        }
      }, TARGET_STALL_MS)
    }

    return () => {
      observer?.disconnect()
      if (stallTimer !== null) {
        window.clearTimeout(stallTimer)
      }
      if (animationFrame !== null) {
        cancelAnimationFrame(animationFrame)
      }
    }
  }, [state.isActive, state.currentStepIndex, state.steps, location.pathname])

  // A step begins when it becomes current, not when it settles: the reading floor below is
  // measured from here so time spent waiting for a slow route counts towards it.
  useEffect(() => {
    stepStartedAtRef.current = Date.now()
  }, [state.currentStepIndex, state.isActive])

  /*
   * Autoplay, driven by the step's content rather than by a clock.
   *
   * A step advances once everything it had to present has finished: its target is anchored,
   * its narration has stopped speaking, and any demonstration it runs — the animated search,
   * the assistant's reply — has played out. Only then does the pause start. A fixed timer is
   * what made the deployed tour unusable on a slow host, because none of those things are
   * predictable from a duration written in a CMS.
   *
   * A step with no narration is settled immediately, so the reading floor is what keeps it on
   * screen long enough to read. For a narrated step the floor is almost never the binding
   * constraint, since the audio already takes about as long as reading it would.
   */
  useEffect(() => {
    const cancel = () => dispatch({ type: 'SET_ADVANCE_COUNTDOWN', ms: null })

    if (!state.isActive || state.steps.length === 0) {
      cancel()
      return
    }
    const currentStep = state.steps[state.currentStepIndex]
    if (!currentStep) {
      cancel()
      return
    }

    // The tour never closes itself. Ending on a card the visitor did not dismiss reads as the
    // page having crashed, so the last step waits for Finish however autoplay is set.
    const isLastStep = state.currentStepIndex >= state.steps.length - 1
    // An operator can hold a step open by setting its auto-advance to zero.
    const pauseMs = currentStep.autoAdvanceMs ?? POST_CONTENT_PAUSE_MS

    // Two independent pauses: `autoplayPaused` is the visitor's explicit choice, remembered
    // between visits; `autoAdvancePaused` is transient hover, so the tour cannot advance out
    // from under a pointer on its way to Next or Exit.
    if (state.autoplayPaused || state.autoAdvancePaused || isLastStep || pauseMs <= 0) {
      cancel()
      return
    }
    if (!pageVisible || !state.targetReady || state.agentResponsePending
        || state.stepActivityPending || !narration.settled) {
      cancel()
      return
    }

    const elapsed = Date.now() - stepStartedAtRef.current
    const delayMs = Math.max(pauseMs, readingTimeMs(currentStep.description) - elapsed)
    dispatch({ type: 'SET_ADVANCE_COUNTDOWN', ms: delayMs })

    const stepAtSet = state.currentStepIndex
    autoAdvanceStepRef.current = stepAtSet
    autoAdvanceTimerRef.current = setTimeout(() => {
      if (autoAdvanceStepRef.current !== stepAtSet) {
        return
      }
      const now = Date.now()
      if (now - lastAdvanceTimeRef.current < 300) {
        return
      }
      lastAdvanceTimeRef.current = now
      const nextStep = state.steps[stepAtSet + 1]
      if (nextStep.route && nextStep.route !== location.pathname) {
        navigate(nextStep.route)
      }
      dispatch({ type: 'NEXT' })
    }, delayMs)

    return () => {
      clearAutoAdvanceTimer()
    }
  }, [
    state.isActive,
    state.currentStepIndex,
    state.steps,
    state.autoplayPaused,
    state.autoAdvancePaused,
    state.agentResponsePending,
    state.stepActivityPending,
    state.targetReady,
    narration.settled,
    pageVisible,
    location.pathname,
    navigate,
    clearAutoAdvanceTimer,
  ])

  const contextValue: TourContextValue = {
    ...state,
    start,
    next,
    prev,
    exit,
    setSearchValue,
    pauseAutoAdvance,
    resumeAutoAdvance,
    setAgentResponsePending,
    setStepActivityPending,
    toggleAutoplay,
    narrationSpeaking: narration.speaking,
    narrationAvailable: narration.available,
    narrationMuted: narration.muted,
    toggleNarrationMuted: narration.toggleMuted,
  }

  return (
    <TourContext.Provider value={contextValue}>
      {children}
    </TourContext.Provider>
  )
}
