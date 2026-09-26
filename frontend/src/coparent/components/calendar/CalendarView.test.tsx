import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CalendarView } from './CalendarView';

const parents = [
  {
    id: 'parent-1',
    name: 'Alex',
    fullName: 'Alex Rowe',
    email: 'alex@example.com',
    color: 'violet',
    avatarUrl: null,
  },
  {
    id: 'parent-2',
    name: 'Sam',
    fullName: 'Sam Rowe',
    email: 'sam@example.com',
    color: 'sky',
    avatarUrl: null,
  },
];

const children = [
  {
    id: 'child-1',
    name: 'Theo',
    fullName: 'Theo Rowe',
    birthdate: '2016-04-22',
    avatarUrl: null,
  },
];

describe('CalendarView', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders calendar shell with empty events', () => {
    render(
      <CalendarView
        parents={parents}
        children={children}
        events={[]}
        scheduleChangeRequests={[]}
        currentParentId="parent-1"
      />,
    );

    expect(screen.getByRole('heading', { name: 'Family Calendar' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add Event' })).toBeInTheDocument();
  });

  it('renders events in the current calendar view', () => {
    const today = new Date();
    const isoDate = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;

    render(
      <CalendarView
        parents={parents}
        children={children}
        events={[
          {
            id: 'event-1',
            type: 'activity',
            title: 'Visible Event',
            startDate: isoDate,
            endDate: isoDate,
            allDay: true,
            parentId: 'parent-1',
            parentIds: ['parent-1'],
            childIds: ['child-1'],
            notes: null,
            recurring: null,
          },
        ]}
        scheduleChangeRequests={[]}
        currentParentId="parent-1"
      />,
    );

    expect(screen.getByText('Visible Event')).toBeInTheDocument();
    expect(screen.getByText('This Month').parentElement).toHaveTextContent('1 events');
  });

  it('supports view switching', async () => {
    const user = userEvent.setup();
    const onChangeView = vi.fn();
    const onNavigateDate = vi.fn();

    render(
      <CalendarView
        parents={parents}
        children={children}
        events={[]}
        scheduleChangeRequests={[]}
        currentParentId="parent-1"
        onChangeView={onChangeView}
        onNavigateDate={onNavigateDate}
      />,
    );

    const monthDay = screen
      .getAllByRole('button')
      .find((element) => element.getAttribute('tabindex') === '0');
    expect(monthDay).toBeDefined();
    monthDay?.focus();
    await user.keyboard('{Enter}');

    await user.click(screen.getByRole('button', { name: 'Week' }));
    const weekDay = screen
      .getAllByRole('button')
      .find((element) => element.getAttribute('tabindex') === '0');
    expect(weekDay).toBeDefined();
    weekDay?.focus();
    await user.keyboard(' ');
    await user.click(screen.getByRole('button', { name: 'Day' }));

    expect(onChangeView).toHaveBeenCalledWith('week');
    expect(onChangeView).toHaveBeenCalledWith('day');
    expect(onNavigateDate).toHaveBeenCalledTimes(2);
  });

  it('counts this month by occurrence, so a series that started last month still counts', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 10, 12, 0)); // October 2026
    render(
      <CalendarView
        parents={parents}
        children={children}
        scheduleChangeRequests={[]}
        currentParentId="parent-1"
        events={[
          {
            id: 'football',
            type: 'activity',
            title: 'Football training',
            startDate: '2026-09-08',
            startTime: '18:00',
            endTime: '19:00',
            allDay: false,
            parentId: null,
            childIds: ['child-1'],
            notes: null,
            recurring: { frequency: 'weekly', days: ['tuesday'], excludedDates: ['2026-10-13'] },
          },
          {
            id: 'dentist',
            type: 'medical',
            title: 'Dentist',
            startDate: '2026-10-07',
            allDay: true,
            parentId: null,
            childIds: ['child-1'],
            notes: null,
            recurring: null,
          },
          {
            id: 'old-appointment',
            type: 'medical',
            title: 'Last month',
            startDate: '2026-09-02',
            allDay: true,
            parentId: null,
            childIds: ['child-1'],
            notes: null,
            recurring: null,
          },
        ]}
      />,
    );

    // Tuesdays in October 2026: 6, 13 (skipped), 20, 27, plus the dentist on the 7th.
    const stat = (label: string) =>
      screen.getByText(label).parentElement?.textContent?.replace(label, '').trim();
    expect(stat('This Month')).toBe('4 events');
    expect(stat('Activities')).toBe('3 scheduled');
    expect(stat('Medical')).toBe('1 appointments');
  });
});
