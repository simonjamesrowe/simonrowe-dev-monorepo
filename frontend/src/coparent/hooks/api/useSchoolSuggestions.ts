import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import type { Child } from '../../lib/api/client';
import { apiClient } from '../../lib/api/client';
import type { SchoolReviewAction, SchoolSuggestion } from '../../types/schoolSuggestions';

import { childKeys } from './useChildren';
import { eventKeys } from './useEvents';

export const schoolSuggestionKeys = {
  all: ['school-suggestions'] as const,
  list: (familyId: string) => [...schoolSuggestionKeys.all, familyId] as const,
};

/** School events waiting for the family: new ones, and accepted ones the school changed. */
export function useSchoolSuggestions(familyId: string | undefined) {
  return useQuery({
    queryKey: schoolSuggestionKeys.list(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<SchoolSuggestion[]>(
        `/families/${familyId}/school-suggestions`,
      );
      return data;
    },
    enabled: !!familyId,
  });
}

function useDecision<T extends { familyId: string; id: string }>(
  request: (variables: T) => Promise<SchoolSuggestion>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: request,
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({
        queryKey: schoolSuggestionKeys.list(variables.familyId),
      });
      queryClient.invalidateQueries({
        queryKey: eventKeys.list(variables.familyId),
      });
    },
  });
}

export function useAcceptSchoolSuggestion() {
  return useDecision(async ({ familyId, id }: { familyId: string; id: string }) => {
    const { data } = await apiClient.post<SchoolSuggestion>(
      `/families/${familyId}/school-suggestions/${id}/accept`,
    );
    return data;
  });
}

export function useDismissSchoolSuggestion() {
  return useDecision(async ({ familyId, id }: { familyId: string; id: string }) => {
    const { data } = await apiClient.post<SchoolSuggestion>(
      `/families/${familyId}/school-suggestions/${id}/dismiss`,
    );
    return data;
  });
}

export function useReviewSchoolSuggestion() {
  return useDecision(
    async ({
      familyId,
      id,
      action,
    }: {
      familyId: string;
      id: string;
      action: SchoolReviewAction;
    }) => {
      const { data } = await apiClient.post<SchoolSuggestion>(
        `/families/${familyId}/school-suggestions/${id}/review`,
        { action },
      );
      return data;
    },
  );
}

/** Links a child to the school calendar (or updates the link), or unlinks them with null. */
export function useSaveSchoolCalendar() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      id,
      link,
    }: {
      id: string;
      link: { classTeacher: string; yearGroup: string } | null;
    }) => {
      const { data } = link
        ? await apiClient.put<Child>(`/children/${id}/school-calendar`, link)
        : await apiClient.delete<Child>(`/children/${id}/school-calendar`);
      return data;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({
        queryKey: childKeys.list(data.familyId),
      });
      queryClient.invalidateQueries({
        queryKey: schoolSuggestionKeys.list(data.familyId),
      });
    },
  });
}
