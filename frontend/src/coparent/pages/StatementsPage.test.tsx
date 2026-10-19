import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { testExpense } from '../components/expenses/testExpense';
import { ToastProvider } from '../components/ui/ToastProvider';
import * as apiHooks from '../hooks/api';
import type { StatementOverview, StatementTransaction, StatementUploadResult } from '../types/statements';

import StatementsPage from './StatementsPage';

const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('../../analytics/analytics', () => ({
  track,
  isAnalyticsConfigured: () => false,
  openCookieSettings: vi.fn(),
}));

vi.mock('../hooks/api', () => ({
  useFamilies: vi.fn(),
  useParentsWithInvited: vi.fn(),
  useChildren: vi.fn(),
  useCurrentParentId: vi.fn(),
  useStatementOverview: vi.fn(),
  useStatementTransactions: vi.fn(),
  useUploadStatement: vi.fn(),
  useCheckStatementRows: vi.fn(),
  useDismissStatementTransaction: vi.fn(),
  useForgetStatementTransaction: vi.fn(),
  useConvertStatementTransaction: vi.fn(),
  useConvertStatementTransactions: vi.fn(),
  useCreateExpense: vi.fn(),
  useUpdateExpense: vi.fn(),
  useUploadReceipt: vi.fn(),
}));

const alex = { id: 'alex', familyId: 'fam-1', fullName: 'Alex Rowe', role: 'primary' as const, status: 'active' };
const sam = { id: 'sam', familyId: 'fam-1', fullName: 'Sam Taylor', role: 'co-parent' as const, status: 'active' };
const fingerprint = 'a'.repeat(64);

const gym: StatementTransaction = {
  id: 'tx-1',
  fingerprint,
  status: 'suggested',
  date: '2026-10-02',
  description: 'KIDS GYM CLUB',
  details: 'Card payment on 01-10-2026',
  amountPence: 11700,
  account: 'Santander current account ··1234',
  suggestion: {
    confidence: 'high',
    title: 'Gymnastics term fees',
    category: 'activities',
    childIds: ['child-1'],
    reason: 'A children’s gym club',
  },
  merchant: null,
  expenseId: null,
  expenseTitle: null,
  uploadId: 'up-1',
  match: null,
  decidedAt: null,
};

const mutateAsync = {
  upload: vi.fn(),
  check: vi.fn(),
  dismiss: vi.fn(),
  forget: vi.fn(),
  convert: vi.fn(),
  convertAll: vi.fn(),
};
const mutation = (fn: ReturnType<typeof vi.fn>) => ({ mutateAsync: fn, isPending: false }) as never;

function setUp({ overview, listed = [] }: { overview: StatementOverview; listed?: StatementTransaction[] }) {
  vi.mocked(apiHooks.useFamilies).mockReturnValue({
    data: [{ id: 'fam-1', name: 'Family', timeZone: 'Europe/London' }],
    isLoading: false,
  } as never);
  vi.mocked(apiHooks.useCurrentParentId).mockReturnValue('alex');
  vi.mocked(apiHooks.useParentsWithInvited).mockReturnValue({ data: [alex, sam] } as never);
  vi.mocked(apiHooks.useChildren).mockReturnValue({
    data: [{ id: 'child-1', familyId: 'fam-1', fullName: 'Mia Rowe', dateOfBirth: '2016-01-01' }],
  } as never);
  vi.mocked(apiHooks.useStatementOverview).mockReturnValue({ data: overview } as never);
  vi.mocked(apiHooks.useStatementTransactions).mockReturnValue({ data: listed, isLoading: false } as never);
  vi.mocked(apiHooks.useUploadStatement).mockReturnValue(mutation(mutateAsync.upload));
  vi.mocked(apiHooks.useCheckStatementRows).mockReturnValue(mutation(mutateAsync.check));
  vi.mocked(apiHooks.useDismissStatementTransaction).mockReturnValue(mutation(mutateAsync.dismiss));
  vi.mocked(apiHooks.useForgetStatementTransaction).mockReturnValue(mutation(mutateAsync.forget));
  vi.mocked(apiHooks.useConvertStatementTransaction).mockReturnValue(mutation(mutateAsync.convert));
  vi.mocked(apiHooks.useConvertStatementTransactions).mockReturnValue(mutation(mutateAsync.convertAll));
  for (const hook of [apiHooks.useCreateExpense, apiHooks.useUpdateExpense, apiHooks.useUploadReceipt]) {
    vi.mocked(hook).mockReturnValue(mutation(vi.fn()));
  }
}

