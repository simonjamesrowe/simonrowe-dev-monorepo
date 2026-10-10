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

import { expenseShareNote, summarizeAction, type SummaryEvent } from './actionSummary';
import { AssistantField } from './AssistantField';
import {
  editablePayload,
  fieldControl,
  normalizedPayload,
  type AssistantEditorOptions,
  type FieldControl,
} from './fieldControls';

export type { AssistantEditorOption, AssistantEditorOptions } from './fieldControls';

const EMPTY_OPTIONS: AssistantEditorOptions = {};

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

/** Plain names for the payload keys; anything else falls back to its key in sentence case. */
const FIELD_LABELS: Record<string, string> = {
  amount: 'Amount (£)',
  currency: 'Currency',
  payerId: 'Who paid, or will pay',
  sharePercent: 'Your share',
  childIds: 'For',
  childId: 'Child',
  expenseId: 'Expense',
  paidOn: 'Paid on',
  timing: 'Paid or coming up',
  eventId: 'Event',
  originalEventId: 'Event to change',
  categoryId: 'Category',
  requestId: 'Request',
  conversationId: 'Conversation',
  recipientId: 'To',
  parentId: 'Children with',
  parentIds: 'Parents going',
  targetHint: 'What it refers to',
  allDay: 'All day',
  recurringFrequency: 'Repeats',
  recurringDays: 'Repeats on',
};

/**
 * The order fields appear in the editor. On a wide screen it fills two columns row by row, so
 * neighbours here sit side by side: start beside end, amount beside currency. Keys not listed
 * keep their payload order after these.
 */
const FIELD_ORDER = [
  'eventId', 'originalEventId', 'categoryId', 'requestId', 'conversationId', 'expenseId',
  'targetHint', 'title', 'name', 'subject', 'recipientId',
  'type', 'childId',
  'amount', 'currency', 'category', 'timing', 'date', 'payerId', 'sharePercent', 'paidOn',
  'originalStartDate', 'originalEndDate', 'newStartDate', 'newEndDate',
  'startDate', 'endDate', 'startTime', 'endTime',
  'location', 'allDay', 'recurringFrequency', 'recurringDays', 'parentId',
  'icon', 'color', 'childIds', 'parentIds',
  'message', 'description', 'reason', 'notes', 'note',
];

const LONG_TEXT = new Set(['message', 'notes', 'note', 'description', 'reason']);
/** Fields that read better across the whole card than squeezed into one column. */
const FULL_WIDTH = new Set(['title', 'name', 'subject', 'type']);
/**
 * Kept in the payload but not shown: the words the note used for a target it could not match
 * are shown once, under the field where the parent picks the target.
 */
const HIDDEN_FIELDS = new Set(['targetHint']);
/** The fields that point at something that already exists, and what each one is. */
const TARGET_FIELDS: Record<string, string> = {
  eventId: 'event',
  originalEventId: 'event',
  categoryId: 'category',
  requestId: 'request',
  conversationId: 'conversation',
  expenseId: 'expense',
};

