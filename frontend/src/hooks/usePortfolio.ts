import { useEffect, useState } from 'react'

import { fetchPortfolio } from '../services/portfolioApi'
import type { PortfolioProject } from '../types/portfolio'

let cached: Promise<PortfolioProject[]> | null = null

/**
 * One request for the whole page view: the header's Portfolio menu and the home carousel both
 * read the list, and the header remounts on every navigation. A failed request is not
 * cached, so the next mount tries again.
 */
function loadPortfolio(): Promise<PortfolioProject[]> {
  if (!cached) {
    cached = fetchPortfolio().catch(error => {
      cached = null
      throw error
    })
  }
  return cached
}

/** For tests, and for the admin editor after a save. */
export function clearPortfolioCache(): void {
  cached = null
}

export interface PortfolioState {
  projects: PortfolioProject[]
  loading: boolean
  error: string | null
}

export function usePortfolio(): PortfolioState {
  const [state, setState] = useState<PortfolioState>({ projects: [], loading: true, error: null })

  useEffect(() => {
    let cancelled = false
    loadPortfolio()
      .then(projects => {
        if (!cancelled) setState({ projects, loading: false, error: null })
      })
      .catch(error => {
        if (!cancelled) {
          setState({
            projects: [],
            loading: false,
            error: error instanceof Error ? error.message : 'Unable to load the portfolio.',
          })
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  return state
}
