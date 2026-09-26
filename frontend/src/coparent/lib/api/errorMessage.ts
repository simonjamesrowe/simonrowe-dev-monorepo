/**
 * The message a CoParent API error carries for the person using the app. Controllers answer
 * a refused request with `{ code, message }`; anything else falls back to a generic sentence
 * rather than surfacing a status code or a stack.
 */
export function apiErrorMessage(error: unknown, fallback: string): string {
  if (error && typeof error === 'object' && 'response' in error) {
    const message = (error as { response?: { data?: { message?: unknown } } }).response?.data
      ?.message;
    if (typeof message === 'string' && message.trim()) return message;
  }
  return fallback;
}
