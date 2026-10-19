import { Check, Copy, Info, RotateCcw, Share2 } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';

import { track } from '../../../analytics/analytics';
import { useRecordSettlement } from '../../hooks/api';
import type { Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import { formatShortDate } from '../../lib/formatters/date';
import type { Expense, ExpenseSummary } from '../../types/expenses';
import { useToast } from '../ui/ToastProvider';

import { ExpenseDrawer } from './ExpenseDrawer';
import { formatMoney } from './money';
import { draftsProblem, draftsTotal, type PaymentDraft, paymentDraft, toPayments } from './paymentDrafts';
import { PaymentsEditor } from './PaymentsEditor';
import { byDate, differenceText, netOf, settleable, settleMessage, shareLine } from './settle';
import { firstName } from './parentTone';

/**
 * Settling up as the money actually moved. The parent ticks the expenses, then records the
 * payments that settled them: one bank transfer by default, or several when it went in parts.
 * The payments are recorded as sent, so a round £1,500 against £1,478.68 of expenses is fine:
 * the expenses are settled and the £21.32 difference is carried into the balance.
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
  const [message, setMessage] = useState('');
  // Until the parent edits the message, it follows what is ticked.
  const [messageEdited, setMessageEdited] = useState(false);
  const items = useMemo(() => byDate(settleable(expenses, me.id)), [expenses, me.id]);
  const ticked = items.filter((expense) => !unticked.has(expense.id));
  const net = netOf(ticked, me.id);
  const owed = Math.abs(net);
  const otherName = firstName(other);

  useEffect(() => {
    if (open) {
      setUnticked(new Set());
      setPaymentsEdited(false);
      setMessageEdited(false);
      setError(null);
    }
  }, [open]);

  useEffect(() => {
    if (!paymentsEdited) setPayments(owed > 0 ? [paymentDraft(owed)] : []);
  }, [owed, paymentsEdited]);

  const builtMessage = settleMessage(ticked, me.id, otherName);
  useEffect(() => {
    if (!messageEdited) setMessage(builtMessage);
  }, [builtMessage, messageEdited]);

  const copyMessage = async () => {
    try {
      await navigator.clipboard.writeText(message);
      showToast({ variant: 'success', title: 'Message copied', description: `Paste it into your chat with ${otherName}.` });
    } catch {
      showToast({ variant: 'error', title: 'Could not copy', description: 'Select the text and copy it instead.' });
    }
  };
  const canShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function';
  const shareMessage = async () => {
    try {
      await navigator.share({ text: message });
    } catch (caught) {
      // Closing the share sheet is not a failure.
      if (caught instanceof DOMException && caught.name === 'AbortError') return;
      showToast({ variant: 'error', title: 'Could not share', description: 'Copy the message instead.' });
    }
  };

  const balance = summary?.balance;
  const overall = !balance || balance.netPence === 0
    ? 'You are all square'
    : balance.creditorParentId === me.id
      ? `Overall, ${otherName} owes you ${formatMoney(balance.netPence)}`
      : `Overall, you owe ${otherName} ${formatMoney(balance.netPence)}`;
  const netText = net > 0 ? `${otherName} pays you ${formatMoney(net)}` : net < 0 ? `You pay ${otherName} ${formatMoney(-net)}` : 'Nothing changes hands';

  const total = draftsTotal(payments);
  const paymentProblem = owed === 0 ? null : draftsProblem(payments);
  const difference = differenceText(total, owed, net < 0, otherName);
  const changePayments = (next: PaymentDraft[]) => {
    setPaymentsEdited(true);
    setPayments(next);
  };

  const submit = async () => {
    setError(null);
    try {
      const body = owed === 0 ? [] : toPayments(payments);
      const { results } = await record.mutateAsync({ familyId, items: ticked, payments: body });
      const failed = results.filter((result) => result.outcome === 'failed').length;
      const claimed = results.filter((result) => result.outcome === 'claimed').length;
      const settled = results.filter((result) => result.outcome === 'reimbursed' || result.outcome === 'confirmed').length;
      if (results.length > failed) track('Settlement Recorded', { expenseCount: results.length - failed });
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
                    <span className="expense-settle__meta">
                      {formatShortDate(expense.date)} · {shareLine(expense, me.id, otherName)}
                    </span>
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
        <PaymentsEditor
          title={net < 0 ? `How you paid ${otherName}` : `How ${otherName} paid you`}
          payments={payments}
          expensesPence={owed}
          difference={difference}
          problem={paymentProblem}
          onChange={changePayments}
        />
      )}

      {message !== '' || messageEdited ? (
        <section className="expense-settle-message" aria-label={`Message for ${otherName}`}>
          <h3 className="expense-payments__title">Message for {otherName}</h3>
          <p className="expense-muted">Edit it if you like, then copy it into WhatsApp.</p>
          <textarea
            className="expense-input expense-textarea expense-settle-message__text"
            aria-label={`Message for ${otherName}`}
            rows={Math.min(Math.max(message.split('\n').length + 1, 6), 18)}
            value={message}
            onChange={(event) => {
              setMessageEdited(true);
              setMessage(event.target.value);
            }}
          />
          <div className="expense-payments__foot">
            <span>
              {messageEdited && (
                <button
                  type="button"
                  className="cp-button cp-button--secondary cp-button--sm"
                  onClick={() => setMessageEdited(false)}
                >
                  <RotateCcw size={14} aria-hidden="true" /> Rewrite from the ticked expenses
                </button>
              )}
            </span>
            <span className="expense-settle-message__actions">
              {canShare && (
                <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={shareMessage} disabled={!message.trim()}>
                  <Share2 size={14} aria-hidden="true" /> Share
                </button>
              )}
              <button type="button" className="cp-button cp-button--primary cp-button--sm" onClick={copyMessage} disabled={!message.trim()}>
                <Copy size={14} aria-hidden="true" /> Copy message
              </button>
            </span>
          </div>
        </section>
      ) : null}

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
