import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, Check, ChevronDown, ExternalLink, RotateCcw, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { prepareSchoolNoteImage } from '../../../pages/admin/schoolNoteImage';
import {
  useApproveAssistantAction,
  useEditAssistantAction,
  useRejectAssistantAction,
} from '../../hooks/api/useAssistant';
import { useUploadReceipt } from '../../hooks/api/useExpenses';
import type { AssistantAction } from '../../types/assistant';
import { describeExpense, formatMoney, parsePounds } from '../expenses/money';

export interface AssistantEditorOption {
  value: string;
  label: string;
}

export type AssistantEditorOptions = Partial<Record<string, AssistantEditorOption[]>>;

const EMPTY_OPTIONS: AssistantEditorOptions = {};

const labels: Record<string, string> = {
  CREATE_EVENT: 'Create event',
  UPDATE_EVENT: 'Update event',
  DELETE_EVENT: 'Delete event',
  CREATE_CATEGORY: 'Create category',
  UPDATE_CATEGORY: 'Update category',
  DELETE_CATEGORY: 'Delete category',
  CREATE_SCHEDULE_CHANGE: 'Request schedule change',
  WITHDRAW_SCHEDULE_CHANGE: 'Withdraw schedule request',
  START_MESSAGE_CONVERSATION: 'Start conversation',
  SEND_MESSAGE: 'Send message',
  CREATE_PERMISSION_REQUEST: 'Create permission request',
  CREATE_EXPENSE: 'Add expense',
  MARK_EXPENSE_PAID: 'Mark expense as paid',
  CLAIM_EXPENSE_REIMBURSEMENT: 'Mark expense paid back',
};

const requiredFields: Record<string, string[]> = {
  CREATE_EVENT: ['title', 'startDate', 'childIds'],
  UPDATE_EVENT: ['eventId'],
  DELETE_EVENT: ['eventId'],
  CREATE_CATEGORY: ['name', 'icon'],
  UPDATE_CATEGORY: ['categoryId'],
  DELETE_CATEGORY: ['categoryId'],
  CREATE_SCHEDULE_CHANGE: ['type', 'newStartDate', 'newEndDate', 'reason'],
  WITHDRAW_SCHEDULE_CHANGE: ['requestId'],
  START_MESSAGE_CONVERSATION: ['message'],
  SEND_MESSAGE: ['conversationId', 'message'],
  CREATE_PERMISSION_REQUEST: ['type', 'childId', 'description'],
  CREATE_EXPENSE: ['title', 'amount', 'category', 'childIds', 'timing', 'date'],
  MARK_EXPENSE_PAID: ['expenseId', 'payerId', 'paidOn'],
  CLAIM_EXPENSE_REIMBURSEMENT: ['expenseId'],
};

/** Plain names for the expense fields; anything else falls back to its spaced-out key. */
const FIELD_LABELS: Record<string, string> = {
  amount: 'Amount (£)',
  currency: 'Currency',
  payerId: 'Who paid, or will pay',
  sharePercent: 'Your share (%)',
  childIds: 'For',
  expenseId: 'Expense',
  paidOn: 'Paid on',
  timing: 'Paid or coming up',
};

/** Who the signed-in parent is, so an expense card can say who owes whom in plain words. */
export interface ExpenseCardContext {
  meId: string;
  otherName: string;
}

function ExpenseSummaryLine({ payload, context }: {
  payload: Record<string, unknown>;
  context: ExpenseCardContext;
}) {
  const amount = typeof payload.amount === 'string' ? parsePounds(payload.amount) : null;
  const share = typeof payload.sharePercent === 'number' ? payload.sharePercent : 50;
  const payerId = typeof payload.payerId === 'string' ? payload.payerId : null;
  const summary = describeExpense({
    amountPence: amount,
    myPercent: share,
    payer: payerId === null ? 'undecided' : payerId === context.meId ? 'me' : 'them',
    timing: payload.timing === 'upcoming' ? 'upcoming' : 'paid',
    otherName: context.otherName,
  });
  return (
    <div className={`expense-summary expense-summary--${summary.tone}`}>
      <div>
        <p className="expense-summary__lead">
          {typeof payload.title === 'string' ? payload.title : 'Expense'}
          {amount ? ` · ${formatMoney(amount)}` : ''}
        </p>
        <p className="expense-summary__lead">{summary.lead}</p>
        {summary.outcome && <p className="expense-summary__outcome">{summary.outcome}</p>}
      </div>
    </div>
  );
}

