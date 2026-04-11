import { API_BASE_URL } from '../config/api'

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
  const response = await fetch(`${API_BASE_URL}/api/code-examples/${id}`)
  if (!response.ok) throw new Error('Code example not found')
  return response.json()
}