const overview = (toReview = 0): StatementOverview => ({ aiEnabled: true, toReview, dismissed: 0, logged: 0, uploads: [] });

function renderPage(path = '/expenses/statements') {
  return render(
    <ToastProvider>
      <MemoryRouter initialEntries={[path]}>
        <StatementsPage />
      </MemoryRouter>
    </ToastProvider>,
  );
}

const uploadResult = (fresh: boolean): StatementUploadResult => ({
  upload: fresh
    ? {
        id: 'up-1',
        format: 'AMEX',
        account: 'American Express',
        from: '2026-09-02',
        to: '2026-09-05',
        spendingCount: 2,
        moneyInCount: 1,
        newCount: 2,
        checkedCount: 0,
        suggestedCount: 0,
        status: 'checking',
        createdAt: '2026-10-10T10:00:00Z',
      }
    : null,
  format: 'AMEX',
  account: 'American Express',
  from: '2026-09-02',
  to: '2026-09-05',
  moneyIn: 1,
  unreadable: 0,
  aiEnabled: true,
  rows: [
    {
      fingerprint: 'b'.repeat(64),
      date: '2026-09-05',
      description: 'EXAMPLE BIKES',
      details: 'LONDON',
      amountPence: 232,
      account: 'American Express',
      state: fresh ? null : 'checked',
      transactionId: null,
      expenseId: null,
      match: null,
    },
    {
      fingerprint: 'c'.repeat(64),
      date: '2026-09-02',
      description: 'Collctiv Class Gift',
      details: '',
      amountPence: 3000,
      account: 'American Express',
      state: fresh ? null : 'suggested',
      transactionId: null,
      expenseId: null,
      match: null,
    },
  ],
});

