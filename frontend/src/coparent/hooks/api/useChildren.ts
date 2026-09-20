import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import type { Child, CreateChildRequest, UpdateChildRequest } from '../../lib/api/client';
import { apiClient } from '../../lib/api/client';

import { familyKeys } from './useFamilies';

export const childKeys = {
  all: ['children'] as const,
  lists: () => [...childKeys.all, 'list'] as const,
  list: (familyId: string) => [...childKeys.lists(), familyId] as const,
  details: () => [...childKeys.all, 'detail'] as const,
  detail: (id: string) => [...childKeys.details(), id] as const,
};

export function useChildren(familyId: string | undefined) {
  return useQuery({
    queryKey: childKeys.list(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<Child[]>(`/families/${familyId}/children`);
      return data;
    },
    enabled: !!familyId,
  });
}

export function useCreateChild() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ familyId, ...request }: CreateChildRequest & { familyId: string }) => {
      const { data } = await apiClient.post<Child>(`/families/${familyId}/children`, request);
      return data;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: childKeys.list(data.familyId) });
      queryClient.invalidateQueries({ queryKey: familyKeys.detail(data.familyId) });
    },
  });
}

export function useUpdateChild() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, ...request }: UpdateChildRequest & { id: string }) => {
      const { data } = await apiClient.patch<Child>(`/children/${id}`, request);
      return data;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: childKeys.list(data.familyId) });
      queryClient.setQueryData(childKeys.detail(data.id), data);
    },
  });
}

export function useDeleteChild() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, familyId }: { id: string; familyId: string }) => {
      await apiClient.delete(`/children/${id}`);
      return { id, familyId };
    },
    onSuccess: ({ familyId }) => {
      queryClient.invalidateQueries({ queryKey: childKeys.list(familyId) });
      queryClient.invalidateQueries({ queryKey: familyKeys.detail(familyId) });
    },
  });
}