function fieldLabel(key: string) {
  if (FIELD_LABELS[key]) return FIELD_LABELS[key];
  const words = key.replace(/([A-Z])/g, ' $1').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function orderedFields(payload: Record<string, unknown>) {
  const rank = (key: string) => {
    const index = FIELD_ORDER.indexOf(key);
    return index === -1 ? FIELD_ORDER.length : index;
  };
  return Object.entries(payload).sort(([a], [b]) => rank(a) - rank(b));
}

/** Plain words for each status, so a card says where it stands while closed. */
const STATUS_LABELS: Record<string, string> = {
  BLOCKED: 'Needs a choice',
  PENDING: 'To review',
  APPLYING: 'Applying',
  APPLIED: 'Approved',
  REJECTED: 'Rejected',
  FAILED: 'Failed',
};

/**
 * Who the signed-in parent is, so a card can say "you" and name the other parent: who owes whom
 * on an expense, and who a message goes to.
 */
export interface ExpenseCardContext {
  meId: string;
  otherName: string;
}

function ExpenseShareLine({ payload, context }: {
  payload: Record<string, unknown>;
  context: ExpenseCardContext;
}) {
  const note = expenseShareNote(payload, context.meId, context.otherName);
  return (
    <div className={`expense-summary expense-summary--${note.tone}`}>
      <p className="expense-summary__lead">{note.text}</p>
    </div>
  );
}

function fieldControls(action: AssistantAction, options: AssistantEditorOptions) {
  const required = new Set(requiredFields[action.actionType] ?? []);
  return Object.fromEntries(Object.entries(action.payload).map(([key, value]) => [
    key, fieldControl(action, key, value, options, required),
  ])) as Record<string, FieldControl>;
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

export function AssistantActionCard({
  familyId,
  batchId,
  action,
  online,
  options = EMPTY_OPTIONS,
  expenseContext,
  events,
  receiptImage,
}: {
  familyId: string;
  batchId: string;
  action: AssistantAction;
  online: boolean;
  options?: AssistantEditorOptions;
  expenseContext?: ExpenseCardContext;
  /** The family's events by id, so a card that changes one can name it with its child and date. */
  events?: Record<string, SummaryEvent>;
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
  const controls = useMemo(() => fieldControls(action, options), [action, options]);
  const form = useForm<Record<string, unknown>>({
    resolver: zodResolver(schema),
    defaultValues: editablePayload(action.payload, controls),
  });

  useEffect(() => {
    form.reset(editablePayload(action.payload, controls));
  }, [action.payload, controls, form]);

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

  const summary = summarizeAction(action, {
    options,
    meId: expenseContext?.meId,
    otherName: expenseContext?.otherName,
    events,
  });
  // A target the note named but nobody could match is said once, under the field where the
  // parent picks it, in place of that field's own "select a target" error.
  const hint = action.targetSnapshot && !action.targetSnapshot.entityId
    ? action.targetSnapshot.hint : null;
  const editorFields = showEditor
    ? orderedFields(action.payload).filter(([key]) => !HIDDEN_FIELDS.has(key))
    : [];
  const shownFields = new Set(editorFields.map(([key]) => key));
  const hintField = hint
    ? Object.keys(TARGET_FIELDS).find((key) => shownFields.has(key) && !action.payload[key])
    : undefined;
  const fieldMessage = (key: string) => {
    if (key === hintField) {
      return `The note mentioned “${hint}”. Choose which ${TARGET_FIELDS[key]} that is.`;
    }
    return action.fieldErrors.find((error) => error.field === key)?.message;
  };
  // Errors for a field the editor shows sit under that field; only the rest are listed here.
  const cardErrors = action.fieldErrors.filter((error) => !shownFields.has(error.field));
  const busy = edit.isPending || approve.isPending || reject.isPending || action.status === 'APPLYING';
  const mutationError = edit.error || approve.error || reject.error;

  return (
    <article className={`assistant-card assistant-card--${action.status.toLowerCase()}`}>
      <button
        className="assistant-card__summary"
        type="button"
        aria-expanded={expanded}
        onClick={() => setExpanded(!expanded)}
      >
        <span className="assistant-card__rail" aria-hidden="true" />
        <span className="assistant-card__heading">
          <span className="assistant-card__title-row">
            <span className="assistant-card__type">{summary.label}</span>
            <span className={`assistant-card__status assistant-card__status--${action.status.toLowerCase()}`}>
              {STATUS_LABELS[action.status] ?? action.status.toLowerCase()}
            </span>
          </span>
          {summary.detail && <span className="assistant-card__detail">{summary.detail}</span>}
        </span>
        <ChevronDown className={expanded ? 'assistant-card__chevron--open' : ''} size={18} />
      </button>

      {expanded && (
        <div className="assistant-card__body">
          {hint && !hintField && (
            <p className="assistant-card__notice">
              <AlertTriangle size={16} /> The note mentioned “{hint}”, but it did not match anything.
            </p>
          )}
          {cardErrors.length > 0 && (
            <ul className="assistant-card__errors">
              {cardErrors.map((error) => (
                <li key={`${error.field}-${error.message}`}>{error.message}</li>
              ))}
            </ul>
          )}

          {action.actionType === 'CREATE_EXPENSE' && expenseContext && (
            <ExpenseShareLine payload={action.payload} context={expenseContext} />
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
            <form className="assistant-card__form assistant-editor" onSubmit={save}>
              {editorFields.map(([key]) => {
                const control = controls[key];
                const wide = (control.kind === 'checkboxes' && !control.short)
                  || LONG_TEXT.has(key) || FULL_WIDTH.has(key);
                const clientError = form.formState.errors[key]?.message;
                return (
                  <AssistantField
                    key={key}
                    name={key}
                    label={fieldLabel(key)}
                    control={control}
                    wide={wide}
                    error={clientError ? String(clientError) : fieldMessage(key)}
                    form={form}
                  />
                );
              })}
              <div className="assistant-editor__actions">
                <button className="cp-button cp-button--secondary" disabled={!online || busy} type="submit">
                  Save changes
                </button>
              </div>
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
                  className="cp-button cp-button--primary"
                  type="button"
                  disabled={!online || busy || action.status === 'BLOCKED'}
                  onClick={() => decide('approve')}
                >
                  <Check size={16} /> {action.status === 'FAILED' ? 'Retry' : 'Approve'}
                </button>
                <button
                  className="cp-button cp-button--danger"
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