describe('StatementsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('opens on the suggestions waiting, and turns one into an expense the co-parent paid', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(1), listed: [gym] });
    mutateAsync.convert.mockResolvedValue(testExpense({ id: 'new', payerId: 'sam' }));
    renderPage();

    const row = screen.getByTestId('statement-suggestion-tx-1');
    expect(within(row).getByText('Likely shared')).toBeInTheDocument();
    expect(within(row).getByText(/Gymnastics term fees — A children’s gym club/)).toBeInTheDocument();
    expect(within(row).getByText("Sam's share £58.50")).toBeInTheDocument();

    await user.click(within(row).getByRole('button', { name: 'Turn into expense' }));
    expect(await screen.findByTestId('expense-source')).toHaveTextContent('KIDS GYM CLUB · £117.00');
    expect(screen.getByDisplayValue('Gymnastics term fees')).toBeInTheDocument();
    expect(screen.getByLabelText('Amount in pounds')).toHaveValue('117.00');
    // Always paid, so there is no paid-or-upcoming choice, but either parent can have paid.
    expect(screen.queryByText('Has it been paid?')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Sam' }));
    await user.click(screen.getByRole('button', { name: 'Add expense' }));

    await waitFor(() => expect(mutateAsync.convert).toHaveBeenCalledTimes(1));
    const call = mutateAsync.convert.mock.calls[0][0];
    expect(call).toMatchObject({ familyId: 'fam-1', fingerprint, uploadId: 'up-1', transaction: null });
    expect(call.expense).toMatchObject({
      title: 'Gymnastics term fees',
      category: 'activities',
      childIds: ['child-1'],
      amountPence: 11700,
      timing: 'paid',
      date: '2026-10-02',
      payerId: 'sam',
    });
    expect(await screen.findByText('Expense added')).toBeInTheDocument();
  });

  it('offers to log the other payments to the same merchant the same way, in one go', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(0) });
    const statement = uploadResult(false);
    const row = (letter: string, date: string, amountPence: number, state: 'checked' | 'dismissed' = 'checked') => ({
      ...statement.rows[0],
      fingerprint: letter.repeat(64),
      date,
      description: `BOOTS 07${letter === 'd' ? '75' : '12'}`,
      amountPence,
      state,
    });
    mutateAsync.upload.mockResolvedValue({
      ...statement,
      rows: [row('d', '2026-09-05', 2585), row('e', '2026-09-04', 1099), row('f', '2026-09-03', 450, 'dismissed'), ...statement.rows],
    });
    const logged = testExpense({ id: 'new', title: 'Calpol', category: 'medical', amountPence: 2585 });
    mutateAsync.convert.mockResolvedValue(logged);
    mutateAsync.convertAll.mockResolvedValue([
      { fingerprint: 'e'.repeat(64), expense: testExpense({ id: 'new-2' }), message: null },
    ]);
    renderPage();

    await user.upload(screen.getByLabelText('Statement files'), new File(['x'], 'activity.csv', { type: 'text/csv' }));
    await screen.findByText('Nothing new in that file');
    await user.click(screen.getByRole('button', { name: /Latest upload/ }));
    await user.click(
      within(screen.getByTestId(`statement-row-${'d'.repeat(64)}`)).getByRole('button', { name: 'Turn into expense' }),
    );
    await user.click(await screen.findByRole('button', { name: 'Add expense' }));

    expect(await screen.findByText('2 more from BOOTS 0775')).toBeInTheDocument();
    // The one marked not shared is listed, but not ticked.
    const boxes = screen.getAllByRole('checkbox');
    expect(boxes.map((box) => (box as HTMLInputElement).checked)).toEqual([true, false]);
    expect(screen.getByText(/you marked it not shared/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Log 1 expense' }));

    await waitFor(() => expect(mutateAsync.convertAll).toHaveBeenCalledTimes(1));
    const call = mutateAsync.convertAll.mock.calls[0][0];
    expect(call.items).toEqual([
      expect.objectContaining({ fingerprint: 'e'.repeat(64), uploadId: null, transaction: expect.objectContaining({ amountPence: 1099 }) }),
    ]);
    expect(call.terms).toMatchObject({ title: 'Calpol', category: 'medical', childIds: ['child-1'], payerId: 'alex' });
    expect(call.terms.shares).toEqual([
      { parentId: 'alex', percent: 50 },
      { parentId: 'sam', percent: 50 },
    ]);
    expect(await screen.findByText('1 more expense added')).toBeInTheDocument();
  });

  it('marks a suggestion as not shared', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(1), listed: [gym] });
    mutateAsync.dismiss.mockResolvedValue({ ...gym, status: 'dismissed' });
    renderPage();

    await user.click(
      within(screen.getByTestId('statement-suggestion-tx-1')).getByRole('button', { name: 'Not shared' }),
    );
    expect(mutateAsync.dismiss).toHaveBeenCalledWith({ familyId: 'fam-1', id: 'tx-1' });
  });

  it('checks a new upload in batches and lists every row of it', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(0) });
    mutateAsync.upload.mockResolvedValue(uploadResult(true));
    mutateAsync.check.mockResolvedValue([
      { fingerprint: 'b'.repeat(64), state: 'checked', transaction: null },
      { fingerprint: 'c'.repeat(64), state: 'suggested', transaction: { ...gym, id: 'tx-2' } },
    ]);
    renderPage();

    expect(screen.getByText(/Your statement isn't kept, and Sam never sees it/)).toBeInTheDocument();
    await user.upload(screen.getByLabelText('Statement files'), new File(['x'], 'activity.csv', { type: 'text/csv' }));

    await waitFor(() => expect(mutateAsync.check).toHaveBeenCalledTimes(1));
    expect(mutateAsync.check.mock.calls[0][0]).toMatchObject({ familyId: 'fam-1', uploadId: 'up-1' });
    expect(mutateAsync.check.mock.calls[0][0].rows).toHaveLength(2);
    expect(await screen.findByText('1 possible shared cost found')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Latest upload/ }));
    expect(screen.getByText('EXAMPLE BIKES')).toBeInTheDocument();
    expect(screen.getByText('Suggested')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Turn into expense' })).toHaveLength(2);
  });

  it('says so, and checks nothing, when a statement was already imported', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(0) });
    mutateAsync.upload.mockResolvedValue(uploadResult(false));
    renderPage();

    await user.upload(screen.getByLabelText('Statement files'), new File(['x'], 'activity.csv', { type: 'text/csv' }));

    expect(await screen.findByText('Nothing new in that file')).toBeInTheDocument();
    expect(mutateAsync.check).not.toHaveBeenCalled();
  });

  it('shows why a file could not be read', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(0) });
    mutateAsync.upload.mockRejectedValue({
      response: { data: { message: "This doesn't look like a statement CoParent can read." } },
    });
    renderPage();

    await user.upload(screen.getByLabelText('Statement files'), new File(['x'], 'notes.csv', { type: 'text/csv' }));

    expect(await screen.findByText("notes.csv couldn't be read")).toBeInTheDocument();
  });
});

