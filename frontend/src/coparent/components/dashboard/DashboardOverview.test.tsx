import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import type { DashboardProps } from '../../types/dashboard';

import { DashboardOverview } from './DashboardOverview';

const baseProps: DashboardProps = {
  family: {
    id: 'fam-1',
    name: 'Test Family',
    timezone: 'America/Los_Angeles',
    primaryParentId: 'par-1',
    secondaryParentId: 'par-2',
    childIds: [],
    createdAt: '2026-02-01T00:00:00Z',
    setupProgress: 0,
  },
  parents: [
    {
      id: 'par-1',
      fullName: 'Alex Rowe',
      email: 'alex@example.com',
      role: 'primary',
      phone: '',
      avatarUrl: null,
      lastActiveAt: '2026-02-01T00:00:00Z',
      notificationPreferences: { email: true, sms: false, push: true },
    },
  ],
  children: [],
  upcomingEvents: [],
  permissionRequests: [],
  expenses: [],
  messages: [],
  invitations: [],
  activityFeed: [],
  budgetSummary: {
    month: 'February 2026',
    currency: 'GBP',
    totalLimit: 0,
    totalSpent: 0,
    remaining: 0,
    categories: [],
  },
  approvalsSummary: {
    totalPending: 0,
    byType: { expenses: 0, scheduleChanges: 0, permissions: 0 },
  },
  setupChecklist: {
    completedCount: 0,
    totalCount: 2,
    items: [
      { id: 's1', label: 'Add children', completed: false },
      { id: 's2', label: 'Invite co-parent', completed: false },
    ],
  },
  widgetCards: [
    {
      id: 'w1',
      title: 'Upcoming Events',
      value: '0',
      description: 'Next 14 days',
      trend: 'flat',
      delta: '0',
      size: 'sm',
      sectionId: 'calendar',
    },
  ],
  quickActions: [
    { id: 'add-expense', label: 'Add Expense', helper: 'Upload a receipt', shortcut: 'E' },
    {
      id: 'create-event',
      label: 'Create Event',
      helper: 'Schedule custody or activity',
      shortcut: 'C',
    },
    { id: 'send-message', label: 'Send Message', helper: 'Start a new thread', shortcut: 'M' },
  ],
};

describe('DashboardOverview', () => {
  it('renders key empty states', () => {
    render(<DashboardOverview {...baseProps} />);

    expect(screen.getByText('No events scheduled.')).toBeInTheDocument();
    expect(screen.getByText('No approvals waiting.')).toBeInTheDocument();
    expect(screen.getByText('No unread threads.')).toBeInTheDocument();
  });

  it('invokes drawer callbacks', async () => {
    const user = userEvent.setup();
    const onOpenProfileDrawer = vi.fn();
    const onOpenChildrenDrawer = vi.fn();
    const onOpenInvitationsDrawer = vi.fn();

    render(
      <DashboardOverview
        {...baseProps}
        onOpenProfileDrawer={onOpenProfileDrawer}
        onOpenChildrenDrawer={onOpenChildrenDrawer}
        onOpenInvitationsDrawer={onOpenInvitationsDrawer}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Profile' }));
    await user.click(screen.getByRole('button', { name: 'Children' }));
    await user.click(screen.getByRole('button', { name: 'Invitations' }));

    expect(onOpenProfileDrawer).toHaveBeenCalledTimes(1);
    expect(onOpenChildrenDrawer).toHaveBeenCalledTimes(1);
    expect(onOpenInvitationsDrawer).toHaveBeenCalledTimes(1);
  });
});
