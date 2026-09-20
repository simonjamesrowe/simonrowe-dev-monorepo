import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import type {
  Conversation,
  CreateMessageConversationRequest,
  CreatePermissionConversationRequest,
  PermissionResponseRequest,
  SendMessageRequest,
} from '../../lib/api/client';
import { apiClient } from '../../lib/api/client';

export const conversationKeys = {
  all: ['conversations'] as const,
  lists: () => [...conversationKeys.all, 'list'] as const,
  list: (familyId: string) => [...conversationKeys.lists(), familyId] as const,
};

export function useConversations(familyId: string | undefined) {
  return useQuery({
    queryKey: conversationKeys.list(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<Conversation[]>(`/families/${familyId}/conversations`);
      return data;
    },
    enabled: !!familyId,
  });
}

export function useCreateMessageConversation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      familyId,
      ...request
    }: CreateMessageConversationRequest & { familyId: string }) => {
      const { data } = await apiClient.post<Conversation>(
        `/families/${familyId}/conversations/message`,
        request,
      );
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: conversationKeys.list(data.familyId) });
    },
  });
}

export function useCreatePermissionConversation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      familyId,
      ...request
    }: CreatePermissionConversationRequest & { familyId: string }) => {
      const { data } = await apiClient.post<Conversation>(
        `/families/${familyId}/conversations/permission`,
        request,
      );
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: conversationKeys.list(data.familyId) });
    },
  });
}

export function useSendMessage() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      conversationId,
      familyId,
      ...request
    }: SendMessageRequest & { conversationId: string; familyId: string }) => {
      const { data } = await apiClient.post<Conversation>(
        `/conversations/${conversationId}/messages`,
        request,
      );
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: conversationKeys.list(data.familyId) });
    },
  });
}

export function useMarkConversationRead() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      conversationId,
      familyId,
    }: {
      conversationId: string;
      familyId: string;
    }) => {
      const { data } = await apiClient.post<Conversation>(
        `/conversations/${conversationId}/mark-read`,
      );
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: conversationKeys.list(data.familyId) });
    },
  });
}

export function useMarkConversationUnread() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      conversationId,
      familyId,
    }: {
      conversationId: string;
      familyId: string;
    }) => {
      const { data } = await apiClient.post<Conversation>(
        `/conversations/${conversationId}/mark-unread`,
      );
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: conversationKeys.list(data.familyId) });
    },
  });
}

export function useApprovePermission() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      permissionId,
      familyId,
      ...request
    }: PermissionResponseRequest & { permissionId: string; familyId: string }) => {
      const { data } = await apiClient.post<Conversation>(
        `/permissions/${permissionId}/approve`,
        request,
      );
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: conversationKeys.list(data.familyId) });
    },
  });
}

export function useDenyPermission() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      permissionId,
      familyId,
      ...request
    }: PermissionResponseRequest & { permissionId: string; familyId: string }) => {
      const { data } = await apiClient.post<Conversation>(
        `/permissions/${permissionId}/deny`,
        request,
      );
      return { ...data, familyId };
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: conversationKeys.list(data.familyId) });
    },
  });
}