describe('StatementsPage analytics', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('reports each uploaded statement with its row and suggestion counts, never its contents', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(0) });
    mutateAsync.upload.mockResolvedValue(uploadResult(true));
    mutateAsync.check.mockResolvedValue([
      { fingerprint: 'b'.repeat(64), state: 'checked', transaction: null },
      { fingerprint: 'c'.repeat(64), state: 'suggested', transaction: { ...gym, id: 'tx-2' } },
    ]);
    renderPage();

    await user.upload(screen.getByLabelText('Statement files'), new File(['x'], 'activity.csv', { type: 'text/csv' }));

    await screen.findByText('1 possible shared cost found');
    expect(track.mock.calls).toEqual([['Statement Uploaded', { rowCount: 2, suggestedCount: 1 }]]);
  });

  it('reports a statement already imported as an upload with nothing suggested', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(0) });
    mutateAsync.upload.mockResolvedValue(uploadResult(false));
    renderPage();

    await user.upload(screen.getByLabelText('Statement files'), new File(['x'], 'activity.csv', { type: 'text/csv' }));

    await screen.findByText('Nothing new in that file');
    expect(track.mock.calls).toEqual([['Statement Uploaded', { rowCount: 2, suggestedCount: 0 }]]);
  });

  it('reports a converted transaction, and then the same-merchant batch, as created from a statement', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(1), listed: [gym] });
    mutateAsync.convert.mockResolvedValue(testExpense({ id: 'new', payerId: 'sam' }));
    renderPage();

    await user.click(
      within(screen.getByTestId('statement-suggestion-tx-1')).getByRole('button', { name: 'Turn into expense' }),
    );
    await user.click(await screen.findByRole('button', { name: 'Add expense' }));
    await screen.findByText('Expense added');

    // Counted once, by the form that saved it, not again by the page that opened the form.
    expect(track.mock.calls).toEqual([['Expense Created', { source: 'statement', count: 1 }]]);
  });

  it('counts only the same-merchant payments that actually became expenses', async () => {
    const user = userEvent.setup();
    setUp({ overview: overview(0) });
    const statement = uploadResult(false);
    const row = (letter: string, amountPence: number) => ({
      ...statement.rows[0],
      fingerprint: letter.repeat(64),
      description: 'BOOTS 0712',
      amountPence,
    });
    mutateAsync.upload.mockResolvedValue({ ...statement, rows: [row('d', 2585), row('e', 1099), row('f', 450)] });
    mutateAsync.convert.mockResolvedValue(testExpense({ id: 'new', title: 'Calpol', amountPence: 2585 }));
    mutateAsync.convertAll.mockResolvedValue([
      { fingerprint: 'e'.repeat(64), expense: testExpense({ id: 'new-2' }), message: null },
      { fingerprint: 'f'.repeat(64), expense: null, message: 'Already logged' },
    ]);
    renderPage();

    await user.upload(screen.getByLabelText('Statement files'), new File(['x'], 'activity.csv', { type: 'text/csv' }));
    await screen.findByText('Nothing new in that file');
    await user.click(screen.getByRole('button', { name: /Latest upload/ }));
    await user.click(
      within(screen.getByTestId(`statement-row-${'d'.repeat(64)}`)).getByRole('button', { name: 'Turn into expense' }),
    );
    await user.click(await screen.findByRole('button', { name: 'Add expense' }));
    await user.click(await screen.findByRole('button', { name: 'Log 2 expenses' }));

    await waitFor(() => expect(mutateAsync.convertAll).toHaveBeenCalledTimes(1));
    expect(track.mock.calls.filter(([name]) => name === 'Expense Created')).toEqual([
      ['Expense Created', { source: 'statement', count: 1 }],
      ['Expense Created', { source: 'statement', count: 1 }],
    ]);
  });
});
