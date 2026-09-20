import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

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

    render(
      <CalendarView
        parents={parents}
        children={children}
        events={[]}
        scheduleChangeRequests={[]}
        currentParentId="parent-1"
        onChangeView={onChangeView}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Week' }));
    await user.click(screen.getByRole('button', { name: 'Day' }));

    expect(onChangeView).toHaveBeenCalledWith('week');
    expect(onChangeView).toHaveBeenCalledWith('day');
  });
});
