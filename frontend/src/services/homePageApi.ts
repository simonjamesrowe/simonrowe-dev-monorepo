import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { HomePageContent } from '../types/homePage'

export async function fetchHomePage(): Promise<HomePageContent> {
  return fetchWithRetry<HomePageContent>(`${API_BASE_URL}/api/home-page`, {
    fallbackMessage: 'Unable to load the home page.',
  })
}