function editorSchema(action: AssistantAction) {
  return z.record(z.string(), z.unknown()).superRefine((payload, context) => {
    requiredFields[action.actionType]?.forEach((field) => {
      const value = payload[field];
      if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) {
        context.addIssue({ code: 'custom', path: [field], message: 'This field is required' });
      }
    });
  });
}

function editablePayload(payload: Record<string, unknown>, options: AssistantEditorOptions) {
  return Object.fromEntries(
    Object.entries(payload).map(([key, value]) => [
      key,
      Array.isArray(value) && !options[key] ? value.join(', ') : value,
    ]),
  );
}

function normalizedPayload(payload: Record<string, unknown>) {
  return Object.fromEntries(
    Object.entries(payload).map(([key, value]) => {
      if (key.endsWith('Ids') || key === 'recurringDays') {
        return [key, typeof value === 'string'
          ? value.split(',').map((item) => item.trim()).filter(Boolean)
          : value];
      }
      return [key, value === '' ? null : value];
    }),
  );
}

export function AssistantActionCard({
  familyId,
  batchId,
  action,
  online,
  options = EMPTY_OPTIONS,
  expenseContext,
  receiptImage,
}: {
  familyId: string;
  batchId: string;
  action: AssistantAction;
  online: boolean;
  options?: AssistantEditorOptions;
  expenseContext?: ExpenseCardContext;
  /**
   * The photo this batch was read from, still only in the browser. It is attached to the expense
   * only if the parent leaves the box ticked and approves, so CoParent never keeps a photo the
   * parent did not choose to keep.
   */
  receiptImage?: File | null;
}) {
  const [expanded, setExpanded] = useState(action.status === 'BLOCKED');
  const schema = useMemo(() => editorSchema(action), [action]);
  const edit = useEditAssistantAction();
  const approve = useApproveAssistantAction();
  const reject = useRejectAssistantAction();
  const uploadReceipt = useUploadReceipt();
  const [attachReceipt, setAttachReceipt] = useState(true);
  const [receiptFailed, setReceiptFailed] = useState(false);
  const offersReceipt = action.actionType === 'CREATE_EXPENSE' && !!receiptImage;
  const terminal = action.status === 'APPLIED' || action.status === 'REJECTED';
  const isDelete = action.actionType.startsWith('DELETE_')
    || action.actionType === 'WITHDRAW_SCHEDULE_CHANGE';
  const showEditor = !terminal && (!isDelete || action.status === 'BLOCKED');
  const form = useForm<Record<string, unknown>>({
    resolver: zodResolver(schema),
    defaultValues: editablePayload(action.payload, options),
  });

  useEffect(() => {
    form.reset(editablePayload(action.payload, options));
  }, [action.payload, form, options]);

  const save = form.handleSubmit(async (payload) => {
    await edit.mutateAsync({
      familyId,
      batchId,
      action: { ...action, payload: normalizedPayload(payload) },
    });
  });

  const attach = async (expenseId: string) => {
    if (!receiptImage) return;
    const url = URL.createObjectURL(receiptImage);
    try {
      // Re-encoding shrinks the photo and drops its EXIF, location included.
      const blob = Object.assign(await prepareSchoolNoteImage(url), { name: receiptImage.name });
      await uploadReceipt.mutateAsync({ familyId, expenseId, file: blob });
    } catch {
      setReceiptFailed(true);
    } finally {
      URL.revokeObjectURL(url);
    }
  };

  const decide = (kind: 'approve' | 'reject') => {
    const mutation = kind === 'approve' ? approve : reject;
    if (kind !== 'approve' || !offersReceipt || !attachReceipt) {
      mutation.mutate({ familyId, batchId, action });
      return;
    }
    mutation.mutate({ familyId, batchId, action }, {
      onSuccess: ({ action: decided }) => {
        if (decided.status === 'APPLIED' && decided.result?.entityType === 'expense') {
          void attach(decided.result.entityId);
        }
      },
    });
  };

  const busy = edit.isPending || approve.isPending || reject.isPending || action.status === 'APPLYING';
  const mutationError = edit.error || approve.error || reject.error;

  return (
    <article className={`assistant-card assistant-card--${action.status.toLowerCase()}`}>
      <button className="assistant-card__summary" type="button" onClick={() => setExpanded(!expanded)}>
        <span className="assistant-card__rail" aria-hidden="true" />
        <span>
          <span className="assistant-card__type">{labels[action.actionType]}</span>
          <span className="assistant-card__status">{action.status.toLowerCase()}</span>
        </span>
        <ChevronDown className={expanded ? 'assistant-card__chevron--open' : ''} size={18} />
      </button>

      {expanded && (
        <div className="assistant-card__body">
          {action.targetSnapshot?.hint && !action.targetSnapshot.entityId && (
            <p className="assistant-card__notice">
              <AlertTriangle size={16} /> Suggested target: {action.targetSnapshot.hint}
            </p>
          )}
          {action.fieldErrors.length > 0 && (
            <ul className="assistant-card__errors">
              {action.fieldErrors.map((error) => (
                <li key={`${error.field}-${error.message}`}>{error.message}</li>
              ))}
            </ul>
          )}

          {action.actionType === 'CREATE_EXPENSE' && expenseContext && (
            <ExpenseSummaryLine payload={action.payload} context={expenseContext} />
          )}
          {offersReceipt && !terminal && (
            <label className="assistant-field assistant-field--inline">
              <input type="checkbox" checked={attachReceipt}
                onChange={(event) => setAttachReceipt(event.target.checked)} />
              <span>Attach this photo as the receipt</span>
            </label>
          )}
          {receiptFailed && (
            <p className="assistant-card__notice">
              <AlertTriangle size={16} /> The expense was added, but the receipt did not upload.
              Add it from the expense.
            </p>
          )}

          {showEditor && (
            <form className="assistant-card__form" onSubmit={save}>
              {Object.entries(action.payload).map(([key, value]) => (
                <label key={key} className="assistant-field">
                  <span>{FIELD_LABELS[key] ?? key.replace(/([A-Z])/g, ' $1').replace(/^./, (letter) => letter.toUpperCase())}</span>
                  {options[key] ? (
                    <select multiple={key.endsWith('Ids')} {...form.register(key)}>
                      {!key.endsWith('Ids') && <option value="">Select…</option>}
                      {options[key]?.map((option) => (
                        <option key={option.value} value={option.value}>{option.label}</option>
                      ))}
                    </select>
                  ) : typeof value === 'boolean' ? (
                    <input type="checkbox" {...form.register(key)} />
                  ) : key === 'message' || key === 'notes' || key === 'description' || key === 'reason' ? (
                    <textarea rows={3} {...form.register(key)} />
                  ) : (
                    <input {...form.register(key)} />
                  )}
                  {form.formState.errors[key]?.message && (
                    <small>{String(form.formState.errors[key]?.message)}</small>
                  )}
                </label>
              ))}
              <button className="assistant-button assistant-button--secondary" disabled={!online || busy} type="submit">
                Save changes
              </button>
            </form>
          )}

          {action.failureMessage && (
            <p className="assistant-card__notice"><RotateCcw size={16} /> {action.failureMessage}</p>
          )}
          {mutationError && <p className="assistant-card__errors">That request failed. Try again.</p>}

          <div className="assistant-card__actions">
            {!terminal && (
              <>
                <button
                  className="assistant-button assistant-button--approve"
                  type="button"
                  disabled={!online || busy || action.status === 'BLOCKED'}
                  onClick={() => decide('approve')}
                >
                  <Check size={16} /> {action.status === 'FAILED' ? 'Retry' : 'Approve'}
                </button>
                <button
                  className="assistant-button assistant-button--reject"
                  type="button"
                  disabled={!online || busy}
                  onClick={() => decide('reject')}
                >
                  <X size={16} /> Reject
                </button>
              </>
            )}
            {action.result && (
              <a className="assistant-card__result" href={action.result.route}>
                View result <ExternalLink size={14} />
              </a>
            )}
          </div>
        </div>
      )}
    </article>
  );
}
