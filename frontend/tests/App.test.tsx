import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import App from '../src/App'

vi.mock('../src/pages/HomePage', () => ({
  HomePage: () => <h1>Homepage</h1>,
}))

vi.mock('../src/components/tour/TourProvider', () => ({
  TourProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

describe('App', () => {
  it('routes / to HomePage', () => {
    window.history.pushState({}, '', '/')
    render(<App />)

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Homepage')
  })
})
