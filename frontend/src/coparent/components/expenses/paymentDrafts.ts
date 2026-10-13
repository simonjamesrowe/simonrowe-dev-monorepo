import type { PaymentMethod, SettlementPayment } from '../../types/expenses';

import { parsePounds, penceToPounds } from './money';

export interface PaymentDraft {
  key: number;
  method: PaymentMethod;
  amount: string;
  paidOn: string;
  reference: string;
}

/** Today as YYYY-MM-DD in the browser's own time zone, the latest date a payment can have. */
export const localToday = () => {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
};

let nextKey = 1;
export const paymentDraft = (amountPence: number, paidOn = localToday()): PaymentDraft => ({
  key: nextKey++,
  method: 'bank_transfer',
  amount: amountPence > 0 ? penceToPounds(amountPence) : '',
  paidOn,
  reference: '',
});

/** A recorded payment as an editable draft. */
export const draftOf = (payment: SettlementPayment): PaymentDraft => ({
  key: nextKey++,
  method: payment.method,
  amount: penceToPounds(payment.amountPence),
  paidOn: payment.paidOn,
  reference: payment.reference ?? '',
});

/** What the drafts add up to, counting an amount that does not parse as nothing. */
export function draftsTotal(payments: PaymentDraft[]): number {
  return payments.reduce((sum, payment) => sum + (parsePounds(payment.amount) ?? 0), 0);
}

/** Why the drafts cannot be saved yet, or null. Their total is not checked: whatever was sent is recorded. */
export function draftsProblem(payments: PaymentDraft[], today = localToday()): string | null {
  if (payments.length === 0) return 'Add the payment that was made.';
  if (payments.some((payment) => parsePounds(payment.amount) === null)) return 'Enter an amount for each payment.';
  if (payments.some((payment) => !payment.paidOn || payment.paidOn > today)) {
    return 'Each payment needs a date that is not in the future.';
  }
  return null;
}

export function toPayments(payments: PaymentDraft[]): SettlementPayment[] {
  return payments.map((payment) => ({
    method: payment.method,
    amountPence: parsePounds(payment.amount) ?? 0,
    paidOn: payment.paidOn,
    reference: payment.reference.trim() || null,
  }));
}
