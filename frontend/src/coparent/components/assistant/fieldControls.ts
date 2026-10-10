// Which control each Quick add field gets. The payload goes back to the server in the shape it
// came in: dates stay YYYY-MM-DD, times HH:mm, weekdays lowercase names and ids plain strings.
// A value already in some other shape, or a field nobody has described here, keeps a text box,
// so nothing is reformatted behind the parent's back.

import type { AssistantAction } from '../../types/assistant';
import { DEFAULT_EVENT_TYPES } from '../calendar/eventTypeColors';

export interface AssistantEditorOption {
  value: string;
  label: string;
}

export type AssistantEditorOptions = Partial<Record<string, AssistantEditorOption[]>>;

export type FieldControl =
  | { kind: 'text' }
  | { kind: 'long' }
  | { kind: 'boolean' }
  | { kind: 'date' }
  | { kind: 'time' }
  | { kind: 'percent' }
  /** One of a few: radio chips. A choice whose value is '' clears the field. */
  | { kind: 'radio'; choices: AssistantEditorOption[] }
  /** One of many: a select. */
  | { kind: 'select'; choices: AssistantEditorOption[]; placeholder: boolean }
  /** Any of: checkbox chips, stored as an array in the order the choices are listed. */
  | { kind: 'checkboxes'; choices: AssistantEditorOption[]; short?: boolean }
  /** A known type, or the parent's own words. */
  | { kind: 'eventType'; choices: AssistantEditorOption[] };

/** Up to this many choices show as chips; more become a select. */
const CHIP_LIMIT = 4;

const DATE_FIELDS = new Set([
  'startDate', 'endDate', 'date', 'paidOn',
  'originalStartDate', 'originalEndDate', 'newStartDate', 'newEndDate',
]);
const TIME_FIELDS = new Set(['startTime', 'endTime']);
const LONG_TEXT = new Set(['message', 'notes', 'note', 'description', 'reason']);
const EVENT_ACTIONS = new Set(['CREATE_EVENT', 'UPDATE_EVENT']);

const YMD = /^\d{4}-\d{2}-\d{2}$/;
const HM = /^\d{2}:\d{2}$/;

export const WEEKDAYS: AssistantEditorOption[] = [
  { value: 'monday', label: 'Mon' },
  { value: 'tuesday', label: 'Tue' },
  { value: 'wednesday', label: 'Wed' },
  { value: 'thursday', label: 'Thu' },
  { value: 'friday', label: 'Fri' },
  { value: 'saturday', label: 'Sat' },
  { value: 'sunday', label: 'Sun' },
];

const REPEATS: AssistantEditorOption[] = [
  { value: '', label: 'Does not repeat' },
  { value: 'daily', label: 'Every day' },
  { value: 'weekly', label: 'Every week' },
];

/** The same words the schedule change dialog uses. */
const SCHEDULE_TYPES: AssistantEditorOption[] = [
  { value: 'swap', label: 'Swap days' },
  { value: 'extend', label: 'Change dates' },
  { value: 'add', label: 'Add time' },
  { value: 'remove', label: 'Cancel date' },
];

const PERMISSION_TYPES: AssistantEditorOption[] = [
  { value: 'schedule', label: 'Schedule' },
  { value: 'travel', label: 'Travel' },
  { value: 'medical', label: 'Medical' },
  { value: 'extracurricular', label: 'Extracurricular' },
];

/** What clearing an optional single choice means, in the parent's words. */
const NONE_LABELS: Record<string, string> = {
  payerId: 'Not decided yet',
};

const isEmpty = (value: unknown) => value == null || value === '';

/** The default event types, then the family's own categories that are not one of them. */
export function eventTypeChoices(options: AssistantEditorOptions): AssistantEditorOption[] {
  const choices = DEFAULT_EVENT_TYPES.map(({ value, label }) => ({ value, label }));
  const seen = new Set(choices.map((choice) => choice.value.toLowerCase()));
  for (const category of options.eventType ?? []) {
    const key = category.value.toLowerCase();
    if (!seen.has(key)) {
      seen.add(key);
      choices.push(category);
    }
  }
  return choices;
}

/** On an update a blank field keeps what is there, so clearing it is "No change". */
function noneLabel(action: AssistantAction, key: string) {
  return action.actionType === 'UPDATE_EVENT' ? 'No change' : NONE_LABELS[key] ?? 'Not set';
}

