import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiClient } from '../../lib/api/client';
import type { Expense, ExpenseRequest, ExpenseSummary, SettleResult } from '../../types/expenses';

export const expenseKeys = {
  all: ['expenses'] as const,
  list: (familyId: string) => [...expenseKeys.all, 'list', familyId] as const,
  summary: (familyId: string) => [...expenseKeys.all, 'summary', familyId] as const,
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

/** Every expense change moves the list and the summary, so both are refetched. */
function useExpenseMutation<TVariables extends { familyId: string }, TResult>(
  mutationFn: (variables: TVariables) => Promise<TResult>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSettled: (_data, _error, variables) => {
      queryClient.invalidateQueries({ queryKey: expenseKeys.list(variables.familyId) });
      queryClient.invalidateQueries({ queryKey: expenseKeys.summary(variables.familyId) });
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

export function useSettleExpenses() {
  return useExpenseMutation(
    async ({ familyId, items }: { familyId: string; items: Pick<Expense, 'id' | 'version'>[] }) =>
      (await apiClient.post<SettleResult[]>(`${base(familyId)}/settle`, { items })).data,
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
