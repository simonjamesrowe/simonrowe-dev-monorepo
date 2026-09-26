import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { AssistantAction } from '../../types/assistant';

import { AssistantActionCard } from './AssistantActionCard';

const editMutation = {
  mutate: vi.fn(),
  mutateAsync: vi.fn().mockResolvedValue(undefined),
  isPending: false,
  error: null,
};
const approveMutation = { ...editMutation, mutate: vi.fn(), mutateAsync: vi.fn() };
const rejectMutation = { ...editMutation, mutate: vi.fn(), mutateAsync: vi.fn() };

vi.mock('../../hooks/api/useAssistant', () => ({
  useApproveAssistantAction: () => approveMutation,
  useEditAssistantAction: () => editMutation,
  useRejectAssistantAction: () => rejectMutation,
}));

describe('AssistantActionCard', () => {
  beforeEach(() => {
    editMutation.mutate.mockClear();
    editMutation.mutateAsync.mockClear();
    approveMutation.mutate.mockClear();
    rejectMutation.mutate.mockClear();
  });

  it('explains blocked uncertainty and disables approval', () => {
    const action: AssistantAction = {
      id: 'action-1',
      actionType: 'UPDATE_EVENT',
      status: 'BLOCKED',
      payload: { eventId: null, targetHint: 'the school fair', title: 'New title' },
      fieldErrors: [{ field: 'eventId', message: 'Select an exact target' }],
      revision: 0,
      targetSnapshot: {
        entityType: 'event',
        entityId: null,
        observedUpdatedAt: null,
        hint: 'the school fair',
      },
      result: null,
      failureMessage: null,
    };

    render(
      <AssistantActionCard
        familyId="family-1"
        batchId="batch-1"
        action={action}
        online
      />,
    );

    expect(screen.getByText(/Suggested target: the school fair/i)).toBeInTheDocument();
    expect(screen.getByText('Select an exact target')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeEnabled();
  });

  it('edits the typed payload and submits only the individual card', async () => {
    const user = userEvent.setup();
    const action: AssistantAction = {
      id: 'action-2',
      actionType: 'CREATE_EVENT',
      status: 'PENDING',
      payload: {
        type: 'school',
        title: 'Old title',
        startDate: '2026-10-01T09:00:00Z',
        childIds: ['child-1'],
      },
      fieldErrors: [],
      revision: 3,
      targetSnapshot: null,
      result: null,
      failureMessage: null,
    };
    render(
      <AssistantActionCard
        familyId="family-1"
        batchId="batch-1"
        action={action}
        online
        options={{ childIds: [{ value: 'child-1', label: 'Robin' }] }}
      />,
    );

    await user.click(screen.getByRole('button', { name: /Create event pending/i }));
    const title = screen.getByRole('textbox', { name: 'Title' });
    await user.clear(title);
    await user.type(title, 'School concert');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(editMutation.mutateAsync).toHaveBeenCalledWith(expect.objectContaining({
      familyId: 'family-1',
      batchId: 'batch-1',
      action: expect.objectContaining({
        id: 'action-2',
        payload: expect.objectContaining({ title: 'School concert', childIds: ['child-1'] }),
      }),
    }));
    expect(approveMutation.mutate).not.toHaveBeenCalled();
  });

  it('shows retry state and disables every decision while offline', async () => {
    const user = userEvent.setup();
    const action: AssistantAction = {
      id: 'action-3',
      actionType: 'SEND_MESSAGE',
      status: 'FAILED',
      payload: { conversationId: 'conversation-1', message: 'Hello' },
      fieldErrors: [],
      revision: 2,
      targetSnapshot: null,
      result: null,
      failureMessage: 'The action could not be applied. Try again.',
    };
    render(
      <AssistantActionCard
        familyId="family-1"
        batchId="batch-1"
        action={action}
        online={false}
      />,
    );

    await user.click(screen.getByRole('button', { name: /Send message failed/i }));
    expect(screen.getByText(action.failureMessage!)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  });
});
