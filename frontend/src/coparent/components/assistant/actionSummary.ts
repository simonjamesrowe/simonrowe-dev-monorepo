// What a Quick add card will do, in one line the parent can read without opening it, e.g.
// "Add expense" + "School trip · £45.00 · you paid, Rhian owes you £22.50". Ids are named from
// the same options the card's editor offers, and a name that cannot be found is left out rather
// than shown as an id.

import type { AssistantAction } from '../../types/assistant';
import { describeEvent, describeWhen, describeRepeat, formatDay, joinNames } from '../calendar/eventSummary';
import { formatMoney, parsePounds, previewShares, type PayerChoice } from '../expenses/money';

import type { AssistantEditorOptions } from './AssistantActionCard';

export const ACTION_LABELS: Record<string, string> = {
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

/** An event the card may point at, so a target can be named with its child and date. */
export interface SummaryEvent {
  title: string;
  startDate?: string;
  childIds?: string[];
}

export interface ActionSummaryContext {
  options?: AssistantEditorOptions;
  /** The signed-in parent, who is "you" rather than their own name. */
  meId?: string;
  /** The other parent's first name. */
  otherName?: string;
  events?: Record<string, SummaryEvent>;
}

export interface ActionSummary {
  label: string;
  /** What exactly it does, or null when the proposal holds nothing worth saying yet. */
  detail: string | null;
}

const MESSAGE_LENGTH = 60;

/** Cuts long text at a word where it can, so a pasted paragraph stays one line. */
export function truncate(text: string, max = MESSAGE_LENGTH): string {
  const clean = text.replace(/\s+/g, ' ').trim();
  if (clean.length <= max) return clean;
  const cut = clean.slice(0, max - 1);
  const space = cut.lastIndexOf(' ');
  return `${(space > max / 2 ? cut.slice(0, space) : cut).trimEnd()}…`;
}

const quote = (text: string) => `“${truncate(text)}”`;

const text = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() ? value.trim() : null;

const ids = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];

const first = (name: string) => name.trim().split(/\s+/)[0];

function optionLabel(context: ActionSummaryContext, keys: string[], id: string | null) {
  if (!id) return null;
  for (const key of keys) {
    const label = context.options?.[key]?.find((option) => option.value === id)?.label;
    if (label) return label;
  }
  return null;
}

function childNames(context: ActionSummaryContext, childIds: string[]): string[] {
  return childIds
    .map((id) => optionLabel(context, ['childIds', 'childId'], id))
    .filter((label): label is string => label !== null)
    .map(first);
}

/** "you" for the signed-in parent, otherwise a first name, or null when nobody can be named. */
function personName(context: ActionSummaryContext, id: string | null, keys: string[]) {
  if (!id) return null;
  if (id === context.meId) return 'you';
  const label = optionLabel(context, keys, id);
  return label ? first(label) : null;
}

/** The event an action points at: "Ava — Swimming (Mon 12 Oct)", or its hint when unmatched. */
function eventName(context: ActionSummaryContext, payload: Record<string, unknown>, key: string) {
  const id = text(payload[key]);
  const known = id ? context.events?.[id] : undefined;
  if (known) {
    const names = joinNames(childNames(context, known.childIds ?? []));
    const what = names ? `${names} — ${known.title}` : known.title;
    return known.startDate ? `${what} (${formatDay(known.startDate)})` : what;
  }
  const label = optionLabel(context, [key, 'eventId'], id);
  if (label) return label;
  const hint = text(payload.targetHint);
  return hint ? `${hint} (choose which)` : null;
}

function targetName(context: ActionSummaryContext, payload: Record<string, unknown>, key: string) {
  const label = optionLabel(context, [key], text(payload[key]));
  if (label) return label;
  const hint = text(payload.targetHint);
  return hint ? `${hint} (choose which)` : null;
}

const join = (...parts: Array<string | null | undefined | false>) =>
  parts.filter((part): part is string => Boolean(part)).join(' · ') || null;

