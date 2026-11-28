import { apiErrorMessage } from '../../lib/api/errorMessage';

export const statusOf = (error: unknown): number | undefined =>
  error && typeof error === 'object' && 'response' in error
    ? (error as { response?: { status?: number } }).response?.status
    : undefined;

export const CONFLICT_MESSAGE =
  'Your co-parent changed this document while you had it open. It has been reloaded with their '
  + 'changes; check it and try again.';

/**
 * What to tell the parent when a document write fails. A 409 means they were looking at an old
 * copy (every write carries the version they saw), which deserves its own sentence.
 */
export function documentErrorMessage(error: unknown, fallback: string): string {
  if (statusOf(error) === 409) return CONFLICT_MESSAGE;
  return apiErrorMessage(error, fallback);
}
