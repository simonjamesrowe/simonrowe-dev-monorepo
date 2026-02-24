import { API_BASE_URL } from '../config/api'
import type { ContactFormData } from '../components/contact/contactFormSchema'

const CONTACT_ENDPOINT = `${API_BASE_URL}/api/contact-us`

export interface ValidationError {
  field: string
  message: string
}

export interface ApiErrorResponse {
  errors?: ValidationError[]
  error?: string
}

export class ContactApiError extends Error {
  constructor(
    message: string,
    public readonly errors?: ValidationError[],
    public readonly statusCode?: number,
  ) {
    super(message)
    this.name = 'ContactApiError'
  }
}

export async function submitContactForm(data: ContactFormData): Promise<void> {
  const response = await fetch(CONTACT_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })

  if (response.ok) {
    return
  }

  let errorBody: ApiErrorResponse = {}
  try {
    errorBody = (await response.json()) as ApiErrorResponse
  } catch {
    // ignore parse errors
  }

  if (errorBody.errors && errorBody.errors.length > 0) {
    throw new ContactApiError(
      errorBody.errors.map((e) => e.message).join(', '),
      errorBody.errors,
      response.status,
    )
  }

  throw new ContactApiError(
    errorBody.error ?? 'An unexpected error occurred. Please try again later.',
    undefined,
    response.status,
  )
}
