import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Event } from '../../types/calendar';

import { WeekView } from './WeekView';

const football: Event = {
  id: 'series',
  type: 'activity',
  title: 'Football training',
  startDate: '2026-09-29',
  startTime: '18:00',
  endTime: '19:00',
  allDay: false,
  parentId: null,
  childIds: ['ethan'],
  notes: null,
  recurring: { frequency: 'weekly', days: ['tuesday'] },
};

describe('WeekView', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 8, 30, 9, 30)); // Wednesday 30 Sept 2026, 09:30
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('draws an 18:00 event on the 6 PM row, not two hours lower', () => {
    render(
      <WeekView
        currentDate={new Date(2026, 8, 30)}
        events={[football]}
        parents={{}}
        onDayClick={vi.fn()}
      />,
    );

    const pill = screen.getByRole('button', { name: /Football training/ });
    const box = pill.parentElement as HTMLElement;
    // Rows are 64px from a 7 AM start.
    expect(box.style.top).toBe('704px');
    // Labels share the rows' pixel height and are offset without margins, which accumulate:
    // with -mt-2 on every label, "6 PM" sat 88px above its line by the evening.
    const sixPm = screen.getByText('6 PM');
    expect(sixPm.style.height).toBe('64px');
    expect(sixPm.className).not.toMatch(/(^|\s)-m[ty]?-/);
  });

  it('marks the real today and puts the time line at the real time', () => {
    render(
      <WeekView
        currentDate={new Date(2026, 8, 30)}
        events={[]}
        parents={{}}
        onDayClick={vi.fn()}
      />,
    );

    expect(screen.getByText('30').className).toContain('bg-teal-600');
    expect(screen.getByTestId('week-now-line').style.top).toBe('160px');
  });

  it('draws no time line on a week that does not contain today', () => {
    render(
      <WeekView
        currentDate={new Date(2026, 9, 14)}
        events={[]}
        parents={{}}
        onDayClick={vi.fn()}
      />,
    );

    expect(screen.queryByTestId('week-now-line')).not.toBeInTheDocument();
  });

  const oneOff = (id: string, overrides: Partial<Event>): Event => ({
    ...football,
    id,
    title: id,
    startDate: '2026-10-01',
    recurring: null,
    ...overrides,
  });

  it('puts overlapping events side by side instead of on top of each other', () => {
    render(
      <WeekView
        currentDate={new Date(2026, 8, 30)}
        events={[
          oneOff('Dentist', { startTime: '15:30', endTime: '16:30' }),
          oneOff('Parents evening', { startTime: '16:00', endTime: '17:00' }),
        ]}
        parents={{}}
        onDayClick={vi.fn()}
      />,
    );

    const dentist = screen.getByRole('button', { name: /Dentist/ }).parentElement as HTMLElement;
    const evening = screen.getByRole('button', { name: /Parents evening/ })
      .parentElement as HTMLElement;
    expect(dentist.style.left).not.toBe(evening.style.left);
    // Each takes half the column (jsdom normalises the calc, so match the half, not the text).
    expect(dentist.style.width).toContain('0.5');
  });

  it('lists all-day events in the day header and links the rest to the day', async () => {
    const onDayClick = vi.fn();
    render(
      <WeekView
        currentDate={new Date(2026, 8, 30)}
        events={[
          oneOff('INSET day', { allDay: true, startTime: undefined, endTime: undefined }),
          oneOff('Book fair', { allDay: true, startTime: undefined, endTime: undefined }),
          oneOff('Non-uniform day', { allDay: true, startTime: undefined, endTime: undefined }),
        ]}
        parents={{}}
        onDayClick={onDayClick}
      />,
    );

    expect(screen.getByRole('button', { name: 'INSET day' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Non-uniform day' })).not.toBeInTheDocument();
    screen.getByRole('button', { name: '+1 more' }).click();
    expect(onDayClick).toHaveBeenCalledWith(new Date(2026, 9, 1));
  });

  it('draws a short event on one line, so its text stays inside its box', () => {
    render(
      <WeekView
        currentDate={new Date(2026, 8, 30)}
        events={[oneOff('Swimming', { startTime: '18:30', endTime: '19:00' })]}
        parents={{}}
        onDayClick={vi.fn()}
      />,
    );

    const pill = screen.getByRole('button', { name: /Swimming/ });
    expect(pill.className).toContain('overflow-hidden');
    expect(pill).toHaveTextContent('Swimming18:30');
    expect(pill).not.toHaveTextContent('19:00');
  });

  it('gives the header and the grid one column template, so days stay over their events', () => {
    const { container } = render(
      <WeekView
        currentDate={new Date(2026, 8, 30)}
        events={[]}
        parents={{}}
        onDayClick={vi.fn()}
      />,
    );

    const templates = Array.from(container.querySelectorAll<HTMLElement>('.grid')).map(
      (grid) => grid.style.gridTemplateColumns,
    );
    expect(templates).toEqual([
      '60px repeat(7, minmax(0, 1fr))',
      '60px repeat(7, minmax(0, 1fr))',
    ]);
  });

  it('draws the time line across today only', () => {
    render(
      <WeekView
        currentDate={new Date(2026, 8, 30)}
        events={[]}
        parents={{}}
        onDayClick={vi.fn()}
      />,
    );

    const line = screen.getByTestId('week-now-line');
    // Today is the Wednesday, the fourth day column after the hour labels.
    const column = line.parentElement as HTMLElement;
    expect(Array.from(column.parentElement!.children).indexOf(column)).toBe(4);
  });

  it('names nobody on an event when only one parent is on the calendar', () => {
    const simon = { id: 'simon', name: 'Simon', fullName: 'Simon Rowe', email: '', color: 'violet', avatarUrl: null };
    const rhian = { id: 'rhian', name: 'Rhian', fullName: 'Rhian R', email: '', color: 'sky', avatarUrl: null };
    const event = oneOff('Cricket', { startTime: '18:00', endTime: '19:30', parentId: 'simon' });

    const { rerender } = render(
      <WeekView currentDate={new Date(2026, 8, 30)} events={[event]} parents={{ simon }} onDayClick={vi.fn()} />,
    );
    expect(screen.getByRole('button', { name: /Cricket/ })).not.toHaveTextContent('Simon');

    rerender(
      <WeekView
        currentDate={new Date(2026, 8, 30)}
        events={[event]}
        parents={{ simon, rhian }}
        onDayClick={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: /Cricket/ })).toHaveTextContent('Simon');
  });
});
