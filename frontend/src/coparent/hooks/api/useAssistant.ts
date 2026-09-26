import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiClient } from '../../lib/api/client';
import type {
  AssistantAction,
  AssistantActionType,
  AssistantBatch,
  AssistantBatchSummary,
} from '../../types/assistant';

import { conversationKeys } from './useConversations';
import { eventKeys } from './useEvents';
import { scheduleChangeRequestKeys } from './useScheduleChangeRequests';

export const assistantKeys = {
  all: ['coparent-assistant'] as const,
  config: () => [...assistantKeys.all, 'config'] as const,
  batches: (familyId: string) => [...assistantKeys.all, 'batches', familyId] as const,
  batch: (familyId: string, batchId: string) =>
    [...assistantKeys.batches(familyId), batchId] as const,
  categories: (familyId: string) => [...assistantKeys.all, 'categories', familyId] as const,
};

export interface AssistantEventCategory {
  id: string;
  name: string;
  system: boolean;
}

export function useAssistantEventCategories(familyId: string | undefined) {
  return useQuery({
    queryKey: assistantKeys.categories(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<AssistantEventCategory[]>(
        `/families/${familyId}/event-categories`,
      );
      return data;
    },
    enabled: Boolean(familyId),
  });
}

export function useAssistantConfig(enabled = true) {
  return useQuery({
    queryKey: assistantKeys.config(),
    queryFn: async () => {
      const { data } = await apiClient.get<{ enabled: boolean }>('/assistant/config');
      return data;
    },
    staleTime: 5 * 60 * 1000,
    enabled,
  });
}

export function useAssistantBatches(familyId: string | undefined) {
  return useQuery({
    queryKey: assistantKeys.batches(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<AssistantBatchSummary[]>(
        `/families/${familyId}/assistant/batches`,
      );
      return data;
    },
    enabled: Boolean(familyId),
  });
}

export function useAssistantBatch(familyId: string | undefined, batchId: string | undefined) {
  return useQuery({
    queryKey: assistantKeys.batch(familyId!, batchId!),
    queryFn: async () => {
      const { data } = await apiClient.get<AssistantBatch>(
        `/families/${familyId}/assistant/batches/${batchId}`,
      );
      return data;
    },
    enabled: Boolean(familyId && batchId),
  });
}

export function useAnalyseAssistantInput() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      familyId,
      text,
      image,
    }: {
      familyId: string;
      text: string;
      image: File | null;
    }) => {
      const form = new FormData();
      if (text.trim()) form.append('text', text.trim());
      if (image) form.append('image', image);
      const { data } = await apiClient.post<AssistantBatch>(
        `/families/${familyId}/assistant/batches`,
        form,
        { headers: { 'Content-Type': undefined } },
      );
      return data;
    },
    onSuccess: (batch) => {
      queryClient.setQueryData(assistantKeys.batch(batch.familyId, batch.id), batch);
      queryClient.invalidateQueries({ queryKey: assistantKeys.batches(batch.familyId) });
    },
  });
}

function replaceAction(batch: AssistantBatch | undefined, action: AssistantAction) {
  if (!batch) return batch;
  return {
    ...batch,
    actions: batch.actions.map((candidate) => (candidate.id === action.id ? action : candidate)),
  };
}

function invalidateDomain(queryClient: ReturnType<typeof useQueryClient>, familyId: string,
  actionType: AssistantActionType) {
  if (actionType.includes('EVENT') || actionType.includes('CATEGORY')) {
    queryClient.invalidateQueries({ queryKey: eventKeys.list(familyId) });
  }
  if (actionType.includes('CATEGORY')) {
    queryClient.invalidateQueries({ queryKey: assistantKeys.categories(familyId) });
  }
  if (actionType.includes('SCHEDULE_CHANGE')) {
    queryClient.invalidateQueries({ queryKey: scheduleChangeRequestKeys.list(familyId) });
  }
  if (actionType.includes('MESSAGE') || actionType.includes('CONVERSATION')
    || actionType.includes('PERMISSION')) {
    queryClient.invalidateQueries({ queryKey: conversationKeys.list(familyId) });
  }
}

export function useEditAssistantAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ familyId, batchId, action }: {
      familyId: string;
      batchId: string;
      action: AssistantAction;
    }) => {
      const { data } = await apiClient.patch<AssistantAction>(
        `/families/${familyId}/assistant/batches/${batchId}/actions/${action.id}`,
        { version: action.revision, actionType: action.actionType, payload: action.payload },
      );
      return { familyId, batchId, action: data };
    },
    onSuccess: ({ familyId, batchId, action }) => {
      queryClient.setQueryData<AssistantBatch>(assistantKeys.batch(familyId, batchId), (batch) =>
        replaceAction(batch, action));
    },
  });
}

function useDecision(decision: 'approve' | 'reject') {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ familyId, batchId, action }: {
      familyId: string;
      batchId: string;
      action: AssistantAction;
    }) => {
      const { data } = await apiClient.post<AssistantAction>(
        `/families/${familyId}/assistant/batches/${batchId}/actions/${action.id}/${decision}`,
        { version: action.revision },
      );
      return { familyId, batchId, action: data };
    },
    onSuccess: ({ familyId, batchId, action }) => {
      queryClient.setQueryData<AssistantBatch>(assistantKeys.batch(familyId, batchId), (batch) =>
        replaceAction(batch, action));
      if (action.status === 'APPLIED') invalidateDomain(queryClient, familyId, action.actionType);
    },
  });
}

export function useApproveAssistantAction() {
  return useDecision('approve');
}

export function useRejectAssistantAction() {
  return useDecision('reject');
}
