import { API_BASE_URL } from '../config/api'
import type { ISkillGroup, ISkillGroupDetail } from '../types/skill'

async function parseErrorMessage(response: Response, fallback: string): Promise<string> {
  try {
    const errorPayload = await response.json()
    if (typeof errorPayload.message === 'string' && errorPayload.message.trim() !== '') {
      return errorPayload.message
    }
  } catch {
    // Keep default fallback message when the response has no JSON payload.
  }

  return fallback
}

export async function fetchSkillGroups(): Promise<ISkillGroup[]> {
  const response = await fetch(`${API_BASE_URL}/api/skills`)

  if (!response.ok) {
    throw new Error(await parseErrorMessage(response, 'Unable to load skills data.'))
  }

  return (await response.json()) as ISkillGroup[]
}

export async function fetchSkillGroup(id: string): Promise<ISkillGroupDetail> {
  const response = await fetch(`${API_BASE_URL}/api/skills/${id}`)

  if (!response.ok) {
    throw new Error(await parseErrorMessage(response, 'Unable to load skill group.'))
  }

  return (await response.json()) as ISkillGroupDetail
}