function eventDetail(context: ActionSummaryContext, payload: Record<string, unknown>) {
  return describeEvent({
    title: text(payload.title),
    childNames: childNames(context, ids(payload.childIds)),
    startDate: text(payload.startDate),
    endDate: text(payload.endDate),
    startTime: text(payload.startTime),
    endTime: text(payload.endTime),
    allDay: payload.allDay === true,
    frequency: text(payload.recurringFrequency),
    days: ids(payload.recurringDays),
  }) || null;
}

/** An update names only what it changes; the proposal leaves everything else null. */
function eventChanges(context: ActionSummaryContext, payload: Record<string, unknown>) {
  const title = text(payload.title);
  const when = describeWhen({
    startDate: text(payload.startDate),
    endDate: text(payload.endDate),
    startTime: text(payload.startTime),
    endTime: text(payload.endTime),
    allDay: payload.allDay === true,
    frequency: text(payload.recurringFrequency),
  });
  const time = !when && text(payload.startTime)
    ? `${payload.startTime}${text(payload.endTime) ? `–${payload.endTime}` : ''}`
    : null;
  const names = childNames(context, ids(payload.childIds));
  const location = text(payload.location);
  return [
    title && `rename to ${quote(title)}`,
    (when || time) && `move to ${when ?? time}`,
    describeRepeat(text(payload.recurringFrequency), ids(payload.recurringDays)),
    names.length > 0 && `for ${joinNames(names)}`,
    location && `at ${location}`,
    text(payload.notes) && 'new notes',
  ].filter(Boolean).join(', ');
}

function amountText(payload: Record<string, unknown>) {
  const raw = text(payload.amount);
  if (!raw) return null;
  const currency = text(payload.currency);
  // A note that gave dollars stays in dollars until the parent fixes it; never call it pounds.
  if (currency && currency !== 'GBP') return `${raw} ${currency} (needs pounds)`;
  const pence = parsePounds(raw);
  return pence ? formatMoney(pence) : raw;
}

/** "you paid, Rhian owes you £22.50" and the other ways round. */
function expenseOutcome(context: ActionSummaryContext, payload: Record<string, unknown>) {
  const foreign = text(payload.currency) !== null && payload.currency !== 'GBP';
  const pence = foreign ? null : parsePounds(text(payload.amount) ?? '');
  const payerId = text(payload.payerId);
  const other = context.otherName ?? 'your co-parent';
  const upcoming = payload.timing === 'upcoming';
  const date = text(payload.date);
  const due = upcoming ? (date ? `due ${formatDay(date)}` : 'coming up') : null;
  if (!context.meId) {
    const payer = personName(context, payerId, ['payerId', 'parentIds']);
    return join(due, payer && `${upcoming ? 'paying' : 'paid by'} ${payer}`);
  }
  const payer: PayerChoice = payerId === null ? 'undecided' : payerId === context.meId ? 'me' : 'them';
  const myPercent = typeof payload.sharePercent === 'number' ? payload.sharePercent : 50;
  const owed = pence ? previewShares(pence, myPercent, payer).owedToMe : 0;
  if (!upcoming) {
    if (payer === 'undecided') return 'choose who paid';
    if (!pence) return payer === 'me' ? 'you paid' : `${other} paid`;
    if (payer === 'me') return owed > 0 ? `you paid, ${other} owes you ${formatMoney(owed)}` : 'you paid, nothing owed';
    return owed < 0 ? `${other} paid, you owe ${other} ${formatMoney(-owed)}` : `${other} paid, nothing owed`;
  }
  if (payer === 'undecided') return `${due}, nobody down to pay yet`;
  if (payer === 'me') {
    return `${due}, you'll pay${owed > 0 ? `, ${other} will owe you ${formatMoney(owed)}` : ''}`;
  }
  return `${due}, ${other} will pay${owed < 0 ? `, you'll owe ${formatMoney(-owed)}` : ''}`;
}

const SCHEDULE_CHANGES: Record<string, string> = {
  swap: 'swap',
  extend: 'change',
  add: 'add',
  remove: 'cancel',
};

