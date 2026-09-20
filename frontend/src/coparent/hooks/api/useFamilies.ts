import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import type { Family, CreateFamilyRequest, UpdateFamilyRequest } from '../../lib/api/client';
import { apiClient } from '../../lib/api/client';

export const familyKeys = {
  all: ['families'] as const,
  lists: () => [...familyKeys.all, 'list'] as const,
  list: () => [...familyKeys.lists()] as const,
  details: () => [...familyKeys.all, 'detail'] as const,
  detail: (id: string) => [...familyKeys.details(), id] as const,
};

export function useFamilies() {
  return useQuery({
    queryKey: familyKeys.list(),
    queryFn: async () => {
      const { data } = await apiClient.get<Family[]>('/families');
      return data;
    },
  });
}

export function useFamily(id: string | undefined) {
  return useQuery({
    queryKey: familyKeys.detail(id!),
    queryFn: async () => {
      const { data } = await apiClient.get<Family>(`/families/${id}`);
      return data;
    },
    enabled: !!id,
  });
}

export function useCreateFamily() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (request: CreateFamilyRequest) => {
      const { data } = await apiClient.post<Family>('/families', request);
      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: familyKeys.lists() });
    },
  });
}

export function useUpdateFamily() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, ...request }: UpdateFamilyRequest & { id: string }) => {
      const { data } = await apiClient.patch<Family>(`/families/${id}`, request);
      return data;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: familyKeys.lists() });
      queryClient.setQueryData(familyKeys.detail(data.id), data);
    },
  });
}

export function useDeleteFamily() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/families/${id}`);
      return id;
    },
    onSuccess: (id) => {
      queryClient.invalidateQueries({ queryKey: familyKeys.lists() });
      queryClient.removeQueries({ queryKey: familyKeys.detail(id) });
    },
  });
}
