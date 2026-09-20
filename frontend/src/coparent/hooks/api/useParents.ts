import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import type {
  Parent,
  CurrentUser,
  UpdateParentRoleRequest,
  UpdateCurrentUserRequest,
  UpdatedCurrentUserProfile,
} from '../../lib/api/client';
import { apiClient } from '../../lib/api/client';

export const parentKeys = {
  all: ['parents'] as const,
  lists: () => [...parentKeys.all, 'list'] as const,
  list: (familyId: string) => [...parentKeys.lists(), familyId] as const,
  me: () => ['me'] as const,
};

export function useCurrentUser(enabled = true) {
  return useQuery({
    queryKey: parentKeys.me(),
    queryFn: async () => {
      const { data } = await apiClient.get<CurrentUser>('/me');
      return data;
    },
    enabled,
  });
}

export function useParents(familyId: string | undefined) {
  return useQuery({
    queryKey: parentKeys.list(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<Parent[]>(`/families/${familyId}/parents`);
      return data;
    },
    enabled: !!familyId,
  });
}

export function useUpdateParentRole() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      id,
      familyId,
      ...request
    }: UpdateParentRoleRequest & { id: string; familyId: string }) => {
      const { data } = await apiClient.patch<Parent>(`/parents/${id}/role`, request);
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: parentKeys.list(data.familyId) });
      queryClient.invalidateQueries({ queryKey: parentKeys.me() });
    },
  });
}

export function useUpdateCurrentUser() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (request: UpdateCurrentUserRequest) => {
      const { data } = await apiClient.patch<UpdatedCurrentUserProfile>('/me', request);
      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: parentKeys.me() });
      queryClient.invalidateQueries({ queryKey: parentKeys.lists() });
    },
  });
}
