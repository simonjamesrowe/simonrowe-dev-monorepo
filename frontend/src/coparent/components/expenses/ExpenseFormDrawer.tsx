import { AlertCircle, Calendar, Camera, Check, Info, Landmark, Mail, Sparkles, Wallet } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

import { prepareSchoolNoteImage } from '../../../pages/admin/schoolNoteImage';
import { useCreateExpense, useUpdateExpense, useUploadReceipt } from '../../hooks/api';
import type { Child, Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { Expense, ExpenseCategory, ExpenseRequest, ExpenseTiming } from '../../types/expenses';

import { EXPENSE_CATEGORIES } from './categories';
import { ExpenseDrawer } from './ExpenseDrawer';
import { describeExpense, parsePounds, penceToPounds, type PayerChoice } from './money';
import { ParentDot } from './ParentDot';
import { firstName, parentTone } from './parentTone';
import { PendingReceiptThumb } from './ReceiptThumb';

const MAX_RECEIPTS = 5;
const RECEIPT_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'];

type ShareMode = 'equal' | 'me' | 'them' | 'custom';

interface PendingFile {
  file: File;
  previewUrl: string | null;
}

interface FormState {
  title: string;
  amount: string;
  category: ExpenseCategory;
  childIds: string[];
  timing: ExpenseTiming;
  payer: PayerChoice;
  date: string;
  shareMode: ShareMode;
  myPercent: number;
  notes: string;
}

const today = () => new Date().toISOString().slice(0, 10);

/**
 * A transaction from one of the parent's own statements being turned into an expense. It is
 * always already paid, so the form asks only who paid: a joint account's payments can belong to
 * either parent.
 */
export interface ExpenseFormSource {
  /** Changes for each transaction, so the form resets when another one is opened. */
  key: string;
  /** The merchant and amount, as the statement has them. */
  heading: string;
  /** The account and date. */
  detail: string;
  draft: {
    title: string;
    amountPence: number;
    date: string;
    category: ExpenseCategory;
    childIds: string[];
  };
  submit: (request: ExpenseRequest) => Promise<Expense>;
}

function initialState(
  expense: Expense | null,
  me: string,
  children: Child[],
  draft?: ExpenseFormSource['draft'],
): FormState {
  if (!expense && draft) {
    return {
      title: draft.title,
      amount: penceToPounds(draft.amountPence),
      category: draft.category,
      childIds: draft.childIds.length > 0 ? [...draft.childIds] : children.length === 1 ? [children[0].id] : [],
      timing: 'paid',
      payer: 'me',
      date: draft.date,
      shareMode: 'equal',
      myPercent: 50,
      notes: '',
    };
  }
  if (!expense) {
    return {
      title: '',
      amount: '',
      category: 'clothing',
      childIds: children.length === 1 ? [children[0].id] : [],
      timing: 'paid',
      payer: 'me',
      date: today(),
      shareMode: 'equal',
      myPercent: 50,
      notes: '',
    };
  }
  const mine = expense.shares.find((share) => share.parentId === me)?.percent ?? 50;
  return {
    title: expense.title,
    amount: penceToPounds(expense.amountPence),
    category: expense.category,
    childIds: [...expense.childIds],
    timing: expense.timing,
    payer: expense.payerId === null ? 'undecided' : expense.payerId === me ? 'me' : 'them',
    date: expense.date,
    shareMode: mine === 50 ? 'equal' : mine === 100 ? 'me' : mine === 0 ? 'them' : 'custom',
    myPercent: mine,
    notes: expense.notes ?? '',
  };
}

function percentFor(state: FormState): number {
  if (state.shareMode === 'equal') return 50;
  if (state.shareMode === 'me') return 100;
  if (state.shareMode === 'them') return 0;
  return state.myPercent;
}

/** Field errors from a `validation_failed` response, keyed by request field. */
function fieldErrors(error: unknown): Record<string, string> {
  const data = (error as { response?: { data?: { fieldErrors?: unknown } } } | null)?.response?.data;
  return data && typeof data.fieldErrors === 'object' && data.fieldErrors
    ? (data.fieldErrors as Record<string, string>)
    : {};
}

/** Makes a phone photo small and strips its location data by re-encoding; PDFs go as they are. */
async function prepareReceipt(pending: PendingFile): Promise<Blob & { name?: string }> {
  if (!pending.previewUrl || pending.file.type === 'application/pdf') return pending.file;
  const blob = await prepareSchoolNoteImage(pending.previewUrl);
  return Object.assign(blob, { name: pending.file.name.replace(/\.\w+$/, '') + '.jpg' });
}

export interface ExpenseFormDrawerProps {
  open: boolean;
  familyId: string;
  me: Parent;
  other: Parent;
  children: Child[];
  /** The expense being edited, or null to add one. */
  expense: Expense | null;
  onClose: () => void;
  onSaved: (expense: Expense, receiptFailures: number) => void;
  onOpenQuickAdd?: () => void;
  /** Set when the expense comes from a statement transaction: prefills it and saves through it. */
  source?: ExpenseFormSource | null;
}

export function ExpenseFormDrawer({
  open,
  familyId,
  me,
  other,
  children,
  expense,
  onClose,
  onSaved,
  onOpenQuickAdd,
  source = null,
}: ExpenseFormDrawerProps) {
  const [state, setState] = useState<FormState>(() => initialState(expense, me.id, children, source?.draft));
  const [files, setFiles] = useState<PendingFile[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const createExpense = useCreateExpense();
  const updateExpense = useUpdateExpense();
  const uploadReceipt = useUploadReceipt();

  // Preview URLs are released when a file is removed, when the form resets, and on unmount;
  // never just because another file was added, or the thumbnails still showing would break.
  const filesRef = useRef<PendingFile[]>([]);
  filesRef.current = files;
  const releasePreviews = () =>
    filesRef.current.forEach((pending) => pending.previewUrl && URL.revokeObjectURL(pending.previewUrl));

  useEffect(() => {
    if (open) {
      releasePreviews();
      setState(initialState(expense, me.id, children, source?.draft));
      setFiles([]);
      setError(null);
    }
    // Reset only when the drawer opens or switches expense, never while typing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, expense?.id, source?.key]);

  useEffect(() => releasePreviews, []);

  const otherName = firstName(other);
  const amountPence = parsePounds(state.amount);
  const myPercent = percentFor(state);
  const editing = expense !== null;
  const existingReceipts = expense?.receipts.length ?? 0;
  const dueLabel = state.date
    ? new Date(`${state.date}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
    : undefined;
  const summary = useMemo(
    () => describeExpense({ amountPence, myPercent, payer: state.payer, timing: state.timing, otherName, dueLabel }),
    [amountPence, myPercent, state.payer, state.timing, otherName, dueLabel],
  );
  const valid =
    state.title.trim().length > 0 &&
    amountPence !== null &&
    state.childIds.length > 0 &&
    state.date.length > 0 &&
    (state.timing === 'upcoming' || state.payer !== 'undecided');
  const errors = fieldErrors(error);
  const set = <K extends keyof FormState>(key: K, value: FormState[K]) =>
    setState((current) => ({ ...current, [key]: value }));

  const chooseTiming = (timing: ExpenseTiming) =>
    setState((current) => ({
      ...current,
      timing,
      payer: timing === 'paid' && current.payer === 'undecided' ? 'me' : current.payer,
    }));

  const addFiles = (list: FileList | null) => {
    if (!list) return;
    const room = MAX_RECEIPTS - existingReceipts - files.length;
    const accepted = Array.from(list)
      .filter((file) => RECEIPT_TYPES.includes(file.type))
      .slice(0, Math.max(0, room))
      .map((file) => ({ file, previewUrl: file.type === 'application/pdf' ? null : URL.createObjectURL(file) }));
    setFiles((current) => [...current, ...accepted]);
  };

  const removeFile = (index: number) =>
    setFiles((current) => {
      const removed = current[index];
      if (removed?.previewUrl) URL.revokeObjectURL(removed.previewUrl);
      return current.filter((_, position) => position !== index);
    });

  const save = async () => {
    if (!valid || amountPence === null) return;
    setSaving(true);
    setError(null);
    const request: ExpenseRequest = {
      title: state.title.trim(),
      category: state.category,
      childIds: state.childIds,
      amountPence,
      currency: 'GBP',
      timing: state.timing,
      date: state.date,
      payerId: state.payer === 'me' ? me.id : state.payer === 'them' ? other.id : null,
      shares: [
        { parentId: me.id, percent: myPercent },
        { parentId: other.id, percent: 100 - myPercent },
      ],
      notes: state.notes.trim() || null,
    };
    try {
      const saved = expense
        ? await updateExpense.mutateAsync({ familyId, id: expense.id, ...request, version: expense.version })
        : source
          ? await source.submit(request)
          : await createExpense.mutateAsync({ familyId, ...request });
      let failures = 0;
      let latest = saved;
      // One at a time, after the expense exists: a failed upload never loses the expense.
      for (const pending of files) {
        try {
          latest = await uploadReceipt.mutateAsync({
            familyId,
            expenseId: saved.id,
            file: await prepareReceipt(pending),
          });
        } catch {
          failures += 1;
        }
      }
      onSaved(latest, failures);
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  };

  const payerButton = (value: PayerChoice, label: string, parent?: Parent) => (
    <button
      type="button"
      aria-pressed={state.payer === value}
      className={`expense-seg__option${state.payer === value ? ` is-on${parent ? ` is-${parentTone(parent)}` : ''}` : ''}`}
      onClick={() => set('payer', value)}
    >
      {parent && <ParentDot tone={parentTone(parent)} />}
      {label}
    </button>
  );

  const shareButton = (value: ShareMode, label: string) => (
    <button
      type="button"
      aria-pressed={state.shareMode === value}
      className={`expense-pill${state.shareMode === value ? ' is-on' : ''}`}
      onClick={() => set('shareMode', value)}
    >
      {state.shareMode === value && <Check size={15} aria-hidden="true" />}
      {label}
    </button>
  );

  const agreedWarning = editing && expense.agreement.status === 'agreed';
  const SummaryIcon = state.timing === 'upcoming' ? Calendar : summary.tone === 'neutral' ? Info : Wallet;

  return (
    <ExpenseDrawer
      open={open}
      title={editing ? 'Edit expense' : 'Add expense'}
      eyebrow={source ? 'From your statement' : undefined}
      wide
      description="Record a shared cost and how it is split between you."
      onClose={onClose}
      footer={
        <>
          {error !== null && Object.keys(errors).length === 0 && (
            <p role="alert" className="expense-callout expense-callout--rose expense-grow">
              <AlertCircle size={16} aria-hidden="true" />
              {apiErrorMessage(error, 'The expense could not be saved. Try again.')}
            </p>
          )}
          <span className="expense-grow" />
          <button type="button" className="cp-button cp-button--secondary" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="cp-button cp-button--primary"
            onClick={save}
            disabled={!valid || saving}
          >
            {saving ? 'Saving…' : editing ? 'Save changes' : 'Add expense'}
          </button>
        </>
      }
    >
      {source && (
        <div className="expense-source" data-testid="expense-source">
          <Landmark size={18} aria-hidden="true" />
          <span>
            <strong>{source.heading}</strong>
            <small>
              {source.detail} · {otherName} sees the expense, never the statement
            </small>
          </span>
        </div>
      )}
      {!editing && !source && onOpenQuickAdd && (
        <button type="button" className="expense-callout expense-callout--teal expense-callout--button" onClick={onOpenQuickAdd}>
          <Sparkles size={18} aria-hidden="true" />
          <span>
            <strong>Got a note or a receipt?</strong> Paste it into Quick add and it fills this in for you.
          </span>
        </button>
      )}
      {agreedWarning && (
        <p className="expense-callout expense-callout--amber">
          <AlertCircle size={18} aria-hidden="true" />
          This expense is agreed. Changing the amount, the share or who paid will ask {otherName} to agree again.
        </p>
      )}

      {/* What it was on the left; who paid and how it is shared on the right. A phone gets one
          column in the same order. */}
      <div className="cp-form-cols">
        <div className="cp-form-col">
          <label className="expense-field">
            <span className="expense-field__label">What was it for?</span>
            <input
              className="expense-input"
              value={state.title}
              maxLength={120}
              placeholder="e.g. School shoes"
              onChange={(event) => set('title', event.target.value)}
            />
            {errors.title && <small className="expense-field__error">{errors.title}</small>}
          </label>

          <div className="expense-grid2 expense-grid2--keep">
            <label className="expense-field">
              <span className="expense-field__label">Amount</span>
              <span className="expense-money-input">
                <span aria-hidden="true">£</span>
                <input
                  className="expense-input"
                  inputMode="decimal"
                  value={state.amount}
                  placeholder="0.00"
                  aria-label="Amount in pounds"
                  onChange={(event) => set('amount', event.target.value)}
                />
              </span>
              {errors.amountPence && <small className="expense-field__error">{errors.amountPence}</small>}
            </label>
            <label className="expense-field">
              <span className="expense-field__label">Category</span>
              <select
                className="expense-input"
                value={state.category}
                onChange={(event) => set('category', event.target.value as ExpenseCategory)}
              >
                {Object.entries(EXPENSE_CATEGORIES).map(([key, info]) => (
                  <option key={key} value={key}>
                    {info.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <fieldset className="expense-field">
            <legend className="expense-field__label">For</legend>
            <div className="expense-pills">
              {children.map((child) => {
                const on = state.childIds.includes(child.id);
                return (
                  <button
                    key={child.id}
                    type="button"
                    aria-pressed={on}
                    className={`expense-pill${on ? ' is-on' : ''}`}
                    onClick={() =>
                      set('childIds', on ? state.childIds.filter((id) => id !== child.id) : [...state.childIds, child.id])
                    }
                  >
                    {on && <Check size={15} aria-hidden="true" />}
                    {child.fullName.split(/\s+/)[0]}
                  </button>
                );
              })}
            </div>
          </fieldset>

          {!source && (
            <fieldset className="expense-field">
              <legend className="expense-field__label">Has it been paid?</legend>
              <div className="expense-seg expense-seg--timing">
                <button
                  type="button"
                  aria-pressed={state.timing === 'paid'}
                  className={`expense-seg__option${state.timing === 'paid' ? ' is-on' : ''}`}
                  onClick={() => chooseTiming('paid')}
                >
                  <Check size={16} aria-hidden="true" /> Already paid
                </button>
                <button
                  type="button"
                  aria-pressed={state.timing === 'upcoming'}
                  className={`expense-seg__option${state.timing === 'upcoming' ? ' is-on' : ''}`}
                  onClick={() => chooseTiming('upcoming')}
                >
                  <Calendar size={16} aria-hidden="true" /> Coming up
                </button>
              </div>
            </fieldset>
          )}
        </div>
        <div className="cp-form-col">
          <fieldset className="expense-field">
            <legend className="expense-field__label">{state.timing === 'paid' ? 'Who paid?' : 'Who will pay?'}</legend>
            <div className="expense-seg">
              {payerButton('me', `You (${firstName(me, 'You')})`, me)}
              {payerButton('them', otherName, other)}
              {state.timing === 'upcoming' && payerButton('undecided', 'Not decided')}
            </div>
          </fieldset>
          <label className="expense-field">
            <span className="expense-field__label">{state.timing === 'paid' ? 'Paid on' : 'Due by'}</span>
            <input
              className="expense-input"
              type="date"
              value={state.date}
              max={state.timing === 'paid' ? today() : undefined}
              onChange={(event) => set('date', event.target.value)}
            />
            {errors.date && <small className="expense-field__error">{errors.date}</small>}
          </label>

          <fieldset className="expense-field">
            <legend className="expense-field__label">How should it be shared?</legend>
            <div className="expense-pills">
              {shareButton('equal', '50 / 50')}
              {shareButton('me', 'You cover it')}
              {shareButton('them', `${otherName} covers it`)}
              {shareButton('custom', 'Custom')}
            </div>
            {state.shareMode === 'custom' && (
              <div className="expense-custom">
                <span>You</span>
                <input
                  type="range"
                  min={0}
                  max={100}
                  step={5}
                  value={state.myPercent}
                  aria-label="Your percentage of the cost"
                  onChange={(event) => set('myPercent', Number(event.target.value))}
                />
                <span className="expense-custom__value">
                  You {state.myPercent}% · {otherName} {100 - state.myPercent}%
                </span>
              </div>
            )}
          </fieldset>

          <div className={`expense-summary expense-summary--${summary.tone}`} aria-live="polite" data-testid="expense-summary">
            <span className="expense-summary__icon">
              <SummaryIcon size={18} aria-hidden="true" />
            </span>
            <div>
              <p className="expense-summary__lead">{summary.lead}</p>
              {summary.outcome && <p className="expense-summary__outcome">{summary.outcome}</p>}
            </div>
          </div>
        </div>
      </div>

      <div className="cp-form-cols">
        <div className="expense-field">
          <span className="expense-field__label">
            Receipts <span className="expense-field__hint">· photos or PDFs, up to {MAX_RECEIPTS}</span>
          </span>
          <div className="expense-receipts">
            {files.map((pending, index) => (
              <PendingReceiptThumb
                key={`${pending.file.name}-${index}`}
                file={pending.file}
                previewUrl={pending.previewUrl}
                onRemove={() => removeFile(index)}
              />
            ))}
            {existingReceipts + files.length < MAX_RECEIPTS && (
              <label className="expense-add-receipt">
                <Camera size={18} aria-hidden="true" />
                Add photo or PDF
                <input
                  type="file"
                  accept="image/jpeg,image/png,image/webp,application/pdf"
                  multiple
                  className="sr-only"
                  onChange={(event) => {
                    addFiles(event.target.files);
                    event.target.value = '';
                  }}
                />
              </label>
            )}
          </div>
        </div>

        <label className="expense-field">
          <span className="expense-field__label">
            Notes <span className="expense-field__hint">· optional</span>
          </span>
          <textarea
            className="expense-input expense-textarea"
            value={state.notes}
            maxLength={1000}
            placeholder={`Anything ${otherName} should know`}
            onChange={(event) => set('notes', event.target.value)}
          />
        </label>
      </div>

      <p className="expense-notice">
        <Mail size={16} aria-hidden="true" />
        {other.status === 'invited'
          ? `${otherName} hasn't joined yet, so nothing is sent now. They can agree to it once they accept your invitation.`
          : `${otherName} will be asked to agree, and emailed a link.`}
      </p>
    </ExpenseDrawer>
  );
}
