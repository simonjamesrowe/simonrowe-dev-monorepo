import { describe, expect, it } from 'vitest';

import type { AssistantAction, AssistantActionType } from '../../types/assistant';

import {
  ACTION_LABELS,
  expenseShareNote,
  summarizeAction,
  truncate,
  type ActionSummaryContext,
} from './actionSummary';

const action = (actionType: AssistantActionType, payload: Record<string, unknown>): AssistantAction => ({
  id: `action-${actionType}`,
  actionType,
  status: 'PENDING',
  payload,
  fieldErrors: [],
  revision: 0,
  targetSnapshot: null,
  result: null,
  failureMessage: null,
});

const context: ActionSummaryContext = {
  meId: 'simon',
  otherName: 'Rhian',
  options: {
    childIds: [
      { value: 'ethan', label: 'Ethan Rowe' },
      { value: 'ava', label: 'Ava Rowe' },
    ],
    childId: [
      { value: 'ethan', label: 'Ethan Rowe' },
      { value: 'ava', label: 'Ava Rowe' },
    ],
    payerId: [
      { value: 'simon', label: 'Simon Rowe' },
      { value: 'rhian', label: 'Rhian Raftopoulos' },
    ],
    recipientId: [{ value: 'rhian', label: 'Rhian Raftopoulos' }],
    eventId: [{ value: 'swim', label: 'Swimming' }],
    originalEventId: [{ value: 'swim', label: 'Swimming' }],
    categoryId: [{ value: 'clubs', label: 'Clubs' }],
    requestId: [{ value: 'req-1', label: 'Half term trip' }],
    conversationId: [{ value: 'conv-1', label: 'Half term pickup' }],
    expenseId: [{ value: 'exp-1', label: 'School trip · £45.00' }],
  },
  events: {
    swim: { title: 'Swimming', startDate: '2026-10-12', childIds: ['ava'] },
  },
};

const line = (type: AssistantActionType, payload: Record<string, unknown>, ctx = context) => {
  const summary = summarizeAction(action(type, payload), ctx);
  return summary.detail ? `${summary.label} · ${summary.detail}` : summary.label;
};