function scheduleChange(context: ActionSummaryContext, payload: Record<string, unknown>) {
  const kind = SCHEDULE_CHANGES[text(payload.type)?.toLowerCase() ?? ''] ?? 'change';
  const range = (start: string | null, end: string | null) => {
    if (!start) return null;
    return end && end.slice(0, 10) !== start.slice(0, 10)
      ? `${formatDay(start)} – ${formatDay(end)}`
      : formatDay(start);
  };
  const from = range(text(payload.originalStartDate), text(payload.originalEndDate));
  const to = range(text(payload.newStartDate), text(payload.newEndDate));
  const change = kind === 'cancel'
    ? `cancel ${from ?? to ?? 'a date'}`
    : from && to && from !== to
      ? `${kind} ${from} → ${to}`
      : `${kind} to ${to ?? 'new dates'}`;
  return join(eventName(context, payload, 'originalEventId'), change);
}

/** The summary for one proposed action. Never throws on a payload missing fields. */
export function summarizeAction(action: AssistantAction, context: ActionSummaryContext = {}): ActionSummary {
  const payload = action.payload ?? {};
  const label = ACTION_LABELS[action.actionType] ?? 'Proposed action';
  const other = context.otherName;
  const message = text(payload.message);

  switch (action.actionType) {
    case 'CREATE_EVENT':
      return { label, detail: eventDetail(context, payload) };
    case 'UPDATE_EVENT': {
      const changes = eventChanges(context, payload);
      return { label, detail: join(eventName(context, payload, 'eventId'), changes || 'no changes yet') };
    }
    case 'DELETE_EVENT':
      return { label, detail: eventName(context, payload, 'eventId') };
    case 'CREATE_CATEGORY':
      return { label, detail: text(payload.name) };
    case 'UPDATE_CATEGORY': {
      const name = text(payload.name);
      return { label, detail: join(targetName(context, payload, 'categoryId'), name && `rename to ${quote(name)}`) };
    }
    case 'DELETE_CATEGORY':
      return { label, detail: targetName(context, payload, 'categoryId') };
    case 'CREATE_SCHEDULE_CHANGE':
      return { label, detail: scheduleChange(context, payload) };
    case 'WITHDRAW_SCHEDULE_CHANGE': {
      const request = targetName(context, payload, 'requestId');
      return { label, detail: request && quote(request) };
    }
    case 'START_MESSAGE_CONVERSATION': {
      const to = personName(context, text(payload.recipientId), ['recipientId', 'parentIds']) ?? other;
      const subject = text(payload.subject);
      return {
        label,
        detail: join(to && `with ${to}`, subject && quote(subject), message && quote(message)),
      };
    }
    case 'SEND_MESSAGE': {
      const conversation = targetName(context, payload, 'conversationId');
      const to = other ? `to ${other}` : null;
      const body = message ? `${to ? `${to}: ` : ''}${quote(message)}` : to;
      return { label, detail: join(body, conversation && `in ${conversation}`) };
    }
    case 'CREATE_PERMISSION_REQUEST': {
      const child = optionLabel(context, ['childId', 'childIds'], text(payload.childId));
      const type = text(payload.type)?.toLowerCase();
      const description = text(payload.description);
      const about = [child && first(child), type && `(${type})`].filter(Boolean).join(' ');
      return {
        label,
        detail: join(other && `ask ${other}`, about || null, description && quote(description)),
      };
    }
    case 'CREATE_EXPENSE':
      return {
        label,
        detail: join(text(payload.title), amountText(payload), expenseOutcome(context, payload)),
      };
    case 'MARK_EXPENSE_PAID': {
      const payer = personName(context, text(payload.payerId), ['payerId', 'parentIds']);
      const paidOn = text(payload.paidOn);
      return {
        label,
        detail: join(
          targetName(context, payload, 'expenseId'),
          payer && `${payer} paid${paidOn ? ` on ${formatDay(paidOn)}` : ''}`,
        ),
      };
    }
    case 'CLAIM_EXPENSE_REIMBURSEMENT':
      return {
        label,
        detail: join(targetName(context, payload, 'expenseId'), other ? `you paid ${other} back` : 'you paid it back'),
      };
    default:
      return { label, detail: null };
  }
}
