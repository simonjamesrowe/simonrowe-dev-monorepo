import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { ISkillGroup, ISkillGroupDetail } from '../types/skill'

const SKILLS_ENDPOINT = `${API_BASE_URL}/api/skills`

export async function fetchSkillGroups(): Promise<ISkillGroup[]> {
  return fetchWithRetry<ISkillGroup[]>(SKILLS_ENDPOINT, {
    fallbackMessage: 'Unable to load skills data.',
  })
}

export async function fetchSkillGroup(id: string): Promise<ISkillGroupDetail> {
  return fetchWithRetry<ISkillGroupDetail>(`${SKILLS_ENDPOINT}/${id}`, {
    fallbackMessage: 'Unable to load skill group.',
  })
}
