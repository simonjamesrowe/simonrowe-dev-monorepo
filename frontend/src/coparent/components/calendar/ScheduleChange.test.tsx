import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { Event, Parent, ScheduleChangeRequest } from '../../types/calendar';

import { ScheduleChangeApproval } from './ScheduleChangeApproval';
import { ScheduleChangeRequestModal } from './ScheduleChangeRequestModal';

const parents: Parent[] = [
  { id: 'alice', name: 'Alice', fullName: 'Alice Rowe', email: '', color: 'violet', avatarUrl: null },
  { id: 'bob', name: 'Bob', fullName: 'Bob Rowe', email: '', color: 'sky', avatarUrl: null },
];

const swimming: Event = {
  id: 'swimming',
  type: 'activity',
  title: 'Swimming',
  startDate: '2026-09-28',
  startTime: '18:30',
  endTime: '19:00',
  allDay: false,
  parentId: null,
  childIds: ['ava'],
  notes: null,
  recurring: { frequency: 'weekly', days: ['monday'] },
};

const request: ScheduleChangeRequest = {
  id: 'request-1',
  status: 'pending',
  requestedBy: 'alice',
  requestedAt: '2026-09-26T10:00:00Z',
  resolvedBy: null,
  resolvedAt: null,
  originalEventId: 'swimming',
  proposedChange: {
    type: 'swap',
    originalStartDate: '2026-10-26',
    originalEndDate: '2026-10-26',
    newStartDate: '2026-10-27',
    newEndDate: '2026-10-27',
  },
  reason: 'Half term trip on the Monday',
  responseNote: null,
};

const approval = (currentParentId: string, extra = {}) =>
  render(
    <ScheduleChangeApproval
      parents={parents}
      children={[]}
      events={[swimming]}
      scheduleChangeRequests={[request]}
      currentParentId={currentParentId}
      {...extra}
    />,
  );

describe('ScheduleChangeApproval', () => {
  it('tells the approver exactly what approving will do to the calendar', async () => {
    const user = userEvent.setup();
    const onApproveRequest = vi.fn().mockResolvedValue(undefined);
    approval('bob', { onApproveRequest });

    expect(
      screen.getByText(/Approving moves "Swimming" on .* to .*; other weeks are unchanged\./),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Approve change' }));
    expect(onApproveRequest).toHaveBeenCalledWith('request-1', undefined);
  });

  it('shows why an approval could not be applied', async () => {
    const user = userEvent.setup();
    const onApproveRequest = vi.fn().mockRejectedValue({
      response: {
        data: {
          message:
            'The change could not be applied, so the request is still pending: the event it changes no longer exists',
        },
      },
    });
    approval('bob', { onApproveRequest });

    await user.click(screen.getByRole('button', { name: 'Approve change' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('still pending');
  });

  it('lets the requester withdraw instead of answering their own request', async () => {
    const user = userEvent.setup();
    const onWithdrawRequest = vi.fn().mockResolvedValue(undefined);
    approval('alice', { onWithdrawRequest });

    expect(screen.queryByRole('button', { name: 'Approve change' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Withdraw request' }));
    expect(onWithdrawRequest).toHaveBeenCalledWith('request-1');
  });
});

describe('ScheduleChangeRequestModal', () => {
  const open = (onSubmit: (data: unknown) => Promise<void>, onClose = vi.fn()) =>
    render(
      <ScheduleChangeRequestModal
        isOpen
        originalEvent={{ ...swimming, startDate: '2026-10-26', endDate: '2026-10-26' }}
        parents={{ alice: parents[0], bob: parents[1] }}
        currentParentId="alice"
        onClose={onClose}
        onSubmit={onSubmit}
      />,
    );

  it('asks to cancel one occurrence with its own dates and closes once sent', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();
    open(onSubmit, onClose);

    await user.click(screen.getByRole('button', { name: 'Cancel date' }));
    expect(screen.queryByLabelText('Start Date')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText(/Reason/), 'Pool is closed that week');
    await user.click(screen.getByRole('button', { name: /Send Request/ }));

    expect(onSubmit).toHaveBeenCalledWith({
      proposedChange: {
        type: 'remove',
        originalStartDate: '2026-10-26',
        originalEndDate: '2026-10-26',
        newStartDate: '2026-10-26',
        newEndDate: '2026-10-26',
      },
      reason: 'Pool is closed that week',
    });
    expect(onClose).toHaveBeenCalled();
  });

  it('keeps the form open and says why when sending fails', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    open(
      vi.fn().mockRejectedValue({
        response: { data: { message: 'Choose which occurrence of the repeating event' } },
      }),
      onClose,
    );

    await user.type(screen.getByLabelText(/Reason/), 'Swap for the trip please');
    await user.click(screen.getByRole('button', { name: /Send Request/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Choose which occurrence');
    expect(onClose).not.toHaveBeenCalled();
  });
});
