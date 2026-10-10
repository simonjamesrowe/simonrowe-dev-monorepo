import { render, screen, waitFor, within } from '@testing-library/react';
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

  it('says exactly what it will do, and where it stands, before it is opened', () => {
    render(
      <AssistantActionCard
        familyId="family-1"
        batchId="batch-1"
        action={{ ...expenseAction, status: 'APPLIED' }}
        online
        options={{ childIds: [{ value: 'child-1', label: 'Robin Rowe' }] }}
        expenseContext={{ meId: 'parent-1', otherName: 'Sam' }}
      />,
    );

    const summary = screen.getByRole('button', { name: /^Add expense/ });
    expect(summary).toHaveAttribute('aria-expanded', 'false');
    expect(summary).toHaveTextContent('Approved');
    expect(screen.getByText('Kids padded coat · £39.00 · Sam paid, you owe Sam £19.50')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
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

    // Said once, under the field where the parent picks the event, not as a notice and an error.
    expect(screen.queryByText(/Suggested target/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Select an exact target')).not.toBeInTheDocument();
    expect(screen.getAllByText(/The note mentioned/)).toHaveLength(1);
    expect(screen.getByRole('textbox', { name: 'Event' })).toHaveAccessibleDescription(
      'The note mentioned “the school fair”. Choose which event that is.',
    );
    expect(screen.queryByRole('textbox', { name: 'What it refers to' })).not.toBeInTheDocument();
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

    await user.click(screen.getByRole('button', { name: /^Create event To review/ }));
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

    await user.click(screen.getByRole('button', { name: /^Send message Failed/ }));
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

    await user.click(screen.getByRole('button', { name: /^Delete event To review/ }));
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

    await user.click(screen.getByRole('button', { name: /^Add expense To review/ }));
    expect(screen.getByText('Kids padded coat · £39.00 · Sam paid, you owe Sam £19.50')).toBeInTheDocument();
    // The open card adds the split and when it counts, without repeating the line above it.
    expect(screen.getByText("Your share is £19.50 and Sam's is £19.50. It counts towards the balance once Sam agrees."))
      .toBeInTheDocument();
    expect(screen.queryByText('Kids padded coat · £39.00')).not.toBeInTheDocument();
    expect(screen.queryByText(/Sam paid £39.00/)).not.toBeInTheDocument();
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

    await user.click(screen.getByRole('button', { name: /^Add expense To review/ }));
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

    await user.click(screen.getByRole('button', { name: /^Add expense To review/ }));
    await user.click(screen.getByRole('checkbox', { name: 'Attach this photo as the receipt' }));
    await user.click(screen.getByRole('button', { name: 'Approve' }));

    expect(uploadMutation.mutateAsync).not.toHaveBeenCalled();
    approveMutation.mutate.mockReset();
  });
});

const children = [
  { value: 'child-1', label: 'Ethan Rowe' },
  { value: 'child-2', label: 'Ava Rowe' },
];
const parents = [
  { value: 'parent-1', label: 'Simon Rowe' },
  { value: 'parent-2', label: 'Rhian Raftopoulos' },
];

const eventAction: AssistantAction = {
  id: 'action-event',
  actionType: 'CREATE_EVENT',
  status: 'PENDING',
  payload: {
    type: 'activity',
    title: 'Swimming',
    startDate: '2026-10-13',
    endDate: '2026-12-15',
    startTime: '16:00',
    endTime: '17:00',
    allDay: null,
    parentId: null,
    parentIds: [],
    childIds: ['child-1'],
    location: 'Leisure centre',
    notes: null,
    recurringFrequency: 'weekly',
    recurringDays: ['tuesday'],
  },
  fieldErrors: [],
  revision: 1,
  targetSnapshot: null,
  result: null,
  failureMessage: null,
};

function renderCard(action: AssistantAction, options = {}) {
  render(
    <AssistantActionCard
      familyId="family-1"
      batchId="batch-1"
      action={action}
      online
      options={options}
      expenseContext={{ meId: 'parent-1', otherName: 'Rhian' }}
    />,
  );
}

const savedPayload = () => {
  const { calls } = editMutation.mutateAsync.mock;
  return calls[calls.length - 1]?.[0].action.payload;
};

describe('AssistantActionCard editor controls', () => {
  beforeEach(() => editMutation.mutateAsync.mockClear());

  it('gives an event date and time pickers, type chips, repeat chips and child chips', async () => {
    const user = userEvent.setup();
    renderCard(eventAction, {
      childIds: children,
      parentIds: parents,
      parentId: parents,
      eventType: [{ value: 'Therapy', label: 'Therapy' }, { value: 'school', label: 'School' }],
    });
    await user.click(screen.getByRole('button', { name: /^Create event/ }));

    expect(screen.getByLabelText('Start date')).toHaveAttribute('type', 'date');
    expect(screen.getByLabelText('End date')).toHaveAttribute('type', 'date');
    expect(screen.getByLabelText('Start time')).toHaveAttribute('type', 'time');
    expect(screen.getByLabelText('Start time')).toHaveValue('16:00');

    const type = screen.getByRole('radiogroup', { name: 'Type' });
    expect(within(type).getByRole('radio', { name: 'Activity' })).toBeChecked();
    // The family's own category is offered once, and "school" is not listed twice.
    expect(within(type).getByRole('radio', { name: 'Therapy' })).toBeInTheDocument();
    expect(within(type).getAllByRole('radio', { name: 'School' })).toHaveLength(1);
    expect(within(type).getByRole('radio', { name: 'Other' })).not.toBeChecked();

    const repeats = screen.getByRole('radiogroup', { name: 'Repeats' });
    expect(within(repeats).getByRole('radio', { name: 'Every week' })).toBeChecked();
    const days = screen.getByRole('group', { name: 'Repeats on' });
    expect(within(days).getAllByRole('checkbox').map((day) => day.getAttribute('aria-label')))
      .toEqual(['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']);
    expect(within(days).getByRole('checkbox', { name: 'Tuesday' })).toBeChecked();

    const forWhom = screen.getByRole('group', { name: 'For' });
    expect(within(forWhom).getByRole('checkbox', { name: 'Ethan Rowe' })).toBeChecked();
    expect(within(forWhom).getByRole('checkbox', { name: 'Ava Rowe' })).not.toBeChecked();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'All day' })).not.toBeChecked();
  });

  it('sends an untouched card back exactly as it came', async () => {
    const user = userEvent.setup();
    renderCard(eventAction, { childIds: children, parentIds: parents, parentId: parents });
    await user.click(screen.getByRole('button', { name: /^Create event/ }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(savedPayload()).toEqual(eventAction.payload);
  });

  it('keeps every value in the shape the server reads after edits', async () => {
    const user = userEvent.setup();
    renderCard(eventAction, { childIds: children, parentIds: parents, parentId: parents });
    await user.click(screen.getByRole('button', { name: /^Create event/ }));

    await user.click(screen.getByRole('checkbox', { name: 'Ava Rowe' }));
    await user.click(screen.getByRole('checkbox', { name: 'Thursday' }));
    await user.click(screen.getByRole('checkbox', { name: 'Monday' }));
    await user.click(screen.getByRole('radio', { name: 'Medical' }));
    const start = screen.getByLabelText('Start time');
    await user.clear(start);
    await user.type(start, '15:30');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(savedPayload()).toEqual({
      ...eventAction.payload,
      type: 'medical',
      startTime: '15:30',
      childIds: ['child-1', 'child-2'],
      recurringDays: ['monday', 'tuesday', 'thursday'],
    });
  });

  it('takes a type of the parent’s own, and clears a repeat', async () => {
    const user = userEvent.setup();
    renderCard(eventAction, { childIds: children });
    await user.click(screen.getByRole('button', { name: /^Create event/ }));

    await user.click(screen.getByRole('radio', { name: 'Other' }));
    await user.type(screen.getByRole('textbox', { name: 'Other type' }), 'Birthday party');
    await user.click(screen.getByRole('radio', { name: 'Does not repeat' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(savedPayload()).toMatchObject({ type: 'Birthday party', recurringFrequency: null });
  });

  it('shows a type the list does not have as Other, with its words', async () => {
    const user = userEvent.setup();
    renderCard({ ...eventAction, payload: { ...eventAction.payload, type: 'Football' } });
    await user.click(screen.getByRole('button', { name: /^Create event/ }));

    expect(screen.getByRole('radio', { name: 'Other' })).toBeChecked();
    expect(screen.getByRole('textbox', { name: 'Other type' })).toHaveValue('Football');
  });

  it('gives an expense a percentage, a currency, payer chips and child chips', async () => {
    const user = userEvent.setup();
    const action: AssistantAction = {
      ...expenseAction,
      payload: { ...expenseAction.payload, payerId: null, timing: 'upcoming', currency: null },
    };
    renderCard(action, {
      childIds: children,
      payerId: parents,
      currency: [{ value: 'GBP', label: 'GBP (£)' }],
      timing: [{ value: 'paid', label: 'Already paid' }, { value: 'upcoming', label: 'Coming up' }],
      category: ['activities', 'childcare', 'clothing', 'education', 'food', 'medical', 'other',
        'travel'].map((value) => ({ value, label: value })),
    });
    await user.click(screen.getByRole('button', { name: /^Add expense/ }));

    const share = screen.getByRole('spinbutton', { name: 'Your share' });
    expect(share).toHaveValue(50);
    expect(share).toHaveAttribute('min', '0');
    expect(share).toHaveAttribute('max', '100');
    expect(screen.getByText('%')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Currency' })).toHaveValue('GBP');
    expect(screen.getByRole('combobox', { name: 'Category' })).toHaveValue('clothing');

    const payer = screen.getByRole('radiogroup', { name: 'Who paid, or will pay' });
    expect(within(payer).getByRole('radio', { name: 'Not decided yet' })).toBeChecked();
    expect(within(screen.getByRole('radiogroup', { name: 'Paid or coming up' }))
      .getByRole('radio', { name: 'Coming up' })).toBeChecked();

    await user.click(within(payer).getByRole('radio', { name: 'Rhian Raftopoulos' }));
    await user.clear(share);
    await user.type(share, '60');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(savedPayload()).toEqual({
      ...action.payload,
      currency: 'GBP',
      payerId: 'parent-2',
      sharePercent: 60,
    });
  });

  it('keeps a currency that is not pounds showing until the parent changes it', async () => {
    const user = userEvent.setup();
    renderCard({
      ...expenseAction,
      status: 'BLOCKED',
      payload: { ...expenseAction.payload, amount: '40', currency: 'USD' },
      fieldErrors: [{ field: 'currency', message: 'Expenses are in pounds sterling.' }],
    }, { currency: [{ value: 'GBP', label: 'GBP (£)' }] });

    const currency = screen.getByRole('combobox', { name: 'Currency' });
    expect(currency).toHaveValue('USD');
    expect(currency).toHaveAccessibleDescription('Expenses are in pounds sterling.');
    expect(screen.getAllByText('Expenses are in pounds sterling.')).toHaveLength(1);
    await user.selectOptions(currency, 'GBP');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(savedPayload()).toMatchObject({ currency: 'GBP', amount: '40' });
  });

  it('keeps a plain text box for a field it does not recognise', async () => {
    const user = userEvent.setup();
    renderCard({
      ...eventAction,
      actionType: 'CREATE_CATEGORY',
      payload: { name: 'Therapy', icon: 'heart', color: 'violet' },
    });
    await user.click(screen.getByRole('button', { name: /^Create category/ }));

    expect(screen.getByRole('textbox', { name: 'Icon' })).toHaveValue('heart');
    expect(screen.getByRole('textbox', { name: 'Color' })).toHaveValue('violet');
  });
});
