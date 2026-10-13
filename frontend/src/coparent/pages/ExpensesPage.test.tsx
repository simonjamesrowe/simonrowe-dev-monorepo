import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { testExpense } from '../components/expenses/testExpense';
import { ToastProvider } from '../components/ui/ToastProvider';
import * as apiHooks from '../hooks/api';
import type { ExpenseSettlement, ExpenseSummary } from '../types/expenses';

import ExpensesPage from './ExpensesPage';

vi.mock('../hooks/api', () => ({
  useFamilies: vi.fn(),
  useParentsWithInvited: vi.fn(),
  useInvitations: vi.fn(),
  useResendInvitation: vi.fn(),
  useRenameInvitedParent: vi.fn(),
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
  useSettlements: vi.fn(),
  useRecordSettlement: vi.fn(),
  useConfirmSettlement: vi.fn(),
  useUpdateSettlement: vi.fn(),
  useMarkPaidBack: vi.fn(),
  fetchReceiptBlob: vi.fn(),
  useStatementOverview: vi.fn(),
}));

const mutation = (mutateAsync = vi.fn().mockResolvedValue({})) =>
  ({ mutateAsync, isPending: false }) as unknown as ReturnType<typeof apiHooks.useCreateExpense>;

const transitionMutate = vi.fn();
const recordMutate = vi.fn();
const confirmMutate = vi.fn();
const updateSettlementMutate = vi.fn();
const markPaidBackMutate = vi.fn();
const createMutate = vi.fn();
const resendMutate = vi.fn();
const renameMutate = vi.fn();

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
    carriedPence: 0,
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

type TestParent = { id: string; familyId: string; fullName: string; role: 'primary' | 'co-parent'; status: string; email?: string };

let statementOverview = { aiEnabled: true, toReview: 0, dismissed: 0, logged: 0, uploads: [] };