describe('summarizeAction', () => {
  it('says what a new repeating event is, for whom and when', () => {
    expect(line('CREATE_EVENT', {
      type: 'activity',
      title: 'Football training',
      startDate: '2026-10-13',
      endDate: null,
      startTime: '18:00',
      endTime: '19:00',
      allDay: false,
      childIds: ['ethan'],
      recurringFrequency: 'weekly',
      recurringDays: ['tuesday'],
    })).toBe('Create event · Ethan — Football training · Tue 13 Oct, 18:00–19:00 · every Tuesday');
  });

  it('says when a series stops and spans several days for a one-off', () => {
    expect(line('CREATE_EVENT', {
      title: 'Clubs',
      startDate: '2026-10-13',
      endDate: '2026-12-15',
      childIds: ['ethan', 'ava'],
      recurringFrequency: 'weekly',
      recurringDays: ['monday', 'thursday'],
    })).toBe('Create event · Ethan and Ava — Clubs · Tue 13 Oct · every Mon and Thu until Tue 15 Dec');
    expect(line('CREATE_EVENT', {
      title: 'Half term',
      startDate: '2026-10-26',
      endDate: '2026-10-30',
      allDay: true,
      childIds: [],
    })).toBe('Create event · Half term · Mon 26 Oct – Fri 30 Oct, all day');
  });

  it('reads a start date sent as a date-time', () => {
    expect(line('CREATE_EVENT', { title: 'Concert', startDate: '2026-10-01T09:00:00Z', childIds: [] }))
      .toBe('Create event · Concert · Thu 1 Oct');
  });

  it('names an event to delete with its child and date', () => {
    expect(line('DELETE_EVENT', { eventId: 'swim', targetHint: null }))
      .toBe('Delete event · Ava — Swimming (Mon 12 Oct)');
  });

  it('falls back to the hint, then to nothing, when the event is not known', () => {
    expect(line('DELETE_EVENT', { eventId: null, targetHint: 'the school fair' }))
      .toBe('Delete event · the school fair (choose which)');
    expect(line('DELETE_EVENT', { eventId: 'gone' }, {})).toBe('Delete event');
  });

  it('lists only what an update changes', () => {
    expect(line('UPDATE_EVENT', {
      eventId: 'swim',
      title: null,
      startDate: '2026-10-14',
      startTime: '17:00',
      endTime: '18:00',
      location: 'Leisure centre',
    })).toBe('Update event · Ava — Swimming (Mon 12 Oct) · move to Wed 14 Oct, 17:00–18:00, at Leisure centre');
    expect(line('UPDATE_EVENT', { eventId: 'swim' })).toBe('Update event · Ava — Swimming (Mon 12 Oct) · no changes yet');
  });

  it('says who paid and who owes whom on an expense', () => {
    const base = {
      title: 'School trip',
      amount: '45.00',
      currency: 'GBP',
      timing: 'paid',
      date: '2026-10-08',
      sharePercent: 50,
    };
    expect(line('CREATE_EXPENSE', { ...base, payerId: 'simon' }))
      .toBe('Add expense · School trip · £45.00 · you paid, Rhian owes you £22.50');
    expect(line('CREATE_EXPENSE', { ...base, payerId: 'rhian' }))
      .toBe('Add expense · School trip · £45.00 · Rhian paid, you owe Rhian £22.50');
    expect(line('CREATE_EXPENSE', { ...base, payerId: 'simon', sharePercent: 100 }))
      .toBe('Add expense · School trip · £45.00 · you paid, nothing owed');
  });

  it('describes a coming-up expense and never calls dollars pounds', () => {
    expect(line('CREATE_EXPENSE', {
      title: 'Swimming lessons',
      amount: '60.00',
      currency: 'GBP',
      timing: 'upcoming',
      date: '2026-11-02',
      payerId: null,
      sharePercent: 50,
    })).toBe('Add expense · Swimming lessons · £60.00 · due Mon 2 Nov, nobody down to pay yet');
    expect(line('CREATE_EXPENSE', {
      title: 'Theme park',
      amount: '40.00',
      currency: 'USD',
      timing: 'paid',
      payerId: 'simon',
    })).toBe('Add expense · Theme park · 40.00 USD (needs pounds) · you paid');
  });

  it('names the payer when it does not know who is signed in', () => {
    expect(line('CREATE_EXPENSE', {
      title: 'Coat', amount: '39', currency: 'GBP', timing: 'paid', payerId: 'rhian',
    }, { options: context.options })).toBe('Add expense · Coat · £39.00 · paid by Rhian');
  });

  it('quotes a message and shortens a long one', () => {
    expect(line('SEND_MESSAGE', { conversationId: null, message: 'Can you do pickup Friday?' }, {
      ...context, options: {},
    })).toBe('Send message · to Rhian: “Can you do pickup Friday?”');
    const long = line('SEND_MESSAGE', {
      conversationId: 'conv-1',
      message: 'Can you do pickup on Friday because I am stuck at work until late and the after school club closes at six',
    });
    expect(long).toBe(
      'Send message · to Rhian: “Can you do pickup on Friday because I am stuck at work…” · in Half term pickup',
    );
  });

  it('starts a conversation with the named recipient', () => {
    expect(line('START_MESSAGE_CONVERSATION', {
      recipientId: 'rhian', subject: 'Half term', message: 'Shall we split the week?',
    })).toBe('Start conversation · with Rhian · “Half term” · “Shall we split the week?”');
  });

  it('covers categories, schedule changes and permission requests', () => {
    expect(line('CREATE_CATEGORY', { name: 'Tutoring', icon: 'book' })).toBe('Create category · Tutoring');
    expect(line('UPDATE_CATEGORY', { categoryId: 'clubs', name: 'After school' }))
      .toBe('Update category · Clubs · rename to “After school”');
    expect(line('DELETE_CATEGORY', { categoryId: 'clubs' })).toBe('Delete category · Clubs');
    expect(line('CREATE_SCHEDULE_CHANGE', {
      originalEventId: 'swim',
      type: 'swap',
      originalStartDate: '2026-10-26',
      originalEndDate: '2026-10-26',
      newStartDate: '2026-10-27',
      newEndDate: '2026-10-27',
      reason: 'Trip',
    })).toBe('Request schedule change · Ava — Swimming (Mon 12 Oct) · swap Mon 26 Oct → Tue 27 Oct');
    expect(line('CREATE_SCHEDULE_CHANGE', {
      type: 'remove', originalStartDate: '2026-10-26', newStartDate: '2026-10-26', newEndDate: '2026-10-26',
    })).toBe('Request schedule change · cancel Mon 26 Oct');
    expect(line('WITHDRAW_SCHEDULE_CHANGE', { requestId: 'req-1' }))
      .toBe('Withdraw schedule request · “Half term trip”');
    expect(line('CREATE_PERMISSION_REQUEST', {
      type: 'travel', childId: 'ethan', description: 'Trip to Wales with his grandparents',
    })).toBe('Create permission request · ask Rhian · Ethan (travel) · “Trip to Wales with his grandparents”');
  });

  it('names the expense being paid or paid back', () => {
    expect(line('MARK_EXPENSE_PAID', { expenseId: 'exp-1', payerId: 'rhian', paidOn: '2026-10-09' }))
      .toBe('Mark expense as paid · School trip · £45.00 · Rhian paid on Fri 9 Oct');
    expect(line('CLAIM_EXPENSE_REIMBURSEMENT', { expenseId: 'exp-1', note: null }))
      .toBe('Mark expense paid back · School trip · £45.00 · you paid Rhian back');
  });

  it('has a summary for every action type', () => {
    Object.keys(ACTION_LABELS).forEach((type) => {
      const summary = summarizeAction(action(type as AssistantActionType, {}), {});
      expect(summary.label).toBe(ACTION_LABELS[type]);
    });
  });
});

