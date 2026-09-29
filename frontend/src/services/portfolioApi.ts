import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { PortfolioProject } from '../types/portfolio'

const PORTFOLIO_ENDPOINT = `${API_BASE_URL}/api/portfolio`

export async function fetchPortfolio(): Promise<PortfolioProject[]> {
  return fetchWithRetry<PortfolioProject[]>(PORTFOLIO_ENDPOINT, {
    fallbackMessage: 'Unable to load the portfolio.',
  })
}

/** Resolves to null for a slug with no detail page: unknown, unpublished or Coming soon. */
export async function fetchPortfolioProject(slug: string): Promise<PortfolioProject | null> {
  const response = await fetch(`${PORTFOLIO_ENDPOINT}/${encodeURIComponent(slug)}`)
  if (response.status === 404) return null
  if (!response.ok) throw new Error('Unable to load this project.')
  return (await response.json()) as PortfolioProject
}
