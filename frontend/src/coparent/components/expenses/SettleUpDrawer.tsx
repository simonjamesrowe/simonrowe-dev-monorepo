import { Check, Info } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';

import { useSettleExpenses } from '../../hooks/api';
import type { Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { Expense, ExpenseSummary } from '../../types/expenses';
import { useToast } from '../ui/ToastProvider';

import { ExpenseDrawer } from './ExpenseDrawer';
import { formatMoney } from './money';
import { netOf, settleable } from './settle';
import { firstName } from './parentTone';

export function SettleUpDrawer({
  open,
  familyId,
  me,
  other,
  expenses,
  summary,
  onClose,
}: {
  open: boolean;
  familyId: string;
  me: Parent;
  other: Parent;
  expenses: Expense[];
  summary: ExpenseSummary | undefined;
  onClose: () => void;
}) {
  const { showToast } = useToast();
  const settle = useSettleExpenses();
  const [unticked, setUnticked] = useState<Set<string>>(new Set());
  const [error, setError] = useState<unknown>(null);
  const items = useMemo(() => settleable(expenses, me.id), [expenses, me.id]);
  const ticked = items.filter((expense) => !unticked.has(expense.id));
  const net = netOf(ticked, me.id);
  const otherName = firstName(other);

  useEffect(() => {
    if (open) {
      setUnticked(new Set());
      setError(null);
    }
  }, [open]);

  const balance = summary?.balance;
  const overall = !balance || balance.netPence === 0
    ? 'You are all square'
    : balance.creditorParentId === me.id
      ? `Overall, ${otherName} owes you ${formatMoney(balance.netPence)}`
      : `Overall, you owe ${otherName} ${formatMoney(balance.netPence)}`;
  const netText = net > 0 ? `${otherName} pays you ${formatMoney(net)}` : net < 0 ? `You pay ${otherName} ${formatMoney(-net)}` : 'Nothing changes hands';

  const submit = async () => {
    setError(null);
    try {
      const results = await settle.mutateAsync({
        familyId,
        items: ticked.map((expense) => ({ id: expense.id, version: expense.version })),
      });
      const failed = results.filter((result) => result.outcome === 'failed').length;
      const claimed = results.filter((result) => result.outcome === 'claimed').length;
      const settled = results.filter((result) => result.outcome === 'reimbursed' || result.outcome === 'confirmed').length;
      showToast({
        variant: failed ? 'error' : 'success',
        title: failed ? `${failed} could not be settled` : 'Settled up',
        description: [
          settled && `${settled} settled`,
          claimed && `${claimed} marked as paid back, awaiting ${otherName}`,
          failed && 'Something changed on those; reload and try again',
        ]
          .filter(Boolean)
          .join(' · '),
      });
      onClose();
    } catch (caught) {
      setError(caught);
    }
  };

  return (
    <ExpenseDrawer
      open={open}
      title={`Settle up with ${otherName}`}
      description="Settle several expenses in one go, each one individually."
      onClose={onClose}
      footer={
        <>
          <span className="expense-grow" />
          <button type="button" className="expense-button expense-button--ghost" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="expense-button expense-button--primary"
            onClick={submit}
            disabled={ticked.length === 0 || settle.isPending}
          >
            <Check size={16} aria-hidden="true" /> Mark selected settled
          </button>
        </>
      }
    >
      <p className="expense-settle__overall">{overall}</p>
      {error !== null && (
        <p role="alert" className="expense-callout expense-callout--rose">
          {apiErrorMessage(error, 'Settling up did not work. Try again.')}
        </p>
      )}
      {items.length === 0 ? (
        <p className="expense-empty">Nothing to settle.</p>
      ) : (
        <ul className="expense-settle">
          {items.map((expense) => {
            const mine = expense.payerId === me.id;
            const claimed = expense.reimbursement.status === 'claimed';
            const on = !unticked.has(expense.id);
            return (
              <li key={expense.id}>
                <label className="expense-settle__item">
                  <input
                    type="checkbox"
                    checked={on}
                    onChange={() =>
                      setUnticked((current) => {
                        const next = new Set(current);
                        if (next.has(expense.id)) next.delete(expense.id);
                        else next.add(expense.id);
                        return next;
                      })
                    }
                  />
                  <span>
                    <span className="expense-settle__title">{expense.title}</span>
                    <span className="expense-settle__direction">
                      {mine ? (claimed ? `${otherName} already sent it · you confirm it arrived` : `${otherName} owes you`) : `You owe ${otherName}`}
                    </span>
                  </span>
                  <span className={`expense-settle__amount${claimed ? ' is-muted' : mine ? '' : ' is-owe'}`}>
                    {claimed ? 'received ' : mine ? '' : '−'}
                    {formatMoney(expense.owedPence)}
                  </span>
                </label>
              </li>
            );
          })}
        </ul>
      )}
      <div className="expense-settle__net">
        <span>Net</span>
        <span>{netText}</span>
      </div>
      <p className="expense-callout expense-callout--teal">
        <Info size={18} aria-hidden="true" />
        Ticking marks {otherName}'s debts to you as reimbursed, and yours to {otherName} as paid back. {otherName} confirms
        the ones you paid.
      </p>
    </ExpenseDrawer>
  );
}