describe('truncate', () => {
  it('keeps short text and cuts long text at a word', () => {
    expect(truncate('Short note')).toBe('Short note');
    expect(truncate('one two three four five', 12)).toBe('one two…');
    expect(truncate('a'.repeat(20), 10)).toBe(`${'a'.repeat(9)}…`);
  });
});

describe('expenseShareNote', () => {
  const expense = (payload: Record<string, unknown>) => expenseShareNote({
    title: 'School trip', amount: '45.00', currency: 'GBP', timing: 'paid', sharePercent: 50,
    payerId: 'me', ...payload,
  }, 'me', 'Rhian');

  it('gives the split and when it counts, never the title, amount or payer again', () => {
    expect(expense({})).toEqual({
      tone: 'owed',
      text: "Your share is £22.50 and Rhian's is £22.50. It counts towards the balance once Rhian agrees.",
    });
    expect(expense({ payerId: 'rhian', sharePercent: 100 }).tone).toBe('owe');
    expect(expense({ sharePercent: 100 }).text)
      .toBe("Your share is £45.00 and Rhian's is £0.00. Rhian will still be asked to agree.");
    [expense({}), expense({ timing: 'upcoming' })].forEach((note) => {
      expect(note.text).not.toMatch(/School trip|£45\.00 ·|you paid|Rhian paid/i);
    });
  });

  it('says nothing is owed until an upcoming cost is paid', () => {
    expect(expense({ timing: 'upcoming', payerId: null, sharePercent: 60 }).text)
      .toBe("Your share will be £27.00 and Rhian's £18.00. Nothing is owed until it is paid.");
  });

  it('asks for what is missing instead', () => {
    expect(expense({ amount: '' }).text).toBe('Enter an amount to see who owes what.');
    expect(expense({ currency: 'USD' }).text).toBe('Change the amount to pounds to see who owes what.');
    expect(expense({ payerId: null }).text).toBe('Choose who paid to see who owes what.');
  });
});
