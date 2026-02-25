import { API_BASE_URL } from '../config/api'
import type { IJob, IJobDetail } from '../types/job'

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

export async function fetchJobs(): Promise<IJob[]> {
  const response = await fetch(`${API_BASE_URL}/api/jobs`)

  if (!response.ok) {
    throw new Error(await parseErrorMessage(response, 'Unable to load jobs data.'))
  }

  return (await response.json()) as IJob[]
}

export async function fetchJob(id: string): Promise<IJobDetail> {
  const response = await fetch(`${API_BASE_URL}/api/jobs/${id}`)

  if (!response.ok) {
    throw new Error(await parseErrorMessage(response, 'Unable to load job.'))
  }

  return (await response.json()) as IJobDetail
}
