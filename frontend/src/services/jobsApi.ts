import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { IJob, IJobDetail } from '../types/job'

export async function fetchJobs(): Promise<IJob[]> {
  return fetchWithRetry<IJob[]>(`${API_BASE_URL}/api/jobs`, {
    fallbackMessage: 'Unable to load jobs data.',
  })
}

export async function fetchJob(id: string): Promise<IJobDetail> {
  return fetchWithRetry<IJobDetail>(`${API_BASE_URL}/api/jobs/${id}`, {
    fallbackMessage: 'Unable to load job.',
  })
}
