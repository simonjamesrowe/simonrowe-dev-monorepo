import { Banknote, Check, Landmark, Wallet } from 'lucide-react';

import { useConfirmSettlement } from '../../hooks/api';
import type { Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { Expense, ExpenseSettlement, SettlementPayment } from '../../types/expenses';
import { useToast } from '../ui/ToastProvider';

import { formatMoney } from './money';
import { methodLabel, settlementState } from './settle';
import { firstName } from './parentTone';
import { StatusChip } from './StatusChip';

const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

function PaymentIcon({ method }: { method: SettlementPayment['method'] }) {
  if (method === 'bank_transfer') return <Landmark size={16} aria-hidden="true" />;
  if (method === 'cash') return <Banknote size={16} aria-hidden="true" />;
  return <Wallet size={16} aria-hidden="true" />;
}

/** The family's settle-ups: each one's payments, the expenses they paid back, and where it stands. */
export function TransfersList({
  familyId,
  settlements,
  expenses,
  me,
  other,
  onOpenExpense,
}: {
  familyId: string;
  settlements: ExpenseSettlement[];
  expenses: Expense[];
  me: Parent;
  other: Parent;
  onOpenExpense: (expense: Expense) => void;
}) {
  const { showToast } = useToast();
  const confirm = useConfirmSettlement();
  const otherName = firstName(other);

  if (settlements.length === 0) {
    return (
      <p className="expense-empty">
        No transfers yet. Use Settle up to record a bank transfer and the expenses it paid back.
      </p>
    );
  }

  const confirmReceived = async (settlement: ExpenseSettlement) => {
    try {
      const results = await confirm.mutateAsync({ familyId, settlementId: settlement.id });
      const failed = results.filter((result) => result.outcome === 'failed').length;
      showToast({
        variant: failed ? 'error' : 'success',
        title: failed ? `${failed} could not be confirmed` : 'Marked as received',
        description: failed ? 'Something changed on those; reload and try again' : `${formatMoney(settlement.amountPence)} is settled.`,
      });
    } catch (error) {
      showToast({ variant: 'error', title: 'That did not work', description: apiErrorMessage(error, 'Try again.') });
    }
  };

  return (
    <ul className="expense-transfers">
      {settlements.map((settlement) => {
        const state = settlementState(settlement, expenses, me.id);
        const covered = settlement.expenseIds
          .map((id) => expenses.find((expense) => expense.id === id))
          .filter((expense): expense is Expense => Boolean(expense));
        const heading =
          settlement.amountPence === 0
            ? 'Settled against each other'
            : settlement.fromParentId === me.id
              ? `You paid ${otherName} ${formatMoney(settlement.amountPence)}`
              : `${otherName} paid you ${formatMoney(settlement.amountPence)}`;
        const recordedBy = settlement.recordedBy === me.id ? 'you' : otherName;
        return (
          <li key={settlement.id} className="expense-transfer" data-testid={`transfer-${settlement.id}`}>
            <div className="expense-transfer__head">
              <div>
                <p className="expense-transfer__title">{heading}</p>
                <p className="expense-muted">
                  Recorded by {recordedBy} on {shortDate(settlement.createdAt.slice(0, 10))}
                </p>
              </div>
              <StatusChip
                status={
                  state === 'awaitingMe'
                    ? { label: 'Confirm it arrived', tone: 'amber' }
                    : state === 'awaitingThem'
                      ? { label: `Awaiting ${otherName}`, tone: 'slate' }
                      : { label: 'Settled', tone: 'emerald' }
                }
              />
            </div>
            {settlement.payments.length > 0 && (
              <ul className="expense-transfer__payments" aria-label="Payments">
                {settlement.payments.map((payment, index) => (
                  <li key={index}>
                    <PaymentIcon method={payment.method} />
                    <span>
                      {methodLabel(payment.method)} · {shortDate(payment.paidOn)}
                      {payment.reference ? ` · ref ${payment.reference}` : ''}
                    </span>
                    <strong>{formatMoney(payment.amountPence)}</strong>
                  </li>
                ))}
              </ul>
            )}
            <p className="expense-transfer__covers">
              Pays back {settlement.expenseIds.length} expense{settlement.expenseIds.length === 1 ? '' : 's'}
            </p>
            <ul className="expense-transfer__expenses">
              {covered.map((expense) => (
                <li key={expense.id}>
                  <button type="button" className="expense-transfer__expense" onClick={() => onOpenExpense(expense)}>
                    <span>{expense.title}</span>
                    <span>{formatMoney(expense.owedPence)}</span>
                  </button>
                </li>
              ))}
            </ul>
            {state === 'awaitingMe' && (
              <button
                type="button"
                className="cp-button cp-button--primary cp-button--sm expense-transfer__confirm"
                disabled={confirm.isPending}
                onClick={() => confirmReceived(settlement)}
              >
                <Check size={15} aria-hidden="true" /> Confirm received
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}
