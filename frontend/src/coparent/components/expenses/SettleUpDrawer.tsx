import { Check, Info, Plus, Trash2 } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';

import { useRecordSettlement } from '../../hooks/api';
import type { Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { Expense, ExpenseSummary, PaymentMethod, SettlementPayment } from '../../types/expenses';
import { useToast } from '../ui/ToastProvider';

import { ExpenseDrawer } from './ExpenseDrawer';
import { formatMoney, parsePounds, penceToPounds } from './money';
import { netOf, PAYMENT_METHODS, settleable } from './settle';
import { firstName } from './parentTone';

interface PaymentDraft {
  key: number;
  method: PaymentMethod;
  amount: string;
  paidOn: string;
  reference: string;
}

const localToday = () => {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
};

let nextKey = 1;
const draft = (amountPence: number, paidOn = localToday()): PaymentDraft => ({
  key: nextKey++,
  method: 'bank_transfer',
  amount: amountPence > 0 ? penceToPounds(amountPence) : '',
  paidOn,
  reference: '',
});

/**
 * Settling up as the money actually moved. The parent ticks the expenses, then records the
 * payments that settled them: one bank transfer by default, or several when it went in parts.
 * The payments must add up to exactly what the ticked expenses net to, which the server checks
 * too, so a transfer can never mark settled more than it paid.
 */
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
  const record = useRecordSettlement();
  const [unticked, setUnticked] = useState<Set<string>>(new Set());
  const [payments, setPayments] = useState<PaymentDraft[]>([]);
  // Until the parent touches a payment, the single default one follows the ticked total.
  const [paymentsEdited, setPaymentsEdited] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const items = useMemo(() => settleable(expenses, me.id), [expenses, me.id]);
  const ticked = items.filter((expense) => !unticked.has(expense.id));
  const net = netOf(ticked, me.id);
  const owed = Math.abs(net);
  const otherName = firstName(other);
  const today = localToday();

  useEffect(() => {
    if (open) {
      setUnticked(new Set());
      setPaymentsEdited(false);
      setError(null);
    }
  }, [open]);

  useEffect(() => {
    if (!paymentsEdited) setPayments(owed > 0 ? [draft(owed)] : []);
  }, [owed, paymentsEdited]);

  const balance = summary?.balance;
  const overall = !balance || balance.netPence === 0
    ? 'You are all square'
    : balance.creditorParentId === me.id
      ? `Overall, ${otherName} owes you ${formatMoney(balance.netPence)}`
      : `Overall, you owe ${otherName} ${formatMoney(balance.netPence)}`;
  const netText = net > 0 ? `${otherName} pays you ${formatMoney(net)}` : net < 0 ? `You pay ${otherName} ${formatMoney(-net)}` : 'Nothing changes hands';

  const parsed = payments.map((payment) => ({
    ...payment,
    pence: parsePounds(payment.amount),
  }));
  const total = parsed.reduce((sum, payment) => sum + (payment.pence ?? 0), 0);
  const paymentProblem = (() => {
    if (owed === 0) return null;
    if (parsed.length === 0) return 'Add the payment that settled these.';
    if (parsed.some((payment) => payment.pence === null)) return 'Enter an amount for each payment.';
    if (parsed.some((payment) => !payment.paidOn || payment.paidOn > today)) {
      return 'Each payment needs a date that is not in the future.';
    }
    if (total < owed) return `${formatMoney(owed - total)} still to account for.`;
    if (total > owed) return `That is ${formatMoney(total - owed)} more than these expenses come to.`;
    return null;
  })();

  const edit = (key: number, change: Partial<PaymentDraft>) => {
    setPaymentsEdited(true);
    setPayments((current) => current.map((payment) => (payment.key === key ? { ...payment, ...change } : payment)));
  };
  const addPayment = () => {
    setPaymentsEdited(true);
    setPayments((current) => [...current, draft(Math.max(owed - total, 0))]);
  };
  const removePayment = (key: number) => {
    setPaymentsEdited(true);
    setPayments((current) => current.filter((payment) => payment.key !== key));
  };

  const submit = async () => {
    setError(null);
    try {
      const body: SettlementPayment[] =
        owed === 0
          ? []
          : parsed.map((payment) => ({
              method: payment.method,
              amountPence: payment.pence ?? 0,
              paidOn: payment.paidOn,
              reference: payment.reference.trim() || null,
            }));
      const { results } = await record.mutateAsync({ familyId, items: ticked, payments: body });
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
      description="Choose the expenses, then record the payments that settled them."
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
            disabled={ticked.length === 0 || paymentProblem !== null || record.isPending}
          >
            <Check size={16} aria-hidden="true" /> {owed > 0 ? 'Record payment' : 'Mark selected settled'}
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

      {owed > 0 && (
        <section className="expense-payments" aria-label="Payments">
          <h3 className="expense-payments__title">
            {net < 0 ? `How you paid ${otherName}` : `How ${otherName} paid you`}
          </h3>
          <p className="expense-muted">Paid in parts? Add each transfer.</p>
          <ul className="expense-payments__list">
            {payments.map((payment, index) => (
              <li key={payment.key} className="expense-payment" data-testid="settle-payment">
                <label className="expense-field">
                  <span className="expense-field__label">Method</span>
                  <select
                    className="expense-input"
                    aria-label={`Payment ${index + 1} method`}
                    value={payment.method}
                    onChange={(event) => edit(payment.key, { method: event.target.value as PaymentMethod })}
                  >
                    {PAYMENT_METHODS.map((method) => (
                      <option key={method.key} value={method.key}>
                        {method.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="expense-field">
                  <span className="expense-field__label">Amount</span>
                  <span className="expense-money-input">
                    <span aria-hidden="true">£</span>
                    <input
                      className="expense-input"
                      inputMode="decimal"
                      placeholder="0.00"
                      aria-label={`Payment ${index + 1} amount in pounds`}
                      value={payment.amount}
                      onChange={(event) => edit(payment.key, { amount: event.target.value })}
                    />
                  </span>
                </label>
                <label className="expense-field">
                  <span className="expense-field__label">Date</span>
                  <input
                    type="date"
                    className="expense-input"
                    aria-label={`Payment ${index + 1} date`}
                    max={today}
                    value={payment.paidOn}
                    onChange={(event) => edit(payment.key, { paidOn: event.target.value })}
                  />
                </label>
                <label className="expense-field expense-payment__reference">
                  <span className="expense-field__label">Reference (optional)</span>
                  <input
                    className="expense-input"
                    maxLength={100}
                    placeholder="As on the bank statement"
                    aria-label={`Payment ${index + 1} reference`}
                    value={payment.reference}
                    onChange={(event) => edit(payment.key, { reference: event.target.value })}
                  />
                </label>
                {payments.length > 1 && (
                  <button
                    type="button"
                    className="cp-button cp-button--secondary cp-button--sm expense-payment__remove"
                    aria-label={`Remove payment ${index + 1}`}
                    onClick={() => removePayment(payment.key)}
                  >
                    <Trash2 size={14} aria-hidden="true" />
                  </button>
                )}
              </li>
            ))}
          </ul>
          <div className="expense-payments__foot">
            <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={addPayment}>
              <Plus size={14} aria-hidden="true" /> Add another payment
            </button>
            <span className={`expense-payments__total${paymentProblem ? ' is-off' : ''}`} data-testid="settle-payments-total">
              {formatMoney(total)} of {formatMoney(owed)}
            </span>
          </div>
          {paymentProblem && <p className="expense-field__error">{paymentProblem}</p>}
        </section>
      )}

      <p className="expense-callout expense-callout--teal">
        <Info size={18} aria-hidden="true" />
        Ticking marks {otherName}'s debts to you as reimbursed, and yours to {otherName} as paid back.{' '}
        {other.status === 'active'
          ? `${otherName} confirms the ones you paid.`
          : `${otherName} hasn't joined yet, so the ones you paid are settled straight away.`}
      </p>
    </ExpenseDrawer>
  );
}
