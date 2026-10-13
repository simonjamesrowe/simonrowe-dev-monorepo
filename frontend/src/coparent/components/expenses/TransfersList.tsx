import { Banknote, Check, Landmark, Pencil, Wallet } from 'lucide-react';

import { useConfirmSettlement } from '../../hooks/api';
import type { Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { Expense, ExpenseSettlement, SettlementPayment } from '../../types/expenses';
import { useToast } from '../ui/ToastProvider';

import { formatMoney } from './money';
import { byDate, differenceText, methodLabel, settlementState } from './settle';
import { firstName } from './parentTone';
import { StatusChip } from './StatusChip';

const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

function PaymentIcon({ method }: { method: SettlementPayment['method'] }) {
  if (method === 'bank_transfer') return <Landmark size={16} aria-hidden="true" />;
  if (method === 'cash') return <Banknote size={16} aria-hidden="true" />;
  return <Wallet size={16} aria-hidden="true" />;
}

/**
 * The family's settle-ups: each one's payments, the expenses they paid back, and where it stands.
 * The parent who recorded one can edit its payments.
 */
export function TransfersList({
  familyId,
  settlements,
  expenses,
  me,
  other,
  onOpenExpense,
  onEdit,
}: {
  familyId: string;
  settlements: ExpenseSettlement[];
  expenses: Expense[];
  me: Parent;
  other: Parent;
  onOpenExpense: (expense: Expense) => void;
  onEdit: (settlement: ExpenseSettlement) => void;
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
        description: failed ? 'Something changed on those; reload and try again' : `${formatMoney(settlement.paidPence)} is settled.`,
      });
    } catch (error) {
      showToast({ variant: 'error', title: 'That did not work', description: apiErrorMessage(error, 'Try again.') });
    }
  };

  return (
    <ul className="expense-transfers">
      {settlements.map((settlement) => {
        const state = settlementState(settlement, expenses, me.id);
        const covered = byDate(
          settlement.expenseIds
            .map((id) => expenses.find((expense) => expense.id === id))
            .filter((expense): expense is Expense => Boolean(expense)),
        );
        const iPaid = settlement.fromParentId === me.id;
        const heading =
          settlement.amountPence === 0
            ? 'Settled against each other'
            : iPaid
              ? `You paid ${otherName} ${formatMoney(settlement.paidPence)}`
              : `${otherName} paid you ${formatMoney(settlement.paidPence)}`;
        const recordedBy = settlement.recordedBy === me.id ? 'you' : otherName;
        const editedBy = settlement.updatedBy === me.id ? 'you' : otherName;
        const difference = differenceText(settlement.paidPence, settlement.amountPence, iPaid, otherName);
        const canEdit = settlement.recordedBy === me.id && settlement.amountPence > 0;
        return (
          <li key={settlement.id} className="expense-transfer" data-testid={`transfer-${settlement.id}`}>
            <div className="expense-transfer__head">
              <div>
                <p className="expense-transfer__title">{heading}</p>
                <p className="expense-muted">
                  Recorded by {recordedBy} on {shortDate(settlement.createdAt.slice(0, 10))}
                  {settlement.updatedAt && ` · edited by ${editedBy} on ${shortDate(settlement.updatedAt.slice(0, 10))}`}
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
            {difference && (
              <p className="expense-callout expense-callout--amber" data-testid="transfer-difference">
                {difference}
              </p>
            )}
            <p className="expense-transfer__covers">
              Pays back {settlement.expenseIds.length} expense{settlement.expenseIds.length === 1 ? '' : 's'}
              {settlement.amountPence > 0 && ` · ${formatMoney(settlement.amountPence)}`}
            </p>
            <ul className="expense-transfer__expenses">
              {covered.map((expense) => (
                <li key={expense.id}>
                  <button type="button" className="expense-transfer__expense" onClick={() => onOpenExpense(expense)}>
                    <span className="expense-transfer__expense-name">
                      <span>{expense.title}</span>
                      <span className="expense-transfer__expense-date">{shortDate(expense.date)}</span>
                    </span>
                    <span>{formatMoney(expense.owedPence)}</span>
                  </button>
                </li>
              ))}
            </ul>
            {(state === 'awaitingMe' || canEdit) && (
              <div className="expense-transfer__actions">
                {state === 'awaitingMe' && (
                  <button
                    type="button"
                    className="cp-button cp-button--primary cp-button--sm"
                    disabled={confirm.isPending}
                    onClick={() => confirmReceived(settlement)}
                  >
                    <Check size={15} aria-hidden="true" /> Confirm received
                  </button>
                )}
                {canEdit && (
                  <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={() => onEdit(settlement)}>
                    <Pencil size={15} aria-hidden="true" /> Edit transfer
                  </button>
                )}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
