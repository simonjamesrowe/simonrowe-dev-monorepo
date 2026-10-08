import { render, screen, waitFor } from '@testing-library/react';
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

const uploadMutation = { mutateAsync: vi.fn().mockResolvedValue({}), isPending: false };
vi.mock('../../hooks/api/useExpenses', () => ({
  useUploadReceipt: () => uploadMutation,
}));

vi.mock('../../../pages/admin/schoolNoteImage', () => ({
  prepareSchoolNoteImage: vi.fn().mockResolvedValue(new Blob(['jpeg'], { type: 'image/jpeg' })),
}));

const expenseAction: AssistantAction = {
  id: 'action-expense',
  actionType: 'CREATE_EXPENSE',
  status: 'PENDING',
  payload: {
    title: 'Kids padded coat',
    amount: '39.00',
    currency: 'GBP',
    category: 'clothing',
    childIds: ['child-1'],
    timing: 'paid',
    date: '2026-10-08',
    payerId: 'parent-2',
    sharePercent: 50,
    notes: null,
  },
  fieldErrors: [],
  revision: 0,
  targetSnapshot: null,
  result: null,
  failureMessage: null,
};

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

  it('approves or rejects only this action', async () => {
    const user = userEvent.setup();
    const action: AssistantAction = {
      id: 'action-4',
      actionType: 'DELETE_EVENT',
      status: 'PENDING',
      payload: { eventId: 'event-1' },
      fieldErrors: [],
      revision: 1,
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
      />,
    );

    await user.click(screen.getByRole('button', { name: /Delete event pending/i }));
    await user.click(screen.getByRole('button', { name: 'Approve' }));
    await user.click(screen.getByRole('button', { name: 'Reject' }));

    expect(approveMutation.mutate).toHaveBeenCalledWith({
      familyId: 'family-1', batchId: 'batch-1', action,
    });
    expect(rejectMutation.mutate).toHaveBeenCalledWith({
      familyId: 'family-1', batchId: 'batch-1', action,
    });
  });

  it('says in pounds who will owe whom on an expense card', async () => {
    const user = userEvent.setup();
    render(
      <AssistantActionCard
        familyId="family-1"
        batchId="batch-1"
        action={expenseAction}
        online
        expenseContext={{ meId: 'parent-1', otherName: 'Sam' }}
      />,
    );

    await user.click(screen.getByRole('button', { name: /Add expense pending/i }));
    expect(screen.getByText('Kids padded coat · £39.00')).toBeInTheDocument();
    expect(screen.getByText('Sam paid £39.00. Your share is £19.50.')).toBeInTheDocument();
    expect(screen.getByText("You'll owe Sam £19.50 once Sam agrees.")).toBeInTheDocument();
  });

  it('attaches the analysed photo as the receipt only once the expense is approved', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:photo'), revokeObjectURL: vi.fn() });
    approveMutation.mutate.mockImplementation((_variables, options) => options?.onSuccess?.({
      action: { ...expenseAction, status: 'APPLIED', result: { entityType: 'expense', entityId: 'expense-9', route: '/expenses?expense=expense-9' } },
    }));
    const photo = new File(['jpeg'], 'receipt.jpg', { type: 'image/jpeg' });
    render(
      <AssistantActionCard
        familyId="family-1"
        batchId="batch-1"
        action={expenseAction}
        online
        receiptImage={photo}
      />,
    );

    await user.click(screen.getByRole('button', { name: /Add expense pending/i }));
    expect(screen.getByRole('checkbox', { name: 'Attach this photo as the receipt' })).toBeChecked();
    expect(uploadMutation.mutateAsync).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() => expect(uploadMutation.mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ familyId: 'family-1', expenseId: 'expense-9' }),
    ));
    vi.unstubAllGlobals();
    approveMutation.mutate.mockReset();
  });

  it('keeps the photo out of CoParent when the box is unticked', async () => {
    const user = userEvent.setup();
    uploadMutation.mutateAsync.mockClear();
    approveMutation.mutate.mockImplementation((_variables, options) => options?.onSuccess?.({
      action: { ...expenseAction, status: 'APPLIED', result: { entityType: 'expense', entityId: 'expense-9', route: '' } },
    }));
    render(
      <AssistantActionCard
        familyId="family-1"
        batchId="batch-1"
        action={expenseAction}
        online
        receiptImage={new File(['jpeg'], 'receipt.jpg', { type: 'image/jpeg' })}
      />,
    );

    await user.click(screen.getByRole('button', { name: /Add expense pending/i }));
    await user.click(screen.getByRole('checkbox', { name: 'Attach this photo as the receipt' }));
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(uploadMutation.mutateAsync).not.toHaveBeenCalled();
    approveMutation.mutate.mockReset();
  });
});
