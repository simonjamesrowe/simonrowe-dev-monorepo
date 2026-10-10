// Bank statement uploads and the shared-cost suggestions made from them. Private to the parent
// who uploaded them. Every amount is pence of pounds sterling.

import type { ExpenseCategory } from './expenses';

export type StatementState = 'checked' | 'suggested' | 'dismissed' | 'logged';
export type StatementConfidence = 'high' | 'medium' | 'low';
export type StatementUploadStatus = 'checking' | 'incomplete' | 'done' | 'off';

/** A family expense that looks like the same payment: same amount, within a few days. */
export interface StatementMatch {
  id: string;
  title: string;
  date: string;
}

/** One spending row of an upload. `state` is null when the row has never been seen before. */
export interface StatementRow {
  fingerprint: string;
  date: string;
  description: string;
  details: string;
  amountPence: number;
  account: string;
  state: StatementState | null;
  transactionId: string | null;
  expenseId: string | null;
  match: StatementMatch | null;
}

export interface StatementUploadSummary {
  id: string;
  format: string;
  account: string;
  from: string | null;
  to: string | null;
  spendingCount: number;
  moneyInCount: number;
  newCount: number;
  checkedCount: number;
  suggestedCount: number;
  status: StatementUploadStatus;
  createdAt: string;
}

export interface StatementUploadResult {
  /** Null when every row had been seen before, so nothing was added. */
  upload: StatementUploadSummary | null;
  format: string;
  account: string;
  from: string | null;
  to: string | null;
  moneyIn: number;
  unreadable: number;
  rows: StatementRow[];
  aiEnabled: boolean;
}

export interface StatementSuggestion {
  confidence: StatementConfidence;
  title: string | null;
  category: ExpenseCategory;
  childIds: string[];
  reason: string | null;
}

/** A recorded transaction. Details are present only while a suggestion waits for you. */
export interface StatementTransaction {
  id: string;
  fingerprint: string;
  status: StatementState;
  date: string | null;
  description: string | null;
  details: string | null;
  amountPence: number | null;
  account: string | null;
  suggestion: StatementSuggestion | null;
  merchant: string | null;
  expenseId: string | null;
  expenseTitle: string | null;
  uploadId: string | null;
  match: StatementMatch | null;
  decidedAt: string | null;
}

export interface StatementOverview {
  aiEnabled: boolean;
  toReview: number;
  dismissed: number;
  logged: number;
  uploads: StatementUploadSummary[];
}

export interface StatementCheckResult {
  fingerprint: string;
  state: StatementState;
  transaction: StatementTransaction | null;
}

/** A row as sent back to be checked or logged. */
export type StatementRowInput = Pick<
  StatementRow,
  'fingerprint' | 'date' | 'description' | 'details' | 'amountPence' | 'account'
>;
