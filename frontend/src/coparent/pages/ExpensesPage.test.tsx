import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { testExpense } from '../components/expenses/testExpense';
import { ToastProvider } from '../components/ui/ToastProvider';
import * as apiHooks from '../hooks/api';
import type { ExpenseSummary } from '../types/expenses';

import ExpensesPage from './ExpensesPage';

vi.mock('../hooks/api', () => ({
  useFamilies: vi.fn(),
  useParents: vi.fn(),
  useChildren: vi.fn(),
  useCurrentParentId: vi.fn(),
  useExpenses: vi.fn(),
  useExpenseSummary: vi.fn(),
  useExpenseTransition: vi.fn(),
  useCreateExpense: vi.fn(),
  useUpdateExpense: vi.fn(),
  useUploadReceipt: vi.fn(),
  useDeleteExpense: vi.fn(),
  useDeleteReceipt: vi.fn(),
  useMarkExpensePaid: vi.fn(),
  useSettleExpenses: vi.fn(),
  fetchReceiptBlob: vi.fn(),
}));

const mutation = (mutateAsync = vi.fn().mockResolvedValue({})) =>
  ({ mutateAsync, isPending: false }) as unknown as ReturnType<typeof apiHooks.useCreateExpense>;

const transitionMutate = vi.fn();
const createMutate = vi.fn();

const alex = { id: 'alex', familyId: 'fam-1', fullName: 'Alex Rowe', role: 'primary' as const, status: 'active' };
const sam = { id: 'sam', familyId: 'fam-1', fullName: 'Sam Taylor', role: 'co-parent' as const, status: 'active' };

const summary: ExpenseSummary = {
  currency: 'GBP',
  balance: {
    netPence: 4245,
    debtorParentId: 'sam',
    creditorParentId: 'alex',
    expenseCount: 3,
    awaitingYourConfirmationPence: 4000,
    awaitingTheirConfirmationPence: 0,
  },
  needsYourAgreement: { count: 1, totalPence: 24000 },
  needsYourAction: 1,
  awaitingOther: 0,
  upcoming: { count: 1, totalPence: 24000, yourSharePence: 12000, next: [] },
  thisMonth: { totalPence: 0, yourSharePence: 0, byCategory: [] },
};

const skiTrip = testExpense({
  id: 'ski',
  title: 'Ski trip deposit',
  category: 'activities',
  amountPence: 24000,
  timing: 'upcoming',
  date: '2026-10-20',
  payerId: 'sam',
  shares: [
    { parentId: 'alex', percent: 50, sharePence: 12000 },
    { parentId: 'sam', percent: 50, sharePence: 12000 },
  ],
  agreement: { status: 'pending', requestedBy: 'sam', respondedBy: null, respondedAt: null, note: null },
});

function setUp({ parents = [alex, sam], expenses = [skiTrip] } = {}) {
  vi.mocked(apiHooks.useFamilies).mockReturnValue({
    data: [{ id: 'fam-1', name: 'Family', timeZone: 'Europe/London' }],
    isLoading: false,
  } as unknown as ReturnType<typeof apiHooks.useFamilies>);
  vi.mocked(apiHooks.useCurrentParentId).mockReturnValue('alex');
  vi.mocked(apiHooks.useParents).mockReturnValue({ data: parents } as unknown as ReturnType<typeof apiHooks.useParents>);
  vi.mocked(apiHooks.useChildren).mockReturnValue({
    data: [{ id: 'child-1', familyId: 'fam-1', fullName: 'Mia Rowe', dateOfBirth: '2016-01-01' }],
  } as unknown as ReturnType<typeof apiHooks.useChildren>);
  vi.mocked(apiHooks.useExpenses).mockReturnValue({ data: expenses, isLoading: false } as unknown as ReturnType<
    typeof apiHooks.useExpenses
  >);
  vi.mocked(apiHooks.useExpenseSummary).mockReturnValue({ data: summary } as unknown as ReturnType<
    typeof apiHooks.useExpenseSummary
  >);
  vi.mocked(apiHooks.useExpenseTransition).mockReturnValue(
    mutation(transitionMutate) as unknown as ReturnType<typeof apiHooks.useExpenseTransition>,
  );
  vi.mocked(apiHooks.useCreateExpense).mockReturnValue(mutation(createMutate));
  for (const hook of [
    apiHooks.useUpdateExpense,
    apiHooks.useUploadReceipt,
    apiHooks.useDeleteExpense,
    apiHooks.useDeleteReceipt,
    apiHooks.useMarkExpensePaid,
    apiHooks.useSettleExpenses,
  ]) {
    vi.mocked(hook).mockReturnValue(mutation() as never);
  }
}

