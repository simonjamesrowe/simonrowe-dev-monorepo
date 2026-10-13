import { Check } from 'lucide-react';
import { useEffect, useState } from 'react';

import { useUpdateSettlement } from '../../hooks/api';
import type { Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { ExpenseSettlement } from '../../types/expenses';
import { useToast } from '../ui/ToastProvider';

import { ExpenseDrawer } from './ExpenseDrawer';
import { formatMoney } from './money';
import { draftOf, draftsProblem, draftsTotal, type PaymentDraft, toPayments } from './paymentDrafts';
import { PaymentsEditor } from './PaymentsEditor';
import { differenceText } from './settle';
import { firstName } from './parentTone';

/**
 * Corrects a recorded settle-up's payments: what was really sent, when, how and with what
 * reference. The expenses it paid back stay settled; a new difference between the two is carried
 * into the balance, and the other parent is emailed.
 */
export function EditTransferDrawer({
  familyId,
  settlement,
  me,
  other,
  onClose,
}: {
  familyId: string;
  settlement: ExpenseSettlement | null;
  me: Parent;
  other: Parent;
  onClose: () => void;
}) {
  const { showToast } = useToast();
  const update = useUpdateSettlement();
  const [payments, setPayments] = useState<PaymentDraft[]>([]);
  const [error, setError] = useState<unknown>(null);
  const otherName = firstName(other);

  useEffect(() => {
    if (settlement) {
      setPayments(settlement.payments.map(draftOf));
      setError(null);
    }
  }, [settlement]);

  const iPaid = settlement?.fromParentId === me.id;
  const expensesPence = settlement?.amountPence ?? 0;
  const problem = draftsProblem(payments);
  const difference = differenceText(draftsTotal(payments), expensesPence, iPaid, otherName);

  const save = async () => {
    if (!settlement) return;
    setError(null);
    try {
      const saved = await update.mutateAsync({ familyId, settlementId: settlement.id, payments: toPayments(payments) });
      showToast({
        variant: 'success',
        title: 'Transfer updated',
        description: `It now records ${formatMoney(saved.paidPence)} sent.`,
      });
      onClose();
    } catch (caught) {
      setError(caught);
    }
  };

  return (
    <ExpenseDrawer
      open={settlement !== null}
      title="Edit transfer"
      description="Correct how much was sent, when and how. The expenses it paid back stay settled."
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
            onClick={save}
            disabled={problem !== null || update.isPending}
          >
            <Check size={16} aria-hidden="true" /> Save transfer
          </button>
        </>
      }
    >
      {error !== null && (
        <p role="alert" className="expense-callout expense-callout--rose">
          {apiErrorMessage(error, 'Saving the transfer did not work. Try again.')}
        </p>
      )}
      <p className="expense-muted">
        It paid back {settlement?.expenseIds.length ?? 0} expense{settlement?.expenseIds.length === 1 ? '' : 's'}, which came
        to {formatMoney(expensesPence)}. They stay settled whatever you change here.
      </p>
      <PaymentsEditor
        title={iPaid ? `How you paid ${otherName}` : `How ${otherName} paid you`}
        payments={payments}
        expensesPence={expensesPence}
        difference={difference}
        problem={problem}
        onChange={setPayments}
      />
    </ExpenseDrawer>
  );
}
