import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiClient } from '../../lib/api/client';
import type {
  Expense,
  ExpenseRequest,
  ExpenseSettlement,
  ExpenseSummary,
  RecordSettlementResult,
  SettlementPayment,
  SettleResult,
} from '../../types/expenses';

export const expenseKeys = {
  all: ['expenses'] as const,
  list: (familyId: string) => [...expenseKeys.all, 'list', familyId] as const,
  summary: (familyId: string) => [...expenseKeys.all, 'summary', familyId] as const,
  settlements: (familyId: string) => [...expenseKeys.all, 'settlements', familyId] as const,
  receipt: (familyId: string, expenseId: string, receiptId: string) =>
    [...expenseKeys.all, 'receipt', familyId, expenseId, receiptId] as const,
};

const base = (familyId: string) => `/families/${familyId}/expenses`;

export function useExpenses(familyId: string | undefined) {
  return useQuery({
    queryKey: expenseKeys.list(familyId!),
    queryFn: async () => (await apiClient.get<Expense[]>(base(familyId!))).data,
    enabled: !!familyId,
  });
}

export function useExpenseSummary(familyId: string | undefined) {
  return useQuery({
    queryKey: expenseKeys.summary(familyId!),
    queryFn: async () => (await apiClient.get<ExpenseSummary>(`${base(familyId!)}/summary`)).data,
    enabled: !!familyId,
  });
}

/** Every settle-up in the family, newest first. */
export function useSettlements(familyId: string | undefined) {
  return useQuery({
    queryKey: expenseKeys.settlements(familyId!),
    queryFn: async () => (await apiClient.get<ExpenseSettlement[]>(`${base(familyId!)}/settlements`)).data,
    enabled: !!familyId,
  });
}

/** Every expense change moves the list, the summary and possibly a settle-up, so all are refetched. */
function useExpenseMutation<TVariables extends { familyId: string }, TResult>(
  mutationFn: (variables: TVariables) => Promise<TResult>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSettled: (_data, _error, variables) => {
      queryClient.invalidateQueries({ queryKey: expenseKeys.list(variables.familyId) });
      queryClient.invalidateQueries({ queryKey: expenseKeys.summary(variables.familyId) });
      queryClient.invalidateQueries({ queryKey: expenseKeys.settlements(variables.familyId) });
    },
  });
}

export function useCreateExpense() {
  return useExpenseMutation(async ({ familyId, ...request }: ExpenseRequest & { familyId: string }) =>
    (await apiClient.post<Expense>(base(familyId), request)).data);
}

export function useUpdateExpense() {
  return useExpenseMutation(
    async ({ familyId, id, ...request }: ExpenseRequest & { familyId: string; id: string }) =>
      (await apiClient.put<Expense>(`${base(familyId)}/${id}`, request)).data,
  );
}

export function useDeleteExpense() {
  return useExpenseMutation(
    async ({ familyId, id, version }: { familyId: string; id: string; version: number }) => {
      await apiClient.delete(`${base(familyId)}/${id}`, { params: { version } });
      return id;
    },
  );
}

export type ExpenseTransition =
  | 'agree'
  | 'dispute'
  | 'reimbursement/claim'
  | 'reimbursement/confirm'
  | 'reimbursement/reject'
  | 'reimbursement/mark';

/** Agree, dispute and the repayment steps: each sends the version the parent was looking at. */
export function useExpenseTransition() {
  return useExpenseMutation(
    async ({
      familyId,
      expense,
      transition,
      note,
    }: {
      familyId: string;
      expense: Pick<Expense, 'id' | 'version'>;
      transition: ExpenseTransition;
      note?: string;
    }) =>
      (
        await apiClient.post<Expense>(`${base(familyId)}/${expense.id}/${transition}`, {
          version: expense.version,
          note: note ?? null,
        })
      ).data,
  );
}

export function useMarkExpensePaid() {
  return useExpenseMutation(
    async ({
      familyId,
      expense,
      payerId,
      paidOn,
      amountPence,
    }: {
      familyId: string;
      expense: Pick<Expense, 'id' | 'version'>;
      payerId: string;
      paidOn: string;
      amountPence: number;
    }) =>
      (
        await apiClient.post<Expense>(`${base(familyId)}/${expense.id}/mark-paid`, {
          payerId,
          paidOn,
          amountPence,
          version: expense.version,
        })
      ).data,
  );
}

/**
 * Marks several expenses paid back at once, each exactly as its own "Mark paid back" would: what
 * the parent owes is paid back, what they are owed is reimbursed. No payment is recorded; that is
 * useRecordSettlement.
 */
export function useMarkPaidBack() {
  return useExpenseMutation(
    async ({ familyId, items, note }: { familyId: string; items: Pick<Expense, 'id' | 'version'>[]; note: string }) =>
      (
        await apiClient.post<SettleResult[]>(`${base(familyId)}/settle`, {
          items: items.map(({ id, version }) => ({ id, version })),
          note,
        })
      ).data,
  );
}

/** Settles the chosen expenses and records the payments that settled them. */
export function useRecordSettlement() {
  return useExpenseMutation(
    async ({
      familyId,
      items,
      payments,
    }: {
      familyId: string;
      items: Pick<Expense, 'id' | 'version'>[];
      payments: SettlementPayment[];
    }) =>
      (
        await apiClient.post<RecordSettlementResult>(`${base(familyId)}/settlements`, {
          items: items.map(({ id, version }) => ({ id, version })),
          payments,
        })
      ).data,
  );
}

/** Corrects a settle-up's payments: how much, when, how and with what reference. */
export function useUpdateSettlement() {
  return useExpenseMutation(
    async ({ familyId, settlementId, payments }: { familyId: string; settlementId: string; payments: SettlementPayment[] }) =>
      (await apiClient.put<ExpenseSettlement>(`${base(familyId)}/settlements/${settlementId}`, { payments })).data,
  );
}

/** The parent who was paid says a settle-up's payments arrived. */
export function useConfirmSettlement() {
  return useExpenseMutation(
    async ({ familyId, settlementId }: { familyId: string; settlementId: string }) =>
      (await apiClient.post<SettleResult[]>(`${base(familyId)}/settlements/${settlementId}/confirm`)).data,
  );
}

export function useUploadReceipt() {
  return useExpenseMutation(
    async ({ familyId, expenseId, file }: { familyId: string; expenseId: string; file: Blob & { name?: string } }) => {
      const form = new FormData();
      form.append('file', file, file.name ?? 'receipt.jpg');
      return (
        await apiClient.post<Expense>(`${base(familyId)}/${expenseId}/receipts`, form, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })
      ).data;
    },
  );
}

export function useDeleteReceipt() {
  return useExpenseMutation(
    async ({ familyId, expenseId, receiptId }: { familyId: string; expenseId: string; receiptId: string }) =>
      (await apiClient.delete<Expense>(`${base(familyId)}/${expenseId}/receipts/${receiptId}`)).data,
  );
}

/**
 * A receipt's bytes, fetched with the bearer token, because an <img src> cannot send one. The
 * caller turns it into an object URL and revokes it on unmount (see ReceiptThumb).
 */
export async function fetchReceiptBlob(
  familyId: string,
  expenseId: string,
  receiptId: string,
): Promise<Blob> {
  const { data } = await apiClient.get<Blob>(`${base(familyId)}/${expenseId}/receipts/${receiptId}`, {
    responseType: 'blob',
  });
  return data;
}
