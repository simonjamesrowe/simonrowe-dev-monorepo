import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import type { Invitation, CreateInvitationRequest } from '../../lib/api/client';
import { apiClient } from '../../lib/api/client';

import { familyKeys } from './useFamilies';

export const invitationKeys = {
  all: ['invitations'] as const,
  lists: () => [...invitationKeys.all, 'list'] as const,
  list: (familyId: string) => [...invitationKeys.lists(), familyId] as const,
};

export function useInvitations(familyId: string | undefined) {
  return useQuery({
    queryKey: invitationKeys.list(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<Invitation[]>(`/families/${familyId}/invitations`);
      return data;
    },
    enabled: !!familyId,
  });
}

export function useCreateInvitation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      familyId,
      ...request
    }: CreateInvitationRequest & { familyId: string }) => {
      const { data } = await apiClient.post<Invitation>(
        `/families/${familyId}/invitations`,
        request,
      );
      return data;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: invitationKeys.list(data.familyId) });
      queryClient.invalidateQueries({ queryKey: familyKeys.detail(data.familyId) });
    },
  });
}

export function useResendInvitation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, familyId }: { id: string; familyId: string }) => {
      const { data } = await apiClient.post<Invitation>(`/invitations/${id}/resend`);
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: invitationKeys.list(data.familyId) });
    },
  });
}

export function useCancelInvitation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, familyId }: { id: string; familyId: string }) => {
      const { data } = await apiClient.post<Invitation>(`/invitations/${id}/cancel`);
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: invitationKeys.list(data.familyId) });
    },
  });
}

export function useAcceptInvitation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (token: string) => {
      const { data } = await apiClient.post<{
        message: string;
        invitation: { id: string; status: string; acceptedAt?: string };
        family: { id: string; name: string };
      }>('/invitations/accept', { token });
      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: invitationKeys.all });
      queryClient.invalidateQueries({ queryKey: familyKeys.all });
    },
  });
}
