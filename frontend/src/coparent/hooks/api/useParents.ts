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
  withInvited: (familyId: string) => [...parentKeys.lists(), familyId, 'with-invited'] as const,
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

/**
 * The family's parents plus a co-parent who has been invited and not joined yet. Only for
 * expenses: an invited parent can share a cost, but cannot be messaged or put on the calendar,
 * so every other screen keeps using useParents.
 */
export function useParentsWithInvited(familyId: string | undefined) {
  return useQuery({
    queryKey: parentKeys.withInvited(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<Parent[]>(`/families/${familyId}/parents`, {
        params: { includeInvited: true },
      });
      return data;
    },
    enabled: !!familyId,
  });
}

/** Renames a co-parent who has not joined yet. Once they join, their name is theirs to set. */
export function useRenameInvitedParent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, fullName }: { id: string; familyId: string; fullName: string }) => {
      const { data } = await apiClient.patch<Parent>(`/parents/${id}/invited-name`, { fullName });
      return data;
    },
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: parentKeys.list(variables.familyId) });
    },
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

/**
 * The signed-in person's parent id within a family. Never "the primary parent": a co-parent
 * who is signed in must see their own defaults, requests and approvals, not the other side's.
 */
export function useCurrentParentId(familyId: string | undefined) {
  const { data: currentUser } = useCurrentUser();
  return currentUser?.profiles.find((profile) => profile.familyId === familyId)?.id;
}
