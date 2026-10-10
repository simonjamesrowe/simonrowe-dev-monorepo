import { describe, expect, it } from 'vitest';

import type { AssistantAction } from '../../types/assistant';

import {
  editablePayload,
  fieldControl,
  normalizedPayload,
  toggleChoice,
  WEEKDAYS,
} from './fieldControls';

const action = (actionType: AssistantAction['actionType']) => ({ actionType }) as AssistantAction;
const event = action('CREATE_EVENT');
const options = (count: number) => Array.from({ length: count }, (_, index) => ({
  value: `id-${index}`, label: `Option ${index}`,
}));

describe('fieldControl', () => {
  it('uses pickers only for values already in the shape the server reads', () => {
    expect(fieldControl(event, 'startDate', '2026-10-13', {}).kind).toBe('date');
    expect(fieldControl(event, 'endDate', null, {}).kind).toBe('date');
    expect(fieldControl(event, 'startDate', '2026-10-13T09:00:00Z', {}).kind).toBe('text');
    expect(fieldControl(event, 'startTime', '09:30', {}).kind).toBe('time');
    expect(fieldControl(event, 'startTime', '9.30am', {}).kind).toBe('text');
  });

  it('offers chips for a few choices and a select for many', () => {
    expect(fieldControl(event, 'parentId', null, { parentId: options(2) })).toEqual({
      kind: 'radio',
      choices: [{ value: '', label: 'Not set' }, ...options(2)],
    });
    expect(fieldControl(action('MARK_EXPENSE_PAID'), 'payerId', null, { payerId: options(2) },
      new Set(['payerId']))).toEqual({ kind: 'radio', choices: options(2) });
    expect(fieldControl(event, 'eventId', 'id-1', { eventId: options(9) }, new Set(['eventId'])))
      .toEqual({ kind: 'select', choices: options(9), placeholder: false });
    expect(fieldControl(event, 'childIds', [], { childIds: options(9) }).kind).toBe('checkboxes');
  });

  it('knows which kind of type each action has', () => {
    expect(fieldControl(event, 'type', 'school', {}).kind).toBe('eventType');
    expect(fieldControl(action('CREATE_SCHEDULE_CHANGE'), 'type', 'swap', {}).kind).toBe('radio');
    expect(fieldControl(action('CREATE_PERMISSION_REQUEST'), 'type', null, {}).kind).toBe('radio');
  });

  it('calls a blank "No change" on an update, where blank keeps what is there', () => {
    const update = action('UPDATE_EVENT');
    expect(fieldControl(update, 'recurringFrequency', null, {})).toEqual({
      kind: 'radio',
      choices: [
        { value: '', label: 'No change' },
        { value: 'daily', label: 'Every day' },
        { value: 'weekly', label: 'Every week' },
      ],
    });
    expect(fieldControl(update, 'parentId', null, { parentId: options(1) })).toEqual({
      kind: 'radio', choices: [{ value: '', label: 'No change' }, ...options(1)],
    });
  });

  it('keeps a text box for a repeat it cannot show as chips', () => {
    expect(fieldControl(event, 'recurringFrequency', 'monthly', {}).kind).toBe('text');
    expect(fieldControl(event, 'recurringDays', ['tuesday'], {})).toEqual({
      kind: 'checkboxes', choices: WEEKDAYS, short: true,
    });
    expect(fieldControl(event, 'recurringDays', ['tues'], {}).kind).toBe('text');
  });

  it('shows a currency that is not pounds beside GBP', () => {
    expect(fieldControl(action('CREATE_EXPENSE'), 'currency', 'USD', {})).toEqual({
      kind: 'select',
      choices: [{ value: 'GBP', label: 'GBP (£)' }, { value: 'USD', label: 'USD' }],
      placeholder: false,
    });
  });
});

describe('editablePayload and normalizedPayload', () => {
  it('round-trip every value unchanged', () => {
    const payload = {
      title: 'Swimming', childIds: ['a'], recurringDays: ['tuesday'], allDay: null, sharePercent: 50,
      currency: 'GBP', notes: null,
    };
    const controls = Object.fromEntries(Object.entries(payload).map(([key, value]) => [
      key, fieldControl(action('CREATE_EVENT'), key, value, { childIds: options(2) }),
    ]));
    expect(normalizedPayload(editablePayload(payload, controls))).toEqual(payload);
  });

  it('starts an empty currency at GBP and joins only lists shown as text', () => {
    expect(editablePayload({ currency: null, recurringDays: ['tues'] }, {
      currency: { kind: 'select', choices: [], placeholder: false },
      recurringDays: { kind: 'text' },
    })).toEqual({ currency: 'GBP', recurringDays: 'tues' });
  });
});

describe('toggleChoice', () => {
  it('keeps the list order, and any value the list does not know', () => {
    expect(toggleChoice(['thursday'], 'monday', WEEKDAYS)).toEqual(['monday', 'thursday']);
    expect(toggleChoice(['monday', 'thursday'], 'monday', WEEKDAYS)).toEqual(['thursday']);
    expect(toggleChoice(['gone'], 'id-0', options(2))).toEqual(['id-0', 'gone']);
    expect(toggleChoice(null, 'id-1', options(2))).toEqual(['id-1']);
  });
});
