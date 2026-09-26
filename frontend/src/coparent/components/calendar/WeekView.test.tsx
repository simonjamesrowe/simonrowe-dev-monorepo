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
});
