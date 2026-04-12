import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import App from '../src/App'

vi.mock('../src/pages/HomePage', () => ({
  HomePage: () => <h1>Homepage</h1>,
}))

vi.mock('../src/hooks/useProfile', () => ({
  useProfile: () => ({
    profile: null,
    loading: true,
    error: null,
    retry: vi.fn(),
  }),
}))

vi.mock('../src/components/tour/TourProvider', () => ({
  TourProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  TourContext: { Provider: ({ children }: { children: React.ReactNode }) => children },
}))

vi.mock('../src/hooks/useTour', () => ({
  useTour: () => ({
    isActive: false,
    currentStepIndex: 0,
    steps: [],
    searchValue: '',
    autoAdvancePaused: false,
    start: vi.fn(),
    next: vi.fn(),
    prev: vi.fn(),
    exit: vi.fn(),
    setSearchValue: vi.fn(),
    pauseAutoAdvance: vi.fn(),
    resumeAutoAdvance: vi.fn(),
  }),
}))

vi.mock('../src/components/tour/TourButton', () => ({
  TourButton: () => null,
}))

vi.mock('../src/components/tour/TourOverlay', () => ({
  TourOverlay: () => null,
}))

describe('App', () => {
  it('routes / to HomePage', () => {
    window.history.pushState({}, '', '/')
    render(<App />)

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Homepage')
  })
})
