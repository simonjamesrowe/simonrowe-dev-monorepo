import { Plus, Trash2 } from 'lucide-react';

import type { PaymentMethod } from '../../types/expenses';

import { formatMoney } from './money';
import { draftsTotal, localToday, type PaymentDraft, paymentDraft } from './paymentDrafts';
import { PAYMENT_METHODS } from './settle';

/**
 * The payments that settled some expenses, one row each: method, amount, date and reference.
 * The parent enters what was really sent; any difference from what the expenses came to is
 * described under the total and carried into the balance.
 */
export function PaymentsEditor({
  title,
  payments,
  expensesPence,
  difference,
  problem,
  onChange,
}: {
  title: string;
  payments: PaymentDraft[];
  expensesPence: number;
  difference: string | null;
  problem: string | null;
  onChange: (next: PaymentDraft[]) => void;
}) {
  const today = localToday();
  const total = draftsTotal(payments);
  const edit = (key: number, change: Partial<PaymentDraft>) =>
    onChange(payments.map((payment) => (payment.key === key ? { ...payment, ...change } : payment)));

  return (
    <section className="expense-payments" aria-label="Payments">
      <h3 className="expense-payments__title">{title}</h3>
      <p className="expense-muted">Enter what you actually sent. Paid in parts? Add each transfer.</p>
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
                onClick={() => onChange(payments.filter((entry) => entry.key !== payment.key))}
              >
                <Trash2 size={14} aria-hidden="true" />
              </button>
            )}
          </li>
        ))}
      </ul>
      <div className="expense-payments__foot">
        <button
          type="button"
          className="cp-button cp-button--secondary cp-button--sm"
          onClick={() => onChange([...payments, paymentDraft(Math.max(expensesPence - total, 0))])}
        >
          <Plus size={14} aria-hidden="true" /> Add another payment
        </button>
        <span className="expense-payments__total" data-testid="settle-payments-total">
          {formatMoney(total)} sent · expenses {formatMoney(expensesPence)}
        </span>
      </div>
      {problem ? (
        <p className="expense-field__error">{problem}</p>
      ) : (
        difference && (
          <p className="expense-callout expense-callout--amber" data-testid="settle-payments-difference">
            {difference}
          </p>
        )
      )}
    </section>
  );
}
