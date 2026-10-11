import { Check, Info } from 'lucide-react';
import { useEffect, useState } from 'react';

import { useConvertStatementTransactions } from '../../hooks/api';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { Expense } from '../../types/expenses';
import type { StatementBatchResult } from '../../types/statements';

import { ExpenseDrawer } from './ExpenseDrawer';
import { formatMoney } from './money';
import { tickedByDefault, type SameMerchantCandidate } from './sameMerchant';

const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });

/**
 * Offered straight after a statement transaction becomes an expense: the other payments to the
 * same merchant on the page, to log the same way in one go. Each becomes its own expense with
 * its own amount and date, and the other parent gets one email for them all.
 */
export function SameMerchantDrawer({
  familyId,
  logged,
  merchant,
  candidates,
  otherName,
  onClose,
  onLogged,
}: {
  familyId: string;
  /** The expense just logged, whose terms the others take. Null while the drawer is closed. */
  logged: Expense | null;
  merchant: string;
  candidates: SameMerchantCandidate[];
  otherName: string;
  onClose: () => void;
  onLogged: (results: StatementBatchResult[]) => void;
}) {
  const convertAll = useConvertStatementTransactions();
  const [ticked, setTicked] = useState<Set<string>>(new Set());
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (logged) {
      setTicked(new Set(candidates.filter((candidate) => tickedByDefault(candidate, logged.id)).map((c) => c.fingerprint)));
      setError(null);
    }
    // Reset only when another expense is logged, not when the candidates are re-read under it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [logged?.id]);

  const chosen = candidates.filter((candidate) => ticked.has(candidate.fingerprint));
  const total = chosen.reduce((sum, candidate) => sum + candidate.amountPence, 0);

  const toggle = (fingerprint: string) =>
    setTicked((current) => {
      const next = new Set(current);
      if (next.has(fingerprint)) next.delete(fingerprint);
      else next.add(fingerprint);
      return next;
    });

  const submit = async () => {
    if (!logged) return;
    setError(null);
    try {
      const results = await convertAll.mutateAsync({
        familyId,
        items: chosen.map((candidate) => ({
          fingerprint: candidate.fingerprint,
          uploadId: candidate.uploadId,
          transaction: candidate.transaction,
        })),
        terms: {
          title: logged.title,
          category: logged.category,
          childIds: logged.childIds,
          payerId: logged.payerId,
          shares: logged.shares.map((share) => ({ parentId: share.parentId, percent: share.percent })),
          notes: logged.notes,
        },
      });
      onLogged(results);
    } catch (caught) {
      setError(caught);
    }
  };

  return (
    <ExpenseDrawer
      open={logged !== null}
      eyebrow="Same merchant"
      title={`${candidates.length} more from ${merchant}`}
      description={`Log the other payments to ${merchant} the same way`}
      onClose={onClose}
      footer={
        <>
          <span className="expense-grow" />
          <button type="button" className="cp-button cp-button--secondary" onClick={onClose}>
            Not now
          </button>
          <button
            type="button"
            className="cp-button cp-button--primary"
            onClick={submit}
            disabled={chosen.length === 0 || convertAll.isPending}
          >
            <Check size={16} aria-hidden="true" />
            {convertAll.isPending
              ? 'Logging…'
              : `Log ${chosen.length} expense${chosen.length === 1 ? '' : 's'}`}
          </button>
        </>
      }
    >
      <p className="expense-settle__overall">Log these the same way?</p>
      <p className="expense-muted">
        Each becomes its own expense, called “{logged?.title}”, with its own amount and date, and shared the same way.
      </p>
      {error !== null && (
        <p role="alert" className="expense-callout expense-callout--rose">
          {apiErrorMessage(error, 'Logging them did not work. Try again.')}
        </p>
      )}
      <ul className="expense-settle">
        {candidates.map((candidate) => (
          <li key={candidate.fingerprint}>
            <label className="expense-settle__item">
              <input
                type="checkbox"
                checked={ticked.has(candidate.fingerprint)}
                onChange={() => toggle(candidate.fingerprint)}
              />
              <span>
                <span className="expense-settle__title">{candidate.description}</span>
                <span className="expense-settle__direction">
                  {shortDate(candidate.date)}
                  {candidate.account ? ` · ${candidate.account}` : ''}
                  {candidate.state === 'dismissed' ? ' · you marked it not shared' : ''}
                  {candidate.match && candidate.match.id !== logged?.id
                    ? ` · “${candidate.match.title}” may already be this`
                    : ''}
                </span>
              </span>
              <span className="expense-settle__amount">{formatMoney(candidate.amountPence)}</span>
            </label>
          </li>
        ))}
      </ul>
      <div className="expense-settle__net">
        <span>Total</span>
        <span>{formatMoney(total)}</span>
      </div>
      <p className="expense-callout expense-callout--teal">
        <Info size={18} aria-hidden="true" />
        {otherName} is asked to agree to them all in one email.
      </p>
    </ExpenseDrawer>
  );
}
