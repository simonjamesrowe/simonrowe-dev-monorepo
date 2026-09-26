import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { apiClient } from '../../lib/api/client';
import type { AssistantAction, AssistantBatch } from '../../types/assistant';

import {
  assistantKeys,
  useAnalyseAssistantInput,
  useApproveAssistantAction,
  useAssistantBatch,
  useAssistantBatches,
  useAssistantConfig,
  useAssistantEventCategories,
  useEditAssistantAction,
  useRejectAssistantAction,
} from './useAssistant';
import { eventKeys } from './useEvents';

vi.mock('../../lib/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    patch: vi.fn(),
    post: vi.fn(),
  },
}));

function createQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function wrapperFor(queryClient: QueryClient) {
  return ({ children }: PropsWithChildren) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

describe('assistant decisions', () => {
  beforeEach(() => vi.clearAllMocks());

  it('updates only the decided card and invalidates its domain query', async () => {
    const queryClient = createQueryClient();
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
    const wrapper = wrapperFor(queryClient);
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

  it('loads configuration, categories, recent batches, and one batch', async () => {
    vi.mocked(apiClient.get).mockImplementation(async (url: string) => {
      if (url === '/assistant/config') return { data: { enabled: true } };
      if (url.endsWith('/event-categories')) {
        return { data: [{ id: 'category-1', name: 'School', isSystem: false, isDefault: false }] };
      }
      if (url.endsWith('/assistant/batches/batch-1')) {
        return { data: { id: 'batch-1', actions: [] } };
      }
      return { data: [{ id: 'batch-1', actionCount: 0 }] };
    });
    const queryClient = createQueryClient();
    const wrapper = wrapperFor(queryClient);

    const config = renderHook(() => useAssistantConfig(), { wrapper });
    const categories = renderHook(() => useAssistantEventCategories('family-1'), { wrapper });
    const batches = renderHook(() => useAssistantBatches('family-1'), { wrapper });
    const batch = renderHook(() => useAssistantBatch('family-1', 'batch-1'), { wrapper });

    await waitFor(() => expect(config.result.current.data).toEqual({ enabled: true }));
    await waitFor(() => expect(categories.result.current.data).toEqual([
      { id: 'category-1', name: 'School', isSystem: false, isDefault: false },
    ]));
    await waitFor(() => expect(batches.result.current.data).toEqual([
      { id: 'batch-1', actionCount: 0 },
    ]));
    await waitFor(() => expect(batch.result.current.data).toEqual({
      id: 'batch-1', actions: [],
    }));
  });

  it('submits trimmed text and an image, then caches the created batch', async () => {
    const queryClient = createQueryClient();
    const wrapper = wrapperFor(queryClient);
    const created = {
      id: 'batch-2', familyId: 'family-1', status: 'READY', actions: [],
    } as unknown as AssistantBatch;
    vi.mocked(apiClient.post).mockResolvedValue({ data: created });
    const invalidation = vi.spyOn(queryClient, 'invalidateQueries');
    const image = new File(['image'], 'flyer.png', { type: 'image/png' });
    const { result } = renderHook(() => useAnalyseAssistantInput(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({
        familyId: 'family-1', text: '  School fair  ', image,
      });
    });

    const form = vi.mocked(apiClient.post).mock.calls[0][1] as FormData;
    expect(form.get('text')).toBe('School fair');
    expect(form.get('image')).toBe(image);
    expect(queryClient.getQueryData(assistantKeys.batch('family-1', 'batch-2'))).toBe(created);
    expect(invalidation).toHaveBeenCalledWith({
      queryKey: assistantKeys.batches('family-1'),
    });
  });

  it('edits and rejects one action in the cached batch', async () => {
    const queryClient = createQueryClient();
    const wrapper = wrapperFor(queryClient);
    const pending = action('PENDING');
    const batch = {
      id: 'batch-1', familyId: 'family-1', actions: [pending],
    } as unknown as AssistantBatch;
    queryClient.setQueryData(assistantKeys.batch('family-1', 'batch-1'), batch);
    vi.mocked(apiClient.patch).mockResolvedValue({
      data: { ...pending, payload: { title: 'Updated title' }, revision: 2 },
    });
    vi.mocked(apiClient.post).mockResolvedValue({
      data: { ...pending, status: 'REJECTED', revision: 3 },
    });
    const edit = renderHook(() => useEditAssistantAction(), { wrapper });
    const reject = renderHook(() => useRejectAssistantAction(), { wrapper });

    await act(async () => {
      await edit.result.current.mutateAsync({
        familyId: 'family-1', batchId: 'batch-1', action: pending,
      });
    });
    expect(apiClient.patch).toHaveBeenCalledWith(
      '/families/family-1/assistant/batches/batch-1/actions/action-1',
      { version: 1, actionType: 'CREATE_EVENT', payload: pending.payload },
    );

    await act(async () => {
      await reject.result.current.mutateAsync({
        familyId: 'family-1', batchId: 'batch-1', action: pending,
      });
    });
    expect(apiClient.post).toHaveBeenCalledWith(
      '/families/family-1/assistant/batches/batch-1/actions/action-1/reject',
      { version: 1 },
    );
    expect(queryClient.getQueryData<AssistantBatch>(
      assistantKeys.batch('family-1', 'batch-1'))?.actions[0].status).toBe('REJECTED');
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
