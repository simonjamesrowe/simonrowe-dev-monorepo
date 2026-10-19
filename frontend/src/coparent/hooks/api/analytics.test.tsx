import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { apiClient } from '../../lib/api/client';
import type { AssistantAction } from '../../types/assistant';

import { useApproveAssistantAction, useRejectAssistantAction } from './useAssistant';
import {
  useCreateMessageConversation,
  useCreatePermissionConversation,
  useSendMessage,
} from './useConversations';
import { useExpenseTransition } from './useExpenses';
import { useCreateInvitation } from './useInvitations';

const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('../../../analytics/analytics', () => ({
  track,
  isAnalyticsConfigured: () => false,
  openCookieSettings: vi.fn(),
}));

vi.mock('../../lib/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    patch: vi.fn(),
    post: vi.fn(),
  },
}));

function wrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return ({ children }: PropsWithChildren) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

const expense = { id: 'expense-1', version: 3 };

describe('CoParent analytics', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reports an agreed expense with a count, and never the note', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { ...expense, version: 4 } });
    const { result } = renderHook(() => useExpenseTransition(), { wrapper: wrapper() });

    await act(async () => {
      await result.current.mutateAsync({ familyId: 'family-1', expense, transition: 'agree', note: 'Fine by me' });
    });

    expect(track).toHaveBeenCalledTimes(1);
    expect(track).toHaveBeenCalledWith('Expense Agreed', { count: 1 });
  });

  it('reports a disputed expense with no properties', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { ...expense, version: 4 } });
    const { result } = renderHook(() => useExpenseTransition(), { wrapper: wrapper() });

    await act(async () => {
      await result.current.mutateAsync({ familyId: 'family-1', expense, transition: 'dispute', note: 'Too much' });
    });

    expect(track).toHaveBeenCalledTimes(1);
    expect(track).toHaveBeenCalledWith('Expense Disputed');
  });

  it('reports neither for a repayment step, nor for a transition that failed', async () => {
    const { result } = renderHook(() => useExpenseTransition(), { wrapper: wrapper() });

    vi.mocked(apiClient.post).mockResolvedValue({ data: expense });
    await act(async () => {
      await result.current.mutateAsync({ familyId: 'family-1', expense, transition: 'reimbursement/claim' });
    });
    vi.mocked(apiClient.post).mockRejectedValue(new Error('409'));
    await act(async () => {
      await result.current.mutateAsync({ familyId: 'family-1', expense, transition: 'agree' }).catch(() => undefined);
    });

    expect(track).not.toHaveBeenCalled();
  });

  it('reports an approved Quick add expense as created from quick add', async () => {
    const pending = assistantAction('CREATE_EXPENSE', 'PENDING');
    vi.mocked(apiClient.post).mockResolvedValue({ data: { ...pending, status: 'APPLIED', revision: 2 } });
    const { result } = renderHook(() => useApproveAssistantAction(), { wrapper: wrapper() });

    await act(async () => {
      await result.current.mutateAsync({ familyId: 'family-1', batchId: 'batch-1', action: pending });
    });

    expect(track).toHaveBeenCalledWith('Expense Created', { source: 'quick-add', count: 1 });
  });

  it('does not report a Quick add action that created something else, or was rejected', async () => {
    const event = assistantAction('CREATE_EVENT', 'PENDING');
    vi.mocked(apiClient.post).mockResolvedValue({ data: { ...event, status: 'APPLIED' } });
    const approve = renderHook(() => useApproveAssistantAction(), { wrapper: wrapper() });
    await act(async () => {
      await approve.result.current.mutateAsync({ familyId: 'family-1', batchId: 'batch-1', action: event });
    });

    const expenseAction = assistantAction('CREATE_EXPENSE', 'PENDING');
    vi.mocked(apiClient.post).mockResolvedValue({ data: { ...expenseAction, status: 'REJECTED' } });
    const reject = renderHook(() => useRejectAssistantAction(), { wrapper: wrapper() });
    await act(async () => {
      await reject.result.current.mutateAsync({ familyId: 'family-1', batchId: 'batch-1', action: expenseAction });
    });

    expect(track).not.toHaveBeenCalled();
  });

  it('reports a sent invitation', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { id: 'invite-1', familyId: 'family-1' } });
    const { result } = renderHook(() => useCreateInvitation(), { wrapper: wrapper() });

    await act(async () => {
      await result.current.mutateAsync({ familyId: 'family-1', email: 'co@example.com' } as never);
    });

    expect(track).toHaveBeenCalledWith('Invite Sent');
  });

  it('reports each message by kind, once per send', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { id: 'conversation-1' } });
    const reply = renderHook(() => useSendMessage(), { wrapper: wrapper() });
    const start = renderHook(() => useCreateMessageConversation(), { wrapper: wrapper() });
    const ask = renderHook(() => useCreatePermissionConversation(), { wrapper: wrapper() });

    await act(async () => {
      await reply.result.current.mutateAsync({ conversationId: 'c1', familyId: 'family-1', content: 'Hi' } as never);
      await start.result.current.mutateAsync({ familyId: 'family-1', subject: 'S', message: 'M' } as never);
      await ask.result.current.mutateAsync({ familyId: 'family-1', subject: 'Trip' } as never);
    });

    expect(track.mock.calls).toEqual([
      ['Message Sent', { kind: 'message' }],
      ['Message Sent', { kind: 'message' }],
      ['Message Sent', { kind: 'permission' }],
    ]);
  });
});

function assistantAction(
  actionType: AssistantAction['actionType'],
  status: AssistantAction['status'],
): AssistantAction {
  return {
    id: 'action-1',
    actionType,
    status,
    payload: {},
    fieldErrors: [],
    revision: 1,
    targetSnapshot: null,
    result: null,
    failureMessage: null,
  };
}
