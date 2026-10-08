// Shared expenses, as the API returns them. Every amount is pence of pounds sterling.

export type ExpenseTiming = 'paid' | 'upcoming';
export type AgreementStatus = 'pending' | 'agreed' | 'disputed';
export type ReimbursementStatus = 'none' | 'outstanding' | 'claimed' | 'reimbursed';
export type ExpenseCategory =
  | 'education'
  | 'clothing'
  | 'medical'
  | 'activities'
  | 'childcare'
  | 'travel'
  | 'food'
  | 'other';

export interface ExpenseShare {
  parentId: string;
  percent: number;
  sharePence: number;
}

export interface ExpenseReceipt {
  id: string;
  contentType: string;
  sizeBytes: number;
  displayName: string;
  uploadedBy: string;
  uploadedAt: string;
}

export interface ExpenseHistoryEntry {
  at: string;
  by: string;
  action: string;
  note: string | null;
}

export interface Expense {
  id: string;
  familyId: string;
  title: string;
  category: ExpenseCategory;
  childIds: string[];
  amountPence: number;
  currency: 'GBP';
  timing: ExpenseTiming;
  date: string;
  payerId: string | null;
  shares: ExpenseShare[];
  agreement: {
    status: AgreementStatus;
    requestedBy: string;
    respondedBy: string | null;
    respondedAt: string | null;
    note: string | null;
  };
  reimbursement: {
    status: ReimbursementStatus;
    claimedBy: string | null;
    claimedAt: string | null;
    settledBy: string | null;
    settledAt: string | null;
    note: string | null;
  };
  owedPence: number;
  debtorParentId: string | null;
  creditorParentId: string | null;
  countsTowardsBalance: boolean;
  receipts: ExpenseReceipt[];
  notes: string | null;
  history: ExpenseHistoryEntry[];
  version: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ExpenseSummary {
  currency: 'GBP';
  balance: {
    netPence: number;
    debtorParentId: string | null;
    creditorParentId: string | null;
    expenseCount: number;
    awaitingYourConfirmationPence: number;
    awaitingTheirConfirmationPence: number;
  };
  needsYourAgreement: { count: number; totalPence: number };
  needsYourAction: number;
  awaitingOther: number;
  upcoming: { count: number; totalPence: number; yourSharePence: number; next: Expense[] };
  thisMonth: {
    totalPence: number;
    yourSharePence: number;
    byCategory: { category: ExpenseCategory; totalPence: number }[];
  };
}

/** The body of a create or full update. The version is required on update. */
export interface ExpenseRequest {
  title: string;
  category: ExpenseCategory;
  childIds: string[];
  amountPence: number;
  currency: 'GBP';
  timing: ExpenseTiming;
  date: string;
  payerId: string | null;
  shares: { parentId: string; percent: number }[];
  notes: string | null;
  version?: number;
}

export interface SettleResult {
  id: string;
  outcome: 'claimed' | 'reimbursed' | 'confirmed' | 'skipped' | 'failed';
  expense: Expense | null;
  message: string | null;
}
