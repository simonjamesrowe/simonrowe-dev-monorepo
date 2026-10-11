// Finding the other payments to a merchant once one of them has been logged, so they can be logged
// the same way in one go.

import type { StatementMatch, StatementRowInput, StatementState } from '../../types/statements';

/**
 * The part of a statement description that names the merchant. Banks add store numbers and
 * references ("BOOTS 0775", "AMZNMktplace*AB12CD"), so any word with a digit in it goes, along
 * with punctuation and case. A description that is nothing but those is kept whole.
 */
export function merchantKey(description: string): string {
  const words = description
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((word) => word && !/\d/.test(word));
  return words.length > 0 ? words.join(' ') : description.trim().toLowerCase();
}

/** A transaction on the page that could be logged alongside the one just logged. */
export interface SameMerchantCandidate {
  fingerprint: string;
  uploadId: string | null;
  /** Null for a waiting suggestion, whose details the server already holds. */
  transaction: StatementRowInput | null;
  description: string;
  details: string | null;
  date: string;
  amountPence: number;
  account: string | null;
  state: StatementState | null;
  match: StatementMatch | null;
}

/**
 * The other transactions to the same merchant, newest first, leaving out the one just logged and
 * any already logged. The first sighting of a transaction wins, so a row of an upload is preferred
 * to the same transaction as a suggestion, since it carries everything needed to log it.
 */
export function sameMerchant(
  logged: { fingerprint: string; description: string },
  candidates: SameMerchantCandidate[],
): SameMerchantCandidate[] {
  const key = merchantKey(logged.description);
  const seen = new Set<string>([logged.fingerprint]);
  const found: SameMerchantCandidate[] = [];
  for (const candidate of candidates) {
    if (seen.has(candidate.fingerprint)) continue;
    seen.add(candidate.fingerprint);
    if (candidate.state === 'logged' || merchantKey(candidate.description) !== key) continue;
    found.push(candidate);
  }
  return found.sort((a, b) => b.date.localeCompare(a.date));
}

/**
 * Ticked to begin with: not one the parent said was not shared, nor one that looks like an
 * expense already. Looking like the expense just logged doesn't count: a fare paid every day has
 * the same amount within days of the one before.
 */
export function tickedByDefault(candidate: SameMerchantCandidate, loggedExpenseId: string): boolean {
  return candidate.state !== 'dismissed' && (candidate.match === null || candidate.match.id === loggedExpenseId);
}
