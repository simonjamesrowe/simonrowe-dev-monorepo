import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiClient } from '../../lib/api/client';
import type { Expense, ExpenseRequest } from '../../types/expenses';
import type {
  StatementCheckResult,
  StatementOverview,
  StatementRowInput,
  StatementState,
  StatementTransaction,
  StatementUploadResult,
} from '../../types/statements';

import { expenseKeys } from './useExpenses';

export const statementKeys = {
  all: ['statements'] as const,
  overview: (familyId: string) => [...statementKeys.all, 'overview', familyId] as const,
  list: (familyId: string, status: StatementState) => [...statementKeys.all, 'list', familyId, status] as const,
};

const base = (familyId: string) => `/families/${familyId}/statements`;

/** Tab counts and recent uploads. Polls while an upload elsewhere is still being checked. */
export function useStatementOverview(familyId: string | undefined) {
  return useQuery({
    queryKey: statementKeys.overview(familyId!),
    queryFn: async () => (await apiClient.get<StatementOverview>(base(familyId!))).data,
    enabled: !!familyId,
    refetchInterval: (query) =>
      query.state.data?.uploads.some((upload) => upload.status === 'checking') ? 5000 : false,
  });
}

export function useStatementTransactions(familyId: string | undefined, status: StatementState, enabled = true) {
  return useQuery({
    queryKey: statementKeys.list(familyId!, status),
    queryFn: async () =>
      (await apiClient.get<StatementTransaction[]>(`${base(familyId!)}/transactions`, { params: { status } })).data,
    enabled: !!familyId && enabled,
  });
}

function useInvalidateStatements() {
  const queryClient = useQueryClient();
  return (familyId: string) =>
    queryClient.invalidateQueries({
      queryKey: statementKeys.all,
      predicate: (query) => query.queryKey.includes(familyId),
    });
}

export function useUploadStatement() {
  const invalidate = useInvalidateStatements();
  return useMutation({
    mutationFn: async ({ familyId, file }: { familyId: string; file: File }) => {
      const form = new FormData();
      form.append('file', file, file.name);
      return (
        await apiClient.post<StatementUploadResult>(base(familyId), form, {
          headers: { 'Content-Type': 'multipart/form-data' },
        })
      ).data;
    },
    onSettled: (_data, _error, variables) => invalidate(variables.familyId),
  });
}

/** Checks one batch of an upload's new rows. The page refreshes the lists when it has done them all. */
export function useCheckStatementRows() {
  return useMutation({
    mutationFn: async ({
      familyId,
      uploadId,
      rows,
    }: {
      familyId: string;
      uploadId: string;
      rows: StatementRowInput[];
    }) => (await apiClient.post<StatementCheckResult[]>(`${base(familyId)}/uploads/${uploadId}/check`, { rows })).data,
  });
}

export function useDismissStatementTransaction() {
  const invalidate = useInvalidateStatements();
  return useMutation({
    mutationFn: async ({ familyId, id }: { familyId: string; id: string }) =>
      (await apiClient.post<StatementTransaction>(`${base(familyId)}/transactions/${id}/dismiss`)).data,
    onSettled: (_data, _error, variables) => invalidate(variables.familyId),
  });
}

export function useForgetStatementTransaction() {
  const invalidate = useInvalidateStatements();
  return useMutation({
    mutationFn: async ({ familyId, id }: { familyId: string; id: string }) => {
      await apiClient.delete(`${base(familyId)}/transactions/${id}`);
      return id;
    },
    onSettled: (_data, _error, variables) => invalidate(variables.familyId),
  });
}

/** Turns a transaction into an expense. Both the statement lists and the expense lists move. */
export function useConvertStatementTransaction() {
  const queryClient = useQueryClient();
  const invalidate = useInvalidateStatements();
  return useMutation({
    mutationFn: async ({
      familyId,
      fingerprint,
      uploadId,
      transaction,
      expense,
    }: {
      familyId: string;
      fingerprint: string;
      uploadId?: string | null;
      transaction?: StatementRowInput | null;
      expense: ExpenseRequest;
    }) =>
      (
        await apiClient.post<Expense>(`${base(familyId)}/expenses`, {
          fingerprint,
          uploadId: uploadId ?? null,
          transaction: transaction ?? null,
          expense,
        })
      ).data,
    onSettled: (_data, _error, variables) => {
      invalidate(variables.familyId);
      queryClient.invalidateQueries({
        queryKey: expenseKeys.list(variables.familyId),
      });
      queryClient.invalidateQueries({
        queryKey: expenseKeys.summary(variables.familyId),
      });
    },
  });
}
