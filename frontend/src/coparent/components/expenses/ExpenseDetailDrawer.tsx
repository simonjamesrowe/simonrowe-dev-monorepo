import { AlertCircle, Check, History, MessageSquare, Pencil, Plus, Trash2, Wallet } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { prepareSchoolNoteImage } from '../../../pages/admin/schoolNoteImage';
import {
  useDeleteExpense,
  useDeleteReceipt,
  useExpenseTransition,
  useMarkExpensePaid,
  useUploadReceipt,
  type ExpenseTransition,
} from '../../hooks/api';
import type { Child, Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { Expense, ExpenseHistoryEntry, ExpenseReceipt } from '../../types/expenses';
import { useToast } from '../ui/ToastProvider';

import { EXPENSE_CATEGORIES } from './categories';
import { CategoryTile } from './CategoryTile';
import { ExpenseDrawer } from './ExpenseDrawer';
import { canDelete, canEdit, expenseStatus } from './expenseStatus';
import { formatMoney, parsePounds, penceToPounds } from './money';
import { ParentDot } from './ParentDot';
import { firstName, parentTone } from './parentTone';
import { ReceiptLightbox, StoredReceiptThumb } from './ReceiptThumb';
import { StatusChip } from './StatusChip';

type Mode = null | 'dispute' | 'claim' | 'markPaid' | 'reject';

const longDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
const stamp = (iso: string) =>
  new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

const HISTORY: Record<string, string> = {
  create: 'added it',
  update: 'changed it',
  agree: 'agreed',
  dispute: 'disputed it',
  mark_paid: 'marked it as paid',
  claim: 'marked it as paid back',
  confirm: 'confirmed the money arrived',
  reject_claim: "said the money hasn't arrived",
  mark_reimbursed: 'marked it as reimbursed',
  delete: 'removed it',
  receipt_add: 'added a receipt',
  receipt_remove: 'removed a receipt',
};

function historyText(entry: ExpenseHistoryEntry): string {
  const text = HISTORY[entry.action] ?? entry.action;
  return entry.note ? `${text}: “${entry.note}”` : text;
}

export interface ExpenseDetailDrawerProps {
  familyId: string;
  me: Parent;
  other: Parent;
  children: Child[];
  /** The expense to show, or null when closed. Kept live from the list query. */
  expense: Expense | null;
  /** Open with one of the inline steps already showing, as the row buttons do. */
  initialMode?: Mode;
  onClose: () => void;
  onEdit: (expense: Expense) => void;
  onDiscuss: (expense: Expense) => void;
}

export function ExpenseDetailDrawer({
  familyId,
  me,
  other,
  children,
  expense,
  initialMode = null,
  onClose,
  onEdit,
  onDiscuss,
}: ExpenseDetailDrawerProps) {
  const { showToast } = useToast();
  const [mode, setMode] = useState<Mode>(initialMode);
  const [note, setNote] = useState('');
  const [paidPayer, setPaidPayer] = useState(me.id);
  const [paidAmount, setPaidAmount] = useState('');
  const [paidOn, setPaidOn] = useState(new Date().toISOString().slice(0, 10));
  const [error, setError] = useState<unknown>(null);
  const [lightbox, setLightbox] = useState<{ url: string; receipt: ExpenseReceipt } | null>(null);
  // The note field a parent just asked for gets the focus, without autoFocus on every render.
  const noteField = useRef<HTMLElement | null>(null);
  const transition = useExpenseTransition();
  const markPaid = useMarkExpensePaid();
  const remove = useDeleteExpense();
  const uploadReceipt = useUploadReceipt();
  const deleteReceipt = useDeleteReceipt();
  const busy =
    transition.isPending || markPaid.isPending || remove.isPending || uploadReceipt.isPending || deleteReceipt.isPending;

  useEffect(() => {
    setMode(initialMode);
    setNote('');
    setError(null);
    setLightbox(null);
    if (expense) {
      setPaidPayer(expense.payerId ?? me.id);
      setPaidAmount(penceToPounds(expense.amountPence));
    }
    // Only when a different expense is opened, not on every refetch of the same one.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expense?.id, initialMode]);

  useEffect(() => {
    if (mode === 'dispute' || mode === 'reject' || mode === 'claim') noteField.current?.focus();
  }, [mode]);

  if (!expense) {
    return <ExpenseDrawer open={false} title="Expense" description="" onClose={onClose}>{null}</ExpenseDrawer>;
  }

  const otherName = firstName(other);
  const nameOf = (id: string | null) => (id === me.id ? 'You' : id === other.id ? otherName : 'Someone');
  const status = expenseStatus(expense, me.id, otherName);
  const category = EXPENSE_CATEGORIES[expense.category];
  const childNames = expense.childIds
    .map((id) => children.find((child) => child.id === id)?.fullName.split(/\s+/)[0])
    .filter(Boolean)
    .join(', ');
  const payer = expense.payerId === me.id ? me : expense.payerId === other.id ? other : undefined;
  // Withdrawing is taking back your own unagreed request; anything else is deleting.
  const withdrawing = expense.agreement.requestedBy === me.id && expense.agreement.status !== 'agreed';

  const run = async (action: () => Promise<unknown>, success: string, description?: string) => {
    setError(null);
    try {
      await action();
      setMode(null);
      setNote('');
      showToast({ variant: 'success', title: success, description });
    } catch (caught) {
      setError(caught);
    }
  };

  const step = (name: ExpenseTransition, success: string, description?: string, withNote?: string) =>
    run(() => transition.mutateAsync({ familyId, expense, transition: name, note: withNote }), success, description);

  const sharesText =
    expense.shares.find((share) => share.parentId === me.id)?.percent === 50
      ? '50 / 50'
      : expense.shares.map((share) => `${nameOf(share.parentId)} ${share.percent}%`).join(' · ');

  const owedLine = (() => {
    if (!expense.payerId) return 'Nobody is down to pay yet';
    if (expense.owedPence === 0) return 'Nothing — one parent covers it';
    const debtorIsMe = expense.debtorParentId === me.id;
    const who = debtorIsMe ? `You owe ${otherName}` : `${otherName} owes you`;
    const suffix =
      expense.timing === 'upcoming'
        ? expense.agreement.status === 'agreed'
          ? ' once paid'
          : ' once agreed and paid'
        : expense.agreement.status !== 'agreed'
          ? ' once agreed'
          : expense.reimbursement.status === 'reimbursed'

            ? ' · settled'
            : expense.reimbursement.status === 'claimed'
              ? ' · paid back, awaiting confirmation'
              : '';
    return `${who} ${formatMoney(expense.owedPence)}${suffix}`;
  })();

  const addReceipt = async (list: FileList | null) => {
    const file = list?.[0];
    if (!file) return;
    let blob: Blob & { name?: string } = file;
    if (file.type !== 'application/pdf') {
      const url = URL.createObjectURL(file);
      try {
        blob = Object.assign(await prepareSchoolNoteImage(url), { name: file.name });
      } finally {
        URL.revokeObjectURL(url);
      }
    }
    await run(() => uploadReceipt.mutateAsync({ familyId, expenseId: expense.id, file: blob }), 'Receipt added', 'Stored privately for your family.');
  };

  const primary: React.ReactNode[] = [];
  const secondary: React.ReactNode[] = [];
  if (mode === 'dispute' || mode === 'reject') {
    primary.push(
      <button key="cancel" type="button" className="cp-button cp-button--secondary" onClick={() => setMode(null)}>
        Cancel
      </button>,
      <button
        key="send"
        type="button"
        className="cp-button cp-button--danger-solid"
        disabled={!note.trim() || busy}
        onClick={() =>
          mode === 'dispute'
            ? step('dispute', 'Dispute sent', `${otherName} can edit it or withdraw it.`, note.trim())
            : step('reimbursement/reject', 'Marked as not received', `${otherName} will see it is still outstanding.`, note.trim())
        }
      >
        {mode === 'dispute' ? 'Send dispute' : "Say it hasn't arrived"}
      </button>,
    );
  } else if (mode === 'claim') {
    primary.push(
      <button key="cancel" type="button" className="cp-button cp-button--secondary" onClick={() => setMode(null)}>
        Cancel
      </button>,
      <button
        key="claim"
        type="button"
        className="cp-button cp-button--primary"
        disabled={busy}
        onClick={() =>
          step('reimbursement/claim', 'Marked as paid back', `${otherName} will be asked to confirm it arrived.`, note.trim() || undefined)
        }
      >
        <Check size={16} aria-hidden="true" /> Mark paid back
      </button>,
    );
  } else if (mode === 'markPaid') {
    const amount = parsePounds(paidAmount);
    primary.push(
      <button key="cancel" type="button" className="cp-button cp-button--secondary" onClick={() => setMode(null)}>
        Cancel
      </button>,
      <button
        key="paid"
        type="button"
        className="cp-button cp-button--primary"
        disabled={!amount || !paidOn || busy}
        onClick={() =>
          amount &&
          run(
            () => markPaid.mutateAsync({ familyId, expense, payerId: paidPayer, paidOn, amountPence: amount }),
            'Marked as paid',
            amount === expense.amountPence ? undefined : `The amount changed, so ${otherName} will be asked to agree again.`,
          )
        }
      >
        <Check size={16} aria-hidden="true" /> Mark as paid
      </button>,
    );
  } else {
    secondary.push(
      <button key="discuss" type="button" className="cp-button cp-button--secondary" onClick={() => onDiscuss(expense)}>
        <MessageSquare size={16} aria-hidden="true" /> Discuss
      </button>,
    );
    if (canEdit(expense) && !status.actions.includes('edit')) {
      secondary.push(
        <button key="edit" type="button" className="cp-button cp-button--quiet" onClick={() => onEdit(expense)}>
          <Pencil size={15} aria-hidden="true" /> Edit
        </button>,
      );
    }
    if (canDelete(expense, me.id)) {
      secondary.push(
        <button
          key="delete"
          type="button"
          className="cp-button cp-button--quiet cp-button--danger"
          disabled={busy}
          onClick={() =>
            run(async () => {
              await remove.mutateAsync({ familyId, id: expense.id, version: expense.version });
              onClose();
            }, withdrawing ? 'Expense withdrawn' : 'Expense deleted')
          }
        >
          <Trash2 size={15} aria-hidden="true" /> {withdrawing ? 'Withdraw' : 'Delete'}
        </button>,
      );
    }
    for (const action of status.actions) {
      if (action === 'dispute') {
        primary.push(
          <button key="dispute" type="button" className="cp-button cp-button--danger" onClick={() => setMode('dispute')}>
            Dispute
          </button>,
        );
      }
      if (action === 'agree') {
        primary.push(
          <button
            key="agree"
            type="button"
            className="cp-button cp-button--primary"
            disabled={busy}
            onClick={() => step('agree', 'Agreed', expense.timing === 'paid' && expense.owedPence > 0 ? 'It now counts towards the balance.' : undefined)}
          >
            <Check size={16} aria-hidden="true" /> Agree
          </button>,
        );
      }
      if (action === 'edit') {
        primary.push(
          <button key="edit" type="button" className="cp-button cp-button--primary" onClick={() => onEdit(expense)}>
            <Pencil size={15} aria-hidden="true" /> Edit and resend
          </button>,
        );
      }
      if (action === 'markPaid') {
        primary.push(
          <button key="markPaid" type="button" className="cp-button cp-button--primary" onClick={() => setMode('markPaid')}>
            Mark as paid
          </button>,
        );
      }
      if (action === 'claim') {
        primary.push(
          <button key="claim" type="button" className="cp-button cp-button--primary" onClick={() => setMode('claim')}>
            Mark paid back
          </button>,
        );
      }
      if (action === 'markReimbursed') {
        primary.push(
          <button
            key="mark"
            type="button"
            className="cp-button cp-button--secondary"
            disabled={busy}
            onClick={() => step('reimbursement/mark', 'Marked as reimbursed', `${expense.title} is settled.`)}
          >
            Mark as reimbursed
          </button>,
        );
      }
      if (action === 'reject') {
        primary.push(
          <button key="reject" type="button" className="cp-button cp-button--secondary" onClick={() => setMode('reject')}>
            Not received yet
          </button>,
        );
      }
      if (action === 'confirm') {
        primary.push(
          <button
            key="confirm"
            type="button"
            className="cp-button cp-button--primary"
            disabled={busy}
            onClick={() => step('reimbursement/confirm', 'Marked as received', `${expense.title} is settled.`)}
          >
            <Check size={16} aria-hidden="true" /> Confirm received
          </button>,
        );
      }
    }
  }

  return (
    <ExpenseDrawer
      open
      title={expense.title}
      description="The expense, who paid, how it is shared and what happened to it."
      onClose={onClose}
      footer={
        <>
          {secondary}
          <span className="expense-grow" />
          {primary}
        </>
      }
    >
      <div className="expense-detail__top">
        <div>
          <p className="expense-detail__amount">{formatMoney(expense.amountPence)}</p>
          <p className="expense-detail__sub">
            <CategoryTile category={expense.category} size="sm" />
            {category.label}
            {childNames ? ` · ${childNames}` : ''}
          </p>
        </div>
        <StatusChip status={status} />
      </div>

      {error !== null && (
        <p role="alert" className="expense-callout expense-callout--rose">
          <AlertCircle size={18} aria-hidden="true" />
          {apiErrorMessage(error, 'That did not work. Try again.')}
        </p>
      )}
      {expense.agreement.status === 'disputed' && (
        <p className="expense-callout expense-callout--rose">
          <AlertCircle size={18} aria-hidden="true" />
          <span>
            <strong>{nameOf(expense.agreement.respondedBy)} disputed this:</strong> “{expense.agreement.note}”
            {expense.agreement.requestedBy === me.id && <><br />Edit it to send a new version, or withdraw it.</>}
          </span>
        </p>
      )}
      {expense.reimbursement.status === 'claimed' && expense.payerId === me.id && (
        <p className="expense-callout expense-callout--amber">
          <Wallet size={18} aria-hidden="true" />
          <span>
            <strong>
              {otherName} says they paid you back {formatMoney(expense.owedPence)}
            </strong>
            {expense.reimbursement.note ? ` · “${expense.reimbursement.note}”` : ''}. Confirm when it arrives.
          </span>
        </p>
      )}

      <dl className="expense-facts">
        <dt>{expense.timing === 'paid' ? 'Paid by' : 'Paying'}</dt>
        <dd>
          <ParentDot tone={payer ? parentTone(payer) : null} />
          <strong>{payer ? (payer.id === me.id ? `You (${firstName(me, 'You')})` : otherName) : 'Not decided yet'}</strong>
          {expense.timing === 'paid' ? ` · ${longDate(expense.date)}` : ` · due ${longDate(expense.date)}`}
        </dd>
        <dt>Shared</dt>
        <dd>{sharesText}</dd>
        <dt>Shares</dt>
        <dd>
          {expense.shares.map((share) => (
            <span key={share.parentId}>
              {nameOf(share.parentId)} <strong>{formatMoney(share.sharePence)}</strong>
            </span>
          ))}
        </dd>
        <dt>Owed</dt>
        <dd className={expense.debtorParentId === me.id && expense.owedPence > 0 ? 'expense-owed expense-owed--owe' : 'expense-owed'}>
          {owedLine}
        </dd>
        <dt>Receipts</dt>
        <dd>
          <div className="expense-receipts">
            {expense.receipts.length === 0 && <span className="expense-muted">None yet</span>}
            {expense.receipts.map((receipt) => (
              <StoredReceiptThumb
                key={receipt.id}
                familyId={familyId}
                expenseId={expense.id}
                receipt={receipt}
                onOpen={(url) => setLightbox({ url, receipt })}
                onRemove={
                  receipt.uploadedBy === me.id && expense.reimbursement.status !== 'reimbursed'
                    ? () => run(() => deleteReceipt.mutateAsync({ familyId, expenseId: expense.id, receiptId: receipt.id }), 'Receipt removed')
                    : undefined
                }
              />
            ))}
            {expense.receipts.length < 5 && (
              <label className="expense-add-receipt expense-add-receipt--small">
                <Plus size={16} aria-hidden="true" /> Add
                <input
                  type="file"
                  accept="image/jpeg,image/png,image/webp,application/pdf"
                  className="sr-only"
                  onChange={(event) => {
                    void addReceipt(event.target.files);
                    event.target.value = '';
                  }}
                />
              </label>
            )}
          </div>
        </dd>
        {expense.notes && (
          <>
            <dt>Notes</dt>
            <dd>{expense.notes}</dd>
          </>
        )}
      </dl>

      {(mode === 'dispute' || mode === 'reject') && (
        <div className="expense-panel expense-panel--rose">
          <label className="expense-field">
            <span className="expense-field__label">
              {mode === 'dispute'
                ? `Why are you disputing it? ${otherName} will see your reason.`
                : `What's missing? ${otherName} will see this.`}
            </span>
            <textarea
              className="expense-input expense-textarea"
              value={note}
              maxLength={500}
              ref={(element) => { noteField.current = element; }}
              onChange={(event) => setNote(event.target.value)}
              placeholder={mode === 'dispute' ? 'e.g. We agreed trainers, not shoes' : 'e.g. Nothing in my account yet'}
            />
          </label>
        </div>
      )}
      {mode === 'claim' && (
        <div className="expense-panel expense-panel--teal">
          <label className="expense-field">
            <span className="expense-field__label">
              Mark {formatMoney(expense.owedPence)} as paid back to {otherName}
            </span>
            <input
              className="expense-input"
              value={note}
              maxLength={500}
              ref={(element) => { noteField.current = element; }}
              onChange={(event) => setNote(event.target.value)}
              placeholder="How? e.g. Bank transfer (optional)"
            />
          </label>
          <p className="expense-muted">{otherName} will be asked to confirm they received it.</p>
        </div>
      )}
      {mode === 'markPaid' && (
        <div className="expense-panel expense-panel--teal">
          <p className="expense-panel__title">Mark as paid</p>
          <div className="expense-seg">
            {[me, other].map((parent) => (
              <button
                key={parent.id}
                type="button"
                aria-pressed={paidPayer === parent.id}
                className={`expense-seg__option${paidPayer === parent.id ? ` is-on is-${parentTone(parent)}` : ''}`}
                onClick={() => setPaidPayer(parent.id)}
              >
                <ParentDot tone={parentTone(parent)} />
                {parent.id === me.id ? 'You' : otherName} paid
              </button>
            ))}
          </div>
          <div className="expense-grid2">
            <label className="expense-field">
              <span className="expense-field__label">Amount actually paid</span>
              <span className="expense-money-input">
                <span aria-hidden="true">£</span>
                <input className="expense-input" inputMode="decimal" value={paidAmount} onChange={(event) => setPaidAmount(event.target.value)} />
              </span>
            </label>
            <label className="expense-field">
              <span className="expense-field__label">Paid on</span>
              <input
                className="expense-input"
                type="date"
                value={paidOn}
                max={new Date().toISOString().slice(0, 10)}
                onChange={(event) => setPaidOn(event.target.value)}
              />
            </label>
          </div>
          <p className="expense-muted">
            The same amount, paid by the agreed payer, keeps the agreement. Anything else asks {otherName} to agree again.
          </p>
        </div>
      )}

      <section>
        <p className="expense-section-title">
          <History size={16} aria-hidden="true" /> History
        </p>
        <ul className="expense-timeline">
          {[...expense.history].reverse().map((entry, index) => (
            <li key={`${entry.at}-${index}`}>
              <time dateTime={entry.at}>{stamp(entry.at)}</time>
              <span className={entry.action === 'dispute' ? 'expense-timeline__dispute' : undefined}>
                <strong>{nameOf(entry.by)}</strong> {historyText(entry)}
              </span>
            </li>
          ))}
        </ul>
      </section>

      {lightbox && <ReceiptLightbox receipt={lightbox.receipt} url={lightbox.url} onClose={() => setLightbox(null)} />}
    </ExpenseDrawer>
  );
}
