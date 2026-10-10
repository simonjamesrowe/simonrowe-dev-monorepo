import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { testExpense } from '../components/expenses/testExpense';
import { ToastProvider } from '../components/ui/ToastProvider';
import * as apiHooks from '../hooks/api';
import type { ExpenseSummary } from '../types/expenses';

import DashboardPage from './DashboardPage';

vi.mock('../hooks/api', () => ({
  useChildren: vi.fn(),
  useConversations: vi.fn(),
  useCurrentParentId: vi.fn(),
  useCurrentUser: vi.fn(),
  useEvents: vi.fn(),
  useExpenseSummary: vi.fn(),
  useExpenseTransition: vi.fn(),
  useExpenses: vi.fn(),
  useFamilies: vi.fn(),
  useInvitations: vi.fn(),
  useParents: vi.fn(),
  useParentsWithInvited: vi.fn(),
  useResendInvitation: vi.fn(),
  useScheduleChangeRequests: vi.fn(),
  useUpdateCurrentUser: vi.fn(),
}));

const query = <T,>(data: T) => ({ data, isLoading: false }) as never;
const mutation = (mutateAsync = vi.fn().mockResolvedValue({})) => ({ mutateAsync, isPending: false }) as never;

const simon = { id: 'simon', familyId: 'fam', fullName: 'Simon Rowe', role: 'primary' as const, status: 'active' };
const rhian = {
  id: 'rhian',
  familyId: 'fam',
  fullName: 'Rhian Raftopoulos',
  email: 'rhian@example.com',
  role: 'co-parent' as const,
  status: 'invited',
};

const summary = (overrides: Partial<ExpenseSummary> = {}): ExpenseSummary => ({
  currency: 'GBP',
  balance: {
    netPence: 0,
    debtorParentId: null,
    creditorParentId: null,
    expenseCount: 0,
    awaitingYourConfirmationPence: 0,
    awaitingTheirConfirmationPence: 0,
  },
  needsYourAgreement: { count: 0, totalPence: 0 },
  needsYourAction: 0,
  awaitingOther: 0,
  upcoming: { count: 0, totalPence: 0, yourSharePence: 0, next: [] },
  thisMonth: { totalPence: 0, yourSharePence: 0, byCategory: [] },
  ...overrides,
});

const transition = vi.fn().mockResolvedValue({});
const resend = vi.fn().mockResolvedValue({});

