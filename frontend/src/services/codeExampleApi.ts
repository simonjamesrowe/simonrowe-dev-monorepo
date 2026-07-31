import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'

export interface CodeExample {
  id: string
  title: string
  description: string
  language: string
  code: string
  skills: string[]
  createdAt: string
  updatedAt: string
}

export async function fetchCodeExample(id: string): Promise<CodeExample> {
  return fetchWithRetry<CodeExample>(`${API_BASE_URL}/api/code-examples/${id}`, {
    fallbackMessage: 'Code example not found',
  })
}
