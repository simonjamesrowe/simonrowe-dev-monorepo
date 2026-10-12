import { Check, Info } from 'lucide-react';
import { useEffect, useState } from 'react';

import { useMarkPaidBack } from '../../hooks/api';
import type { Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { Expense } from '../../types/expenses';
import { useToast } from '../ui/ToastProvider';

import { ExpenseDrawer } from './ExpenseDrawer';
import { formatMoney } from './money';
import { firstName } from './parentTone';

const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

/**
 * Marks the selected expenses paid back in one go, each exactly as its own "Mark paid back"
 * would. It records no transfer, which is what makes it the way to clear debts settled long ago
 * or outside the app; Settle up is where the bank transfers that paid them are recorded.
 */
export function MarkPaidBackDrawer({
  open,
  familyId,
  me,
  other,
  expenses,
  onClose,
  onDone,
}: {
  open: boolean;
  familyId: string;
  me: Parent;
  other: Parent;
  /** The selected expenses that are outstanding, in either direction. */
  expenses: Expense[];
  onClose: () => void;
  onDone: () => void;
}) {
  const { showToast } = useToast();
  const markPaidBack = useMarkPaidBack();
  const [note, setNote] = useState('Already paid back');
  const [error, setError] = useState<unknown>(null);
  const otherName = firstName(other);
  const iOwe = expenses.filter((expense) => expense.debtorParentId === me.id);
  const owedToMe = expenses.filter((expense) => expense.debtorParentId !== me.id);
  const total = (list: Expense[]) => list.reduce((sum, expense) => sum + expense.owedPence, 0);

  useEffect(() => {
    if (open) {
      setNote('Already paid back');
      setError(null);
    }
  }, [open]);

  const submit = async () => {
    setError(null);
    try {
      const results = await markPaidBack.mutateAsync({ familyId, items: expenses, note: note.trim() });
      const failed = results.filter((result) => result.outcome === 'failed' || result.outcome === 'skipped').length;
      const claimed = results.filter((result) => result.outcome === 'claimed').length;
      const settled = results.filter((result) => result.outcome === 'reimbursed' || result.outcome === 'confirmed').length;
      showToast({
        variant: failed ? 'error' : 'success',
        title: failed ? `${failed} could not be marked` : 'Marked as paid back',
        description: [
          settled && `${settled} settled`,
          claimed && `${claimed} waiting for ${otherName} to confirm`,
          failed && 'Something changed on those; reload and try again',
        ]
          .filter(Boolean)
          .join(' · '),
      });
      onDone();
    } catch (caught) {
      setError(caught);
    }
  };

  return (
    <ExpenseDrawer
      open={open}
      title={`Mark ${expenses.length} as paid back`}
      description="Clears each selected expense from the balance, as Mark paid back does on one."
      onClose={onClose}
      footer={
        <>
          <span className="expense-grow" />
          <button type="button" className="cp-button cp-button--secondary" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="cp-button cp-button--primary"
            onClick={submit}
            // A blank note would be recorded as the server's default, not as the empty field shown.
            disabled={expenses.length === 0 || note.trim() === '' || markPaidBack.isPending}
          >
            <Check size={16} aria-hidden="true" /> Mark {expenses.length} as paid back
          </button>
        </>
      }
    >
      {error !== null && (
        <p role="alert" className="expense-callout expense-callout--rose">
          {apiErrorMessage(error, 'That did not work. Try again.')}
        </p>
      )}
      <ul className="expense-settle">
        {expenses.map((expense) => {
          const mine = expense.debtorParentId === me.id;
          return (
            <li key={expense.id} className="expense-settle__item expense-settle__item--static">
              <span>
                <span className="expense-settle__title">{expense.title}</span>
                <span className="expense-settle__direction">
                  {shortDate(expense.date)} · {mine ? `You owe ${otherName}` : `${otherName} owes you`}
                </span>
              </span>
              <span className={`expense-settle__amount${mine ? ' is-owe' : ''}`}>{formatMoney(expense.owedPence)}</span>
            </li>
          );
        })}
      </ul>
      <div className="expense-settle__net" data-testid="mark-paid-back-totals">
        <span>Clears</span>
        <span>
          {[
            iOwe.length > 0 && `${formatMoney(total(iOwe))} you owe`,
            owedToMe.length > 0 && `${formatMoney(total(owedToMe))} owed to you`,
          ]
            .filter(Boolean)
            .join(' · ')}
        </span>
      </div>
      <label className="expense-field">
        <span className="expense-field__label">Note on each expense</span>
        <input className="expense-input" maxLength={500} value={note} onChange={(event) => setNote(event.target.value)} />
      </label>
      {note.trim() === '' && <small className="expense-field__error">Say how they were paid back</small>}
      <p className="expense-callout expense-callout--teal">
        <Info size={18} aria-hidden="true" />
        No payment is recorded. To record the bank transfers that paid them, use Settle up.{' '}
        {iOwe.length > 0 &&
          (other.status === 'active'
            ? `${otherName} confirms the ones you paid back.`
            : `${otherName} hasn't joined yet, so the ones you owed are settled straight away.`)}
      </p>
    </ExpenseDrawer>
  );
}
