import type { Parent } from '../../lib/api/client';
import type { Expense, ExpenseSummary } from '../../types/expenses';

import { EXPENSE_CATEGORIES } from './categories';
import { CategoryTile } from './CategoryTile';
import { expenseStatus } from './expenseStatus';
import { formatMoney } from './money';
import { firstName } from './parentTone';
import { StatusChip } from './StatusChip';

const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });

/**
 * The dashboard's view of expenses: the balance, what needs this parent's OK (agreeable in
 * place), what is due soon and this month's spending by category. It replaces the old budget
 * snapshot, which had no data behind it.
 */
export function DashboardExpensesPanel({
  me,
  other,
  expenses,
  summary,
  onOpenExpenses,
  onOpenExpense,
  onSettleUp,
  onAgree,
}: {
  me: Parent | undefined;
  other: Parent | undefined;
  expenses: Expense[];
  summary: ExpenseSummary | undefined;
  onOpenExpenses: () => void;
  onOpenExpense: (expense: Expense) => void;
  onSettleUp: () => void;
  onAgree: (expense: Expense) => void;
}) {
  const otherName = firstName(other);
  const balance = summary?.balance;
  const square = !balance || balance.netPence === 0;
  const owedToMe = balance?.creditorParentId === me?.id;
  const toAgree = me
    ? expenses.filter((expense) => expense.agreement.status === 'pending' && expense.agreement.requestedBy !== me.id)
    : [];
  const byCategory = summary?.thisMonth.byCategory ?? [];
  const largest = Math.max(1, ...byCategory.map((entry) => entry.totalPence));
  const shareOf = (expense: Expense) =>
    expense.shares.find((share) => share.parentId === me?.id)?.sharePence ?? 0;

  return (
    <div>
      <div className="expense-dash-panel__head">
        <h3 className="expense-dash-panel__title">Expenses</h3>
        <button type="button" className="expense-button expense-button--text expense-button--danger-text" onClick={onOpenExpenses}>
          Open expenses
        </button>
      </div>

      {!other ? (
        <p className="expense-muted">Invite your co-parent to start sharing costs.</p>
      ) : (
        <>
          <div className={`expense-dash-strip${!square && !owedToMe ? ' expense-dash-strip--owe' : ''}`}>
            <p>
              {square
                ? 'All square'
                : `${owedToMe ? `${otherName} owes you` : `You owe ${otherName}`} ${formatMoney(balance.netPence)}`}
              {balance && balance.expenseCount > 0
                ? ` · ${balance.expenseCount} expense${balance.expenseCount === 1 ? '' : 's'}`
                : ''}
            </p>
            {!square && (
              <button type="button" className="expense-button expense-button--primary expense-button--sm" onClick={onSettleUp}>
                Settle up
              </button>
            )}
          </div>

          <p className="expense-dash-subhead">Needs your OK</p>
          {toAgree.length === 0 ? (
            <p className="expense-muted">You're all caught up.</p>
          ) : (
            toAgree.slice(0, 3).map((expense) => (
              <div key={expense.id} className="expense-dash-item">
                <CategoryTile category={expense.category} size="sm" />
                <button type="button" className="expense-dash-item__body" onClick={() => onOpenExpense(expense)}>
                  <span className="expense-dash-item__title">
                    {expense.title} · {formatMoney(expense.amountPence)}
                  </span>
                  <span className="expense-dash-item__sub">Your share {formatMoney(shareOf(expense))}</span>
                </button>
                <button type="button" className="expense-button expense-button--primary expense-button--sm" onClick={() => onAgree(expense)}>
                  Agree
                </button>
              </div>
            ))
          )}

          <p className="expense-dash-subhead">
            Coming up · next 30 days · your share {formatMoney(summary?.upcoming.yourSharePence ?? 0)}
          </p>
          {(summary?.upcoming.next ?? []).length === 0 ? (
            <p className="expense-muted">Nothing due.</p>
          ) : (
            summary!.upcoming.next.map((expense) => (
              <button key={expense.id} type="button" className="expense-dash-item" onClick={() => onOpenExpense(expense)}>
                <span className="expense-dash-item__body">
                  <span className="expense-dash-item__title">
                    {expense.title} · {formatMoney(expense.amountPence)}
                  </span>
                  <span className="expense-dash-item__sub">
                    Due {shortDate(expense.date)} ·{' '}
                    {!expense.payerId ? 'Payer not decided' : expense.payerId === me?.id ? 'You will pay' : `${otherName} will pay`}
                  </span>
                </span>
                {me && <StatusChip status={expenseStatus(expense, me.id, otherName)} />}
              </button>
            ))
          )}

          <p className="expense-dash-subhead">
            This month · {formatMoney(summary?.thisMonth.totalPence ?? 0)} · your share{' '}
            {formatMoney(summary?.thisMonth.yourSharePence ?? 0)}
          </p>
          {byCategory.length === 0 ? (
            <p className="expense-muted">Nothing paid yet this month.</p>
          ) : (
            byCategory.slice(0, 5).map((entry) => (
              <div key={entry.category} className="expense-bar-row">
                <span>{EXPENSE_CATEGORIES[entry.category]?.label ?? entry.category}</span>
                <span className="expense-bar">
                  <span style={{ width: `${Math.round((entry.totalPence / largest) * 100)}%` }} />
                </span>
                <span className="expense-bar-row__amount">{formatMoney(entry.totalPence)}</span>
              </div>
            ))
          )}
        </>
      )}
    </div>
  );
}