function idChoices(
  action: AssistantAction,
  key: string,
  value: unknown,
  choices: AssistantEditorOption[],
  required: Set<string>,
): FieldControl {
  if (key.endsWith('Ids')) return { kind: 'checkboxes', choices };
  if (choices.length > CHIP_LIMIT) {
    return { kind: 'select', choices, placeholder: !required.has(key) || isEmpty(value) };
  }
  return {
    kind: 'radio',
    choices: required.has(key)
      ? choices
      : [{ value: '', label: noneLabel(action, key) }, ...choices],
  };
}

/** The control for one field of one action. */
export function fieldControl(
  action: AssistantAction,
  key: string,
  value: unknown,
  options: AssistantEditorOptions,
  required: Set<string> = new Set(),
): FieldControl {
  if (key === 'currency') {
    // Pounds only, but a note that gave dollars keeps showing dollars until the parent changes it.
    const choices = [...(options.currency ?? [{ value: 'GBP', label: 'GBP (£)' }])];
    if (typeof value === 'string' && value && !choices.some((choice) => choice.value === value)) {
      choices.push({ value, label: value });
    }
    return { kind: 'select', choices, placeholder: false };
  }
  const choices = options[key];
  if (choices) return idChoices(action, key, value, choices, required);
  if (DATE_FIELDS.has(key) && (isEmpty(value) || (typeof value === 'string' && YMD.test(value)))) {
    return { kind: 'date' };
  }
  if (TIME_FIELDS.has(key) && (isEmpty(value) || (typeof value === 'string' && HM.test(value)))) {
    return { kind: 'time' };
  }
  if (key === 'type') {
    if (EVENT_ACTIONS.has(action.actionType)) {
      return { kind: 'eventType', choices: eventTypeChoices(options) };
    }
    if (action.actionType === 'CREATE_SCHEDULE_CHANGE') return { kind: 'radio', choices: SCHEDULE_TYPES };
    if (action.actionType === 'CREATE_PERMISSION_REQUEST') {
      return { kind: 'radio', choices: PERMISSION_TYPES };
    }
  }
  if (key === 'recurringFrequency'
    && (isEmpty(value) || REPEATS.some((choice) => choice.value === value))) {
    return {
      kind: 'radio',
      choices: action.actionType === 'UPDATE_EVENT'
        ? [{ value: '', label: 'No change' }, ...REPEATS.slice(1)]
        : REPEATS,
    };
  }
  if (key === 'recurringDays' && (value == null || (Array.isArray(value)
    && value.every((day) => WEEKDAYS.some((weekday) => weekday.value === day))))) {
    return { kind: 'checkboxes', choices: WEEKDAYS, short: true };
  }
  if (key === 'sharePercent') return { kind: 'percent' };
  if (key === 'allDay' || typeof value === 'boolean') return { kind: 'boolean' };
  if (LONG_TEXT.has(key)) return { kind: 'long' };
  return { kind: 'text' };
}

/**
 * The form's starting values. Only a list shown in a text box is joined into text; chips keep
 * the array. An empty currency starts at GBP, the only one an expense can be in.
 */
export function editablePayload(
  payload: Record<string, unknown>,
  controls: Record<string, FieldControl>,
) {
  return Object.fromEntries(
    Object.entries(payload).map(([key, value]) => {
      if (key === 'currency' && isEmpty(value)) return [key, 'GBP'];
      return [key, Array.isArray(value) && controls[key]?.kind === 'text' ? value.join(', ') : value];
    }),
  );
}

/** Back to the server's shape: lists typed as text become arrays again, and blanks become null. */
export function normalizedPayload(payload: Record<string, unknown>) {
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

/** Toggles one choice, keeping the choices' order and any value the list does not know. */
export function toggleChoice(current: unknown, value: string, choices: AssistantEditorOption[]) {
  const selected = new Set(Array.isArray(current) ? current as string[] : []);
  if (selected.has(value)) selected.delete(value);
  else selected.add(value);
  const known = new Set(choices.map((choice) => choice.value));
  return [
    ...choices.map((choice) => choice.value).filter((choice) => selected.has(choice)),
    ...[...selected].filter((choice) => !known.has(choice)),
  ];
}