function setUp({
  parents = [alex, sam] as TestParent[],
  expenses = [skiTrip],
  invitations = [] as unknown[],
  settlements = [] as ExpenseSettlement[],
} = {}) {
  vi.mocked(apiHooks.useFamilies).mockReturnValue({
    data: [{ id: 'fam-1', name: 'Family', timeZone: 'Europe/London' }],
    isLoading: false,
  } as unknown as ReturnType<typeof apiHooks.useFamilies>);
  vi.mocked(apiHooks.useCurrentParentId).mockReturnValue('alex');
  vi.mocked(apiHooks.useStatementOverview).mockReturnValue({ data: statementOverview } as unknown as ReturnType<
    typeof apiHooks.useStatementOverview
  >);
  vi.mocked(apiHooks.useParentsWithInvited).mockReturnValue({ data: parents } as unknown as ReturnType<
    typeof apiHooks.useParentsWithInvited
  >);
  vi.mocked(apiHooks.useInvitations).mockReturnValue({ data: invitations } as unknown as ReturnType<
    typeof apiHooks.useInvitations
  >);
  vi.mocked(apiHooks.useResendInvitation).mockReturnValue(mutation(resendMutate) as never);
  vi.mocked(apiHooks.useRenameInvitedParent).mockReturnValue(mutation(renameMutate) as never);
  vi.mocked(apiHooks.useChildren).mockReturnValue({
    data: [{ id: 'child-1', familyId: 'fam-1', fullName: 'Mia Rowe', dateOfBirth: '2016-01-01' }],
  } as unknown as ReturnType<typeof apiHooks.useChildren>);
  vi.mocked(apiHooks.useExpenses).mockReturnValue({ data: expenses, isLoading: false } as unknown as ReturnType<
    typeof apiHooks.useExpenses
  >);
  vi.mocked(apiHooks.useExpenseSummary).mockReturnValue({ data: summary } as unknown as ReturnType<
    typeof apiHooks.useExpenseSummary
  >);
  vi.mocked(apiHooks.useSettlements).mockReturnValue({ data: settlements } as unknown as ReturnType<
    typeof apiHooks.useSettlements
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
  ]) {
    vi.mocked(hook).mockReturnValue(mutation() as never);
  }
  vi.mocked(apiHooks.useRecordSettlement).mockReturnValue(mutation(recordMutate) as never);
  vi.mocked(apiHooks.useConfirmSettlement).mockReturnValue(mutation(confirmMutate) as never);
  vi.mocked(apiHooks.useUpdateSettlement).mockReturnValue(mutation(updateSettlementMutate) as never);
  vi.mocked(apiHooks.useMarkPaidBack).mockReturnValue(mutation(markPaidBackMutate) as never);
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
    recordMutate.mockResolvedValue({ settlement: null, results: [] });
    confirmMutate.mockResolvedValue([]);
    updateSettlementMutate.mockResolvedValue({ paidPence: 150000 });
    markPaidBackMutate.mockResolvedValue([]);
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

  it('lets a parent log costs for a co-parent who has not joined, and resend or rename them', async () => {
    const rhian = {
      id: 'rhian',
      familyId: 'fam-1',
      fullName: 'Rhian',
      email: 'rhian@example.com',
      role: 'co-parent' as const,
      status: 'invited',
    };
    const alexPrimary = { ...alex, role: 'primary' as const };
    setUp({
      parents: [alexPrimary, rhian],
      expenses: [],
      invitations: [{ id: 'inv-1', familyId: 'fam-1', email: 'Rhian@example.com', role: 'co-parent', status: 'pending' }],
    });
    const user = userEvent.setup();
    renderPage();

    const banner = screen.getByRole('region', { name: 'Co-parent not joined yet' });
    expect(banner).toHaveTextContent("Rhian hasn't joined yet");
    expect(banner).toHaveTextContent('rhian@example.com');
    expect(screen.queryByText('Invite your co-parent to start sharing costs.')).not.toBeInTheDocument();

    await user.click(within(banner).getByRole('button', { name: 'Resend invitation' }));
    expect(resendMutate).toHaveBeenCalledWith({ id: 'inv-1', familyId: 'fam-1' });

    await user.click(within(banner).getByRole('button', { name: /Change name/ }));
    await user.clear(within(banner).getByLabelText('Their name'));
    await user.type(within(banner).getByLabelText('Their name'), 'Rhian Jones');
    await user.click(within(banner).getByRole('button', { name: 'Save name' }));
    expect(renameMutate).toHaveBeenCalledWith({ id: 'rhian', familyId: 'fam-1', fullName: 'Rhian Jones' });

    await user.click(screen.getByRole('button', { name: /Add expense/ }));
    expect(screen.getByText(/Rhian hasn't joined yet, so nothing is sent now/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Amount in pounds'), '20');
    await user.click(screen.getByRole('button', { name: 'Rhian' }));
    expect(screen.getByTestId('expense-summary')).toHaveTextContent('Rhian paid £20.00');
  });

  it('only offers resend and rename to the primary parent', () => {
    setUp({
      parents: [{ ...alex, role: 'co-parent' as const }, { ...sam, status: 'invited', email: 'sam@example.com' }],
      expenses: [],
    });
    renderPage();

    const banner = screen.getByRole('region', { name: 'Co-parent not joined yet' });
    expect(within(banner).queryByRole('button')).not.toBeInTheDocument();
  });

  it('asks for the co-parent before any cost can be shared', () => {
    setUp({ parents: [alex], expenses: [] });
    renderPage();

    expect(screen.getByText('Invite your co-parent to start sharing costs.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add expense/ })).not.toBeInTheDocument();
  });

  it('points to statement suggestions only when some are waiting', () => {
    setUp();
    renderPage();
    expect(screen.getByRole('button', { name: /Import statements/ })).toBeInTheDocument();
    expect(screen.queryByTestId('statement-banner')).not.toBeInTheDocument();

    statementOverview = { ...statementOverview, toReview: 3 };
    setUp();
    renderPage();
    expect(screen.getByTestId('statement-banner')).toHaveTextContent(
      '3 transactions from your statements look like shared costs',
    );
    expect(screen.getByRole('button', { name: 'Review 3' })).toBeInTheDocument();
    statementOverview = { ...statementOverview, toReview: 0 };
  });

  it('lets the parent here agree to their own expenses until the co-parent joins', async () => {
    const rhian = { id: 'rhian', familyId: 'fam-1', fullName: 'Rhian', email: 'rhian@example.com', role: 'co-parent' as const, status: 'invited' };
    const mine = testExpense({ id: 'mine', shares: [
      { parentId: 'alex', percent: 50, sharePence: 2250 },
      { parentId: 'rhian', percent: 50, sharePence: 2250 },
    ] });
    const theirs = testExpense({ id: 'theirs', payerId: 'rhian', debtorParentId: 'alex', creditorParentId: 'rhian' });
    setUp({ parents: [alex, rhian], expenses: [mine, theirs] });
    const user = userEvent.setup();
    renderPage();

    const row = screen.getByTestId('expense-row-mine');
    expect(within(row).getByText('Needs your OK')).toBeInTheDocument();
    expect(within(row).queryByText('Waiting for Rhian')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Needs action/ })).toHaveTextContent('2');

    const banner = screen.getByRole('region', { name: 'Co-parent not joined yet' });
    expect(banner).toHaveTextContent('you agree to every expense yourself');
    await user.click(within(banner).getByRole('button', { name: /Agree all 2/ }));
    expect(transitionMutate).toHaveBeenCalledTimes(2);
    expect(transitionMutate).toHaveBeenCalledWith(
      expect.objectContaining({ transition: 'agree', expense: expect.objectContaining({ id: 'mine', version: 1 }) }),
    );
    expect(transitionMutate).toHaveBeenCalledWith(
      expect.objectContaining({ transition: 'agree', expense: expect.objectContaining({ id: 'theirs' }) }),
    );
  });

  it('settles up as two bank transfers, saying what is still owed until they add up', async () => {
    const owedToSam = testExpense({
      id: 'owed',
      title: 'Swimming lessons',
      payerId: 'sam',
      debtorParentId: 'alex',
      creditorParentId: 'sam',
      agreement: { status: 'agreed', requestedBy: 'sam', respondedBy: 'alex', respondedAt: null, note: null },
      reimbursement: { status: 'outstanding', claimedBy: null, claimedAt: null, settledBy: null, settledAt: null, note: null, settlementId: null },
      countsTowardsBalance: true,
      owedPence: 3000,
      version: 2,
    });
    setUp({ expenses: [owedToSam] });
    const user = userEvent.setup();
    renderPage('/expenses?settle=1');

    expect(screen.getByText('How you paid Sam')).toBeInTheDocument();
    expect(screen.getByLabelText('Payment 1 amount in pounds')).toHaveValue('30.00');
    const record = screen.getByRole('button', { name: /Record payment/ });
    expect(record).toBeEnabled();

    await user.clear(screen.getByLabelText('Payment 1 amount in pounds'));
    await user.type(screen.getByLabelText('Payment 1 amount in pounds'), '20');
    expect(screen.getByTestId('settle-payments-difference')).toHaveTextContent(
      '£10.00 less than the expenses came to, so you still owe Sam £10.00.',
    );
    expect(record).toBeEnabled();

    await user.click(screen.getByRole('button', { name: /Add another payment/ }));
    expect(screen.getByLabelText('Payment 2 amount in pounds')).toHaveValue('10.00');
    await user.type(screen.getByLabelText('Payment 2 reference'), 'SWIM');
    expect(screen.getByTestId('settle-payments-total')).toHaveTextContent('£30.00 sent · expenses £30.00');
    expect(screen.queryByTestId('settle-payments-difference')).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText('Payment 2 amount in pounds'));
    expect(screen.getByText('Enter an amount for each payment.')).toBeInTheDocument();
    expect(record).toBeDisabled();
    await user.type(screen.getByLabelText('Payment 2 amount in pounds'), '10');
    await user.click(record);

    expect(recordMutate).toHaveBeenCalledWith({
      familyId: 'fam-1',
      items: [expect.objectContaining({ id: 'owed', version: 2 })],
      payments: [
        expect.objectContaining({ method: 'bank_transfer', amountPence: 2000, reference: null }),
        expect.objectContaining({ method: 'bank_transfer', amountPence: 1000, reference: 'SWIM' }),
      ],
    });
  });

  it('records a round amount sent, saying the overpayment is owed back', async () => {
    const owedToSam = testExpense({
      id: 'owed',
      title: 'Swimming lessons',
      payerId: 'sam',
      debtorParentId: 'alex',
      creditorParentId: 'sam',
      agreement: { status: 'agreed', requestedBy: 'sam', respondedBy: 'alex', respondedAt: null, note: null },
      reimbursement: { status: 'outstanding', claimedBy: null, claimedAt: null, settledBy: null, settledAt: null, note: null, settlementId: null },
      countsTowardsBalance: true,
      owedPence: 147868,
      version: 2,
    });
    setUp({ expenses: [owedToSam] });
    const user = userEvent.setup();
    renderPage('/expenses?settle=1');

    await user.clear(screen.getByLabelText('Payment 1 amount in pounds'));
    await user.type(screen.getByLabelText('Payment 1 amount in pounds'), '1500');
    await user.clear(screen.getByLabelText('Payment 1 date'));
    await user.type(screen.getByLabelText('Payment 1 date'), '2026-10-08');
    expect(screen.getByTestId('settle-payments-difference')).toHaveTextContent(
      '£21.32 more than the expenses came to, so Sam owes you £21.32 back.',
    );
    await user.click(screen.getByRole('button', { name: /Record payment/ }));

    expect(recordMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        payments: [expect.objectContaining({ amountPence: 150000, paidOn: '2026-10-08' })],
      }),
    );
  });

  it('lets the parent who recorded a transfer correct its amount and date', async () => {
    const paidBack = (id: string, title: string, date: string, owedPence: number) =>
      testExpense({
        id,
        title,
        date,
        payerId: 'sam',
        debtorParentId: 'alex',
        creditorParentId: 'sam',
        agreement: { status: 'agreed', requestedBy: 'sam', respondedBy: 'alex', respondedAt: null, note: null },
        reimbursement: { status: 'reimbursed', claimedBy: 'alex', claimedAt: null, settledBy: 'alex', settledAt: null, note: 'Bank transfer', settlementId: 'set-1' },
        countsTowardsBalance: false,
        owedPence,
      });
    const settlement: ExpenseSettlement = {
      id: 'set-1',
      familyId: 'fam-1',
      fromParentId: 'alex',
      toParentId: 'sam',
      amountPence: 147868,
      paidPence: 147868,
      currency: 'GBP',
      payments: [{ method: 'bank_transfer', amountPence: 147868, paidOn: '2026-10-13', reference: null }],
      expenseIds: ['roof', 'party'],
      recordedBy: 'alex',
      createdAt: '2026-10-13T10:00:00Z',
      updatedBy: null,
      updatedAt: null,
    };
    setUp({
      expenses: [paidBack('roof', 'Roof Repairs', '2026-09-20', 4000), paidBack('party', "Ava's Party", '2026-09-02', 143868)],
      settlements: [settlement],
    });
    const user = userEvent.setup();
    renderPage('/expenses?view=transfers');

    const card = screen.getByTestId('transfer-set-1');
    expect(card).toHaveTextContent('You paid Sam £1,478.68');
    // Each expense carries its date, oldest first.
    const rows = within(card).getAllByRole('button', { name: /Party|Roof/ });
    expect(rows[0]).toHaveTextContent("Ava's Party2 Sept 2026");
    expect(rows[1]).toHaveTextContent('Roof Repairs20 Sept 2026');

    await user.click(within(card).getByRole('button', { name: /Edit transfer/ }));
    const amount = await screen.findByLabelText('Payment 1 amount in pounds');
    expect(amount).toHaveValue('1478.68');
    await user.clear(amount);
    await user.type(amount, '1500');
    await user.clear(screen.getByLabelText('Payment 1 date'));
    await user.type(screen.getByLabelText('Payment 1 date'), '2026-10-08');
    expect(screen.getByTestId('settle-payments-difference')).toHaveTextContent('so Sam owes you £21.32 back');
    await user.click(screen.getByRole('button', { name: /Save transfer/ }));

    expect(updateSettlementMutate).toHaveBeenCalledWith({
      familyId: 'fam-1',
      settlementId: 'set-1',
      payments: [{ method: 'bank_transfer', amountPence: 150000, paidOn: '2026-10-08', reference: null }],
    });
  });

  it('shows what a transfer carried into the balance', () => {
    const settlement: ExpenseSettlement = {
      id: 'set-1',
      familyId: 'fam-1',
      fromParentId: 'alex',
      toParentId: 'sam',
      amountPence: 147868,
      paidPence: 150000,
      currency: 'GBP',
      payments: [{ method: 'bank_transfer', amountPence: 150000, paidOn: '2026-10-08', reference: null }],
      expenseIds: [],
      recordedBy: 'sam',
      createdAt: '2026-10-13T10:00:00Z',
      updatedBy: 'sam',
      updatedAt: '2026-10-14T10:00:00Z',
    };
    setUp({ expenses: [], settlements: [settlement] });
    renderPage('/expenses?view=transfers');

    const card = screen.getByTestId('transfer-set-1');
    expect(card).toHaveTextContent('You paid Sam £1,500.00');
    expect(card).toHaveTextContent('edited by Sam on 14 Oct 2026');
    expect(within(card).getByTestId('transfer-difference')).toHaveTextContent(
      '£21.32 more than the expenses came to, so Sam owes you £21.32 back.',
    );
    // Only the parent who recorded it can edit it.
    expect(within(card).queryByRole('button', { name: /Edit transfer/ })).not.toBeInTheDocument();
  });

  it('shows each expense\'s date and whole bill, and writes an editable message to copy', async () => {
    const owedToSam = testExpense({
      id: 'owed',
      title: 'Swimming lessons',
      date: '2026-10-03',
      amountPence: 6000,
      payerId: 'sam',
      debtorParentId: 'alex',
      creditorParentId: 'sam',
      agreement: { status: 'agreed', requestedBy: 'sam', respondedBy: 'alex', respondedAt: null, note: null },
      reimbursement: { status: 'outstanding', claimedBy: null, claimedAt: null, settledBy: null, settledAt: null, note: null, settlementId: null },
      countsTowardsBalance: true,
      owedPence: 3000,
      shares: [
        { parentId: 'alex', percent: 50, sharePence: 3000 },
        { parentId: 'sam', percent: 50, sharePence: 3000 },
      ],
    });
    setUp({ expenses: [owedToSam] });
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
    renderPage('/expenses?settle=1');

    expect(screen.getByText(/£60\.00 total · you 50%, Sam 50%/)).toBeInTheDocument();
    const message = screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Message for Sam' });
    expect(message.value).toContain('my 50% is £30.00');
    expect(message.value).toContain('In total, I owe you £30.00.');

    await user.type(message, ' Sending now.');
    await user.click(screen.getByRole('button', { name: /Copy message/ }));
    expect(writeText).toHaveBeenCalledWith(expect.stringMatching(/I owe you £30\.00\. Sending now\.$/));

    // Edits stick when the ticks change, until the parent asks for a fresh one.
    await user.click(screen.getByRole('checkbox'));
    expect(message.value).toContain('Sending now.');
    await user.click(screen.getByRole('button', { name: /Rewrite from the ticked expenses/ }));
    expect(screen.queryByRole('textbox', { name: 'Message for Sam' })).not.toBeInTheDocument();
  });

  it('lists transfers with the expenses they paid back, and confirms one that arrived', async () => {
    const claimed = testExpense({
      id: 'claimed',
      title: 'School trip',
      agreement: { status: 'agreed', requestedBy: 'alex', respondedBy: 'sam', respondedAt: null, note: null },
      reimbursement: { status: 'claimed', claimedBy: 'sam', claimedAt: null, settledBy: null, settledAt: null, note: 'Bank transfer', settlementId: 'set-1' },
      countsTowardsBalance: true,
      owedPence: 2250,
    });
    const settlement: ExpenseSettlement = {
      id: 'set-1',
      familyId: 'fam-1',
      fromParentId: 'sam',
      toParentId: 'alex',
      amountPence: 2250,
      paidPence: 2250,
      currency: 'GBP',
      payments: [{ method: 'bank_transfer', amountPence: 2250, paidOn: '2026-10-07', reference: 'TRIP' }],
      expenseIds: ['claimed'],
      recordedBy: 'sam',
      createdAt: '2026-10-07T10:00:00Z',
      updatedBy: null,
      updatedAt: null,
    };
    setUp({ expenses: [claimed], settlements: [settlement] });
    const user = userEvent.setup();
    renderPage('/expenses?view=transfers');

    expect(screen.getByRole('button', { name: /Transfers/ })).toHaveTextContent('1');
    const card = screen.getByTestId('transfer-set-1');
    expect(card).toHaveTextContent('Sam paid you £22.50');
    expect(card).toHaveTextContent('Bank transfer · 7 Oct 2026 · ref TRIP');
    expect(within(card).getByRole('button', { name: /School trip/ })).toBeInTheDocument();

    await user.click(within(card).getByRole('button', { name: /Confirm received/ }));
    expect(confirmMutate).toHaveBeenCalledWith({ familyId: 'fam-1', settlementId: 'set-1' });
  });

  it('filters the list to a from and to date, both inclusive', async () => {
    const on = (id: string, date: string) => testExpense({ id, title: id, date });
    setUp({ expenses: [on('before', '2026-09-30'), on('first', '2026-10-01'), on('last', '2026-10-31'), on('after', '2026-11-01')] });
    const user = userEvent.setup();
    renderPage('/expenses?view=all');

    await user.type(screen.getByLabelText('From date'), '2026-10-01');
    await user.type(screen.getByLabelText('To date'), '2026-10-31');

    expect(screen.getByTestId('expense-row-first')).toBeInTheDocument();
    expect(screen.getByTestId('expense-row-last')).toBeInTheDocument();
    expect(screen.queryByTestId('expense-row-before')).not.toBeInTheDocument();
    expect(screen.queryByTestId('expense-row-after')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Clear dates/ }));
    expect(screen.getByTestId('expense-row-before')).toBeInTheDocument();
    expect(screen.getByTestId('expense-row-after')).toBeInTheDocument();
  });

  it('marks everything up to a date as paid back in one go', async () => {
    const owed = (id: string, date: string, owedPence: number) =>
      testExpense({
        id,
        title: id,
        date,
        payerId: 'sam',
        debtorParentId: 'alex',
        creditorParentId: 'sam',
        agreement: { status: 'agreed', requestedBy: 'alex', respondedBy: 'alex', respondedAt: null, note: null },
        reimbursement: { status: 'outstanding', claimedBy: null, claimedAt: null, settledBy: null, settledAt: null, note: null, settlementId: null },
        countsTowardsBalance: true,
        owedPence,
        version: 2,
      });
    setUp({ expenses: [owed('august', '2026-08-20', 5000), owed('early-sept', '2026-09-08', 2500), owed('october', '2026-10-02', 9900)] });
    const user = userEvent.setup();
    renderPage('/expenses?view=owed');

    await user.type(screen.getByLabelText('To date'), '2026-09-08');
    const bulk = screen.getByRole('region', { name: 'Bulk actions' });
    await user.click(within(bulk).getByLabelText('Select all shown'));
    expect(bulk).toHaveTextContent('2 selected');
    await user.click(within(bulk).getByRole('button', { name: /Mark 2 paid back/ }));

    expect(screen.getByTestId('mark-paid-back-totals')).toHaveTextContent('£75.00 you owe');
    await user.clear(screen.getByLabelText('Note on each expense'));
    expect(screen.getByRole('button', { name: 'Mark 2 as paid back' })).toBeDisabled();
    await user.type(screen.getByLabelText('Note on each expense'), 'Paid before 9 Sept');
    await user.click(screen.getByRole('button', { name: 'Mark 2 as paid back' }));

    expect(markPaidBackMutate).toHaveBeenCalledWith({
      familyId: 'fam-1',
      items: [expect.objectContaining({ id: 'early-sept', version: 2 }), expect.objectContaining({ id: 'august', version: 2 })],
      note: 'Paid before 9 Sept',
    });
  });

  it('agrees to only the ticked expenses it can', async () => {
    const pending = (id: string, requestedBy: string) =>
      testExpense({ id, title: id, agreement: { status: 'pending', requestedBy, respondedBy: null, respondedAt: null, note: null } });
    setUp({ expenses: [pending('theirs', 'sam'), pending('mine', 'alex'), pending('other', 'sam')] });
    const user = userEvent.setup();
    renderPage('/expenses?view=all');

    await user.click(screen.getByLabelText('Select theirs'));
    await user.click(screen.getByLabelText('Select mine'));
    const bulk = screen.getByRole('region', { name: 'Bulk actions' });
    expect(within(bulk).getByRole('button', { name: /Mark 0 paid back/ })).toBeDisabled();
    await user.click(within(bulk).getByRole('button', { name: /Agree 1/ }));

    expect(transitionMutate).toHaveBeenCalledTimes(1);
    expect(transitionMutate).toHaveBeenCalledWith(
      expect.objectContaining({ transition: 'agree', expense: expect.objectContaining({ id: 'theirs' }) }),
    );
  });
});