function renderPage(path = '/expenses') {
  return render(
    <ToastProvider>
      <MemoryRouter initialEntries={[path]}>
        <ExpensesPage />
      </MemoryRouter>
    </ToastProvider>,
  );
}

describe('ExpensesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    transitionMutate.mockResolvedValue({});
    createMutate.mockResolvedValue(testExpense({ id: 'new' }));
  });

  it('shows the balance and opens on what needs the signed-in parent', () => {
    setUp();
    renderPage();

    expect(screen.getByText('Sam owes you')).toBeInTheDocument();
    expect(screen.getByText('£42.45')).toBeInTheDocument();
    expect(screen.getByText('£40.00 waiting for you to confirm')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Needs action/ })).toHaveAttribute('aria-pressed', 'true');
    const row = screen.getByTestId('expense-row-ski');
    expect(within(row).getByText('Needs your OK')).toBeInTheDocument();
    expect(within(row).getByText('Sam will pay')).toBeInTheDocument();
    expect(within(row).getByText('Your share £120.00')).toBeInTheDocument();
  });

  it('agrees from the row with the version the parent was looking at', async () => {
    setUp();
    renderPage();

    await userEvent.click(within(screen.getByTestId('expense-row-ski')).getByRole('button', { name: /Agree/ }));

    expect(transitionMutate).toHaveBeenCalledWith(
      expect.objectContaining({ familyId: 'fam-1', transition: 'agree', expense: expect.objectContaining({ id: 'ski', version: 1 }) }),
    );
  });

  it('logs an expense the other parent paid, saying in pounds who will owe what', async () => {
    setUp();
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /Add expense/ }));
    await user.type(screen.getByPlaceholderText('e.g. School shoes'), 'Hay fever medicine');
    await user.type(screen.getByLabelText('Amount in pounds'), '12.80');
    await user.click(screen.getByRole('button', { name: 'Sam' }));

    const summaryBox = screen.getByTestId('expense-summary');
    expect(summaryBox).toHaveTextContent('Sam paid £12.80. Your share is £6.40.');
    expect(summaryBox).toHaveTextContent("You'll owe Sam £6.40 once Sam agrees.");
    expect(summaryBox).not.toHaveTextContent('$');

    await user.click(screen.getByRole('button', { name: /^Add expense$/ }));
    expect(createMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        familyId: 'fam-1',
        title: 'Hay fever medicine',
        amountPence: 1280,
        currency: 'GBP',
        timing: 'paid',
        payerId: 'sam',
        childIds: ['child-1'],
        shares: [
          { parentId: 'alex', percent: 50 },
          { parentId: 'sam', percent: 50 },
        ],
      }),
    );
  });

  it('keeps save disabled until the expense says what, how much, for whom and who paid', async () => {
    setUp();
    const user = userEvent.setup();
    renderPage('/expenses?new=1');

    const save = screen.getByRole('button', { name: /^Add expense$/ });
    expect(save).toBeDisabled();
    await user.type(screen.getByPlaceholderText('e.g. School shoes'), 'Coat');
    await user.type(screen.getByLabelText('Amount in pounds'), '0');
    expect(save).toBeDisabled();
    await user.clear(screen.getByLabelText('Amount in pounds'));
    await user.type(screen.getByLabelText('Amount in pounds'), '39');
    expect(save).toBeEnabled();
  });

  it('asks for the co-parent before any cost can be shared', () => {
    setUp({ parents: [alex], expenses: [] });
    renderPage();

    expect(screen.getByText('Invite your co-parent to start sharing costs.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add expense/ })).not.toBeInTheDocument();
  });
});
