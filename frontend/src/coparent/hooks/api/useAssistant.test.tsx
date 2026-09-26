import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { apiClient } from '../../lib/api/client';
import type { AssistantAction, AssistantBatch } from '../../types/assistant';

import { assistantKeys, useApproveAssistantAction } from './useAssistant';
import { eventKeys } from './useEvents';

vi.mock('../../lib/api/client', () => ({
  apiClient: {
    post: vi.fn(),
  },
}));

describe('assistant decisions', () => {
  beforeEach(() => vi.clearAllMocks());

  it('updates only the decided card and invalidates its domain query', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const pending = action('PENDING');
    const applied: AssistantAction = {
      ...pending,
      status: 'APPLIED',
      revision: 2,
      result: { entityType: 'event', entityId: 'event-1', route: '/calendar' },
    };
    const batch: AssistantBatch = {
      id: 'batch-1',
      familyId: 'family-1',
      status: 'READY',
      model: 'gpt-5.4-nano',
      inputKinds: ['TEXT'],
      actionCount: 2,
      actions: [pending, { ...pending, id: 'action-2', actionType: 'CREATE_CATEGORY' }],
      createdAt: '2026-09-23T10:00:00Z',
      updatedAt: '2026-09-23T10:00:00Z',
      expiresAt: '2026-09-30T10:00:00Z',
    };
    queryClient.setQueryData(assistantKeys.batch('family-1', 'batch-1'), batch);
    const invalidation = vi.spyOn(queryClient, 'invalidateQueries');
    vi.mocked(apiClient.post).mockResolvedValue({ data: applied });
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(() => useApproveAssistantAction(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({
        familyId: 'family-1', batchId: 'batch-1', action: pending,
      });
    });

    expect(apiClient.post).toHaveBeenCalledWith(
      '/families/family-1/assistant/batches/batch-1/actions/action-1/approve',
      { version: 1 },
    );
    expect(queryClient.getQueryData<AssistantBatch>(
      assistantKeys.batch('family-1', 'batch-1'))?.actions).toEqual([
      applied,
      expect.objectContaining({ id: 'action-2', status: 'PENDING' }),
    ]);
    expect(invalidation).toHaveBeenCalledWith({ queryKey: eventKeys.list('family-1') });
  });
});

function action(status: AssistantAction['status']): AssistantAction {
  return {
    id: 'action-1',
    actionType: 'CREATE_EVENT',
    status,
    payload: { title: 'School concert' },
    fieldErrors: [],
    revision: 1,
    targetSnapshot: null,
    result: null,
    failureMessage: null,
  };
}