const renderPage = () =>
  render(
    <ToastProvider>
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </ToastProvider>,
  );

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 10, 15, 45));
    vi.mocked(apiHooks.useFamilies).mockReturnValue(
      query([{ id: 'fam', name: 'Rowe', timeZone: 'Europe/London', parentIds: [], childIds: [], invitationIds: [], createdAt: '2026-09-20T10:00:00Z' }]),
    );
    vi.mocked(apiHooks.useCurrentUser).mockReturnValue(query({ auth0Id: 'a', email: 's@example.com', profiles: [], isNewUser: false }));
    vi.mocked(apiHooks.useCurrentParentId).mockReturnValue('simon');
    vi.mocked(apiHooks.useParents).mockReturnValue(query([simon]));
    vi.mocked(apiHooks.useParentsWithInvited).mockReturnValue(query([simon, rhian]));
    vi.mocked(apiHooks.useChildren).mockReturnValue(
      query([
        { id: 'ethan', familyId: 'fam', fullName: 'Ethan Rowe', dateOfBirth: '2016-05-01' },
        { id: 'ava', familyId: 'fam', fullName: 'Ava Rowe', dateOfBirth: '2019-03-01' },
      ]),
    );
    vi.mocked(apiHooks.useInvitations).mockReturnValue(
      query([
        { id: 'inv', familyId: 'fam', email: 'Rhian@example.com', role: 'co-parent', status: 'pending', sentAt: '2026-10-08T19:12:00Z', expiresAt: '2026-10-22T19:12:00Z' },
      ]),
    );
    vi.mocked(apiHooks.useConversations).mockReturnValue(query([]));
    vi.mocked(apiHooks.useEvents).mockReturnValue(query([]));
    vi.mocked(apiHooks.useScheduleChangeRequests).mockReturnValue(query([]));
    vi.mocked(apiHooks.useExpenses).mockReturnValue(query([]));
    vi.mocked(apiHooks.useExpenseSummary).mockReturnValue(query(summary()));
    vi.mocked(apiHooks.useExpenseTransition).mockReturnValue(mutation(transition));
    vi.mocked(apiHooks.useResendInvitation).mockReturnValue(mutation(resend));
    vi.mocked(apiHooks.useUpdateCurrentUser).mockReturnValue(mutation());
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('asks the parent to chase an invitation that is still open, by the invitee name', async () => {
    const user = userEvent.setup();
    renderPage();

    const needs = screen.getByRole('region', { name: 'Needs you' });
    expect(needs).toHaveTextContent("Rhian hasn't joined yet");
    expect(needs).toHaveTextContent('Invitation sent Thursday 8 October.');
    await user.click(within(needs).getByRole('button', { name: 'Resend' }));
    expect(resend).toHaveBeenCalledWith({ id: 'inv', familyId: 'fam' });
  });

  it('puts an expense the other parent sent first, agreeable in one press, without counting it twice', async () => {
    const user = userEvent.setup();
    const deposit = testExpense({
      id: 'ski',
      title: 'Ski trip deposit',
      amountPence: 24000,
      shares: [
        { parentId: 'simon', percent: 50, sharePence: 12000 },
        { parentId: 'rhian', percent: 50, sharePence: 12000 },
      ],
      agreement: { status: 'pending', requestedBy: 'rhian', respondedBy: null, respondedAt: null, note: null },
    });
    vi.mocked(apiHooks.useExpenses).mockReturnValue(query([deposit]));
    vi.mocked(apiHooks.useExpenseSummary).mockReturnValue(query(summary({ needsYourAction: 1 })));
    renderPage();

    const needs = screen.getByRole('region', { name: 'Needs you' });
    const items = needs.querySelectorAll('.cp-brief__ask');
    expect(items[0]).toHaveTextContent('Ski trip deposit · £240.00');
    expect(items[0]).toHaveTextContent('Your share is £120.00.');
    expect(needs).not.toHaveTextContent('waiting on you');
    await user.click(within(items[0] as HTMLElement).getByRole('button', { name: 'Agree' }));
    expect(transition).toHaveBeenCalledWith({ familyId: 'fam', expense: deposit, transition: 'agree' });
  });

  it('lists a schedule change the other parent asked for and an unread message', () => {
    vi.mocked(apiHooks.useParentsWithInvited).mockReturnValue(query([simon, { ...rhian, status: 'active' }]));
    vi.mocked(apiHooks.useInvitations).mockReturnValue(query([]));
    vi.mocked(apiHooks.useScheduleChangeRequests).mockReturnValue(
      query([
        { id: 'req', status: 'pending', requestedBy: 'rhian', reason: 'Work trip that weekend', proposedChange: {} },
        { id: 'mine', status: 'pending', requestedBy: 'simon', reason: 'My own request', proposedChange: {} },
      ]),
    );
    vi.mocked(apiHooks.useConversations).mockReturnValue(
      query([
        {
          id: 'conv',
          type: 'message',
          subject: 'Half term',
          lastMessageAt: '2026-10-10T10:00:00Z',
          unreadCount: 1,
          participants: {},
          messages: [{ id: 'm', senderId: 'rhian', content: 'Can you take them on the Tuesday?', timestamp: '', isRead: false, deliveryStatus: 'delivered' }],
        },
      ]),
    );
    renderPage();

    const needs = screen.getByRole('region', { name: 'Needs you' });
    expect(needs).toHaveTextContent('Rhian asked to change the schedule');
    expect(needs).toHaveTextContent('Work trip that weekend');
    expect(needs).not.toHaveTextContent('My own request');
    expect(needs).toHaveTextContent('Half term');
    expect(needs).toHaveTextContent('Can you take them on the Tuesday?');
  });

  it('says who owes whom in the money line', () => {
    vi.mocked(apiHooks.useExpenseSummary).mockReturnValue(
      query(
        summary({
          balance: {
            netPence: 4245,
            debtorParentId: 'rhian',
            creditorParentId: 'simon',
            expenseCount: 2,
            awaitingYourConfirmationPence: 0,
            awaitingTheirConfirmationPence: 0,
          },
        }),
      ),
    );
    renderPage();

    const money = screen.getByRole('region', { name: 'Shared expenses' });
    expect(money).toHaveTextContent('Rhian owes you £42.45');
    expect(within(money).getByRole('button', { name: 'Settle up' })).toBeInTheDocument();
  });

  it('says the children are with this parent when a custody day says so', () => {
    vi.mocked(apiHooks.useEvents).mockReturnValue(
      query([
        {
          id: 'weekend',
          type: 'custody',
          title: 'With Simon',
          startDate: '2026-10-10T00:00:00Z',
          endDate: '2026-10-11T00:00:00Z',
          allDay: true,
          parentId: 'simon',
          childIds: ['ethan', 'ava'],
          notes: null,
          recurring: null,
        },
      ]),
    );
    renderPage();

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Ethan and Ava are with you.');
  });
});
