import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import { apiClient } from '../../lib/api/client';
import type { ScheduleChangeRequest, ProposedChange } from '../../types/calendar';

import { eventKeys } from './useEvents';

export const scheduleChangeRequestKeys = {
  all: ['scheduleChangeRequests'] as const,
  lists: () => [...scheduleChangeRequestKeys.all, 'list'] as const,
  list: (familyId: string) => [...scheduleChangeRequestKeys.lists(), familyId] as const,
  details: () => [...scheduleChangeRequestKeys.all, 'detail'] as const,
  detail: (id: string) => [...scheduleChangeRequestKeys.details(), id] as const,
};

export interface CreateScheduleChangeRequestRequest {
  originalEventId?: string | null;
  proposedChange: ProposedChange;
  reason: string;
}

export interface RespondToRequestRequest {
  responseNote?: string;
}

export function useScheduleChangeRequests(familyId: string | undefined) {
  return useQuery({
    queryKey: scheduleChangeRequestKeys.list(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<ScheduleChangeRequest[]>(
        `/families/${familyId}/schedule-change-requests`,
      );
      return data;
    },
    enabled: !!familyId,
  });
}

export function useScheduleChangeRequest(
  requestId: string | undefined,
  familyId: string | undefined,
) {
  return useQuery({
    queryKey: scheduleChangeRequestKeys.detail(requestId!),
    queryFn: async () => {
      const { data } = await apiClient.get<ScheduleChangeRequest>(
        `/families/${familyId}/schedule-change-requests/${requestId}`,
      );
      return data;
    },
    enabled: !!requestId && !!familyId,
  });
}

export function useCreateScheduleChangeRequest() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      familyId,
      ...request
    }: CreateScheduleChangeRequestRequest & { familyId: string }) => {
      const { data } = await apiClient.post<ScheduleChangeRequest>(
        `/families/${familyId}/schedule-change-requests`,
        request,
      );
      return data;
    },
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: scheduleChangeRequestKeys.list(variables.familyId),
      });
    },
  });
}

export function useApproveScheduleChangeRequest() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      id,
      familyId,
      ...request
    }: RespondToRequestRequest & { id: string; familyId: string }) => {
      const { data } = await apiClient.post<ScheduleChangeRequest>(
        `/families/${familyId}/schedule-change-requests/${id}/approve`,
        request,
      );
      return data;
    },
    onSuccess: (data, variables) => {
      queryClient.invalidateQueries({
        queryKey: scheduleChangeRequestKeys.list(variables.familyId),
      });
      queryClient.setQueryData(scheduleChangeRequestKeys.detail(data.id), data);
      queryClient.invalidateQueries({ queryKey: eventKeys.list(variables.familyId) });
    },
  });
}

export function useDeclineScheduleChangeRequest() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      id,
      familyId,
      ...request
    }: RespondToRequestRequest & { id: string; familyId: string }) => {
      const { data } = await apiClient.post<ScheduleChangeRequest>(
        `/families/${familyId}/schedule-change-requests/${id}/decline`,
        request,
      );
      return data;
    },
    onSuccess: (data, variables) => {
      queryClient.invalidateQueries({
        queryKey: scheduleChangeRequestKeys.list(variables.familyId),
      });
      queryClient.setQueryData(scheduleChangeRequestKeys.detail(data.id), data);
    },
  });
}

export function useDeleteScheduleChangeRequest() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, familyId }: { id: string; familyId: string }) => {
      await apiClient.delete(`/families/${familyId}/schedule-change-requests/${id}`);
      return { id, familyId };
    },
    onSuccess: ({ familyId }) => {
      queryClient.invalidateQueries({ queryKey: scheduleChangeRequestKeys.list(familyId) });
    },
  });
}
