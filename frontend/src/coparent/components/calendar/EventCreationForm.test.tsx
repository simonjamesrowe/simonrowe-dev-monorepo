import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { EventCreationForm, type EventCreationFormRef } from './EventCreationForm';

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

describe('EventCreationForm', () => {
  it('renders core form fields', () => {
    render(
      <EventCreationForm
        parents={parents}
        children={children}
        currentParentId="parent-1"
        initialDate="2026-02-20"
      />,
    );

    expect(screen.getByPlaceholderText('e.g. Emma Soccer Practice')).toBeInTheDocument();
    expect(screen.getByText('Start date')).toBeInTheDocument();
    expect(screen.getByText('End date')).toBeInTheDocument();
    expect(screen.getByText('Event type')).toBeInTheDocument();
  });

  it('validates required fields (title and date)', async () => {
    const user = userEvent.setup();
    const onValidationChange = vi.fn();

    render(
      <EventCreationForm
        parents={parents}
        children={children}
        currentParentId="parent-1"
        initialDate="2026-02-20"
        onValidationChange={onValidationChange}
      />,
    );

    expect(onValidationChange).toHaveBeenCalledWith(false);

    await user.type(screen.getByPlaceholderText('e.g. Emma Soccer Practice'), 'Science Fair');
    expect(onValidationChange).toHaveBeenLastCalledWith(true);
  });

  it('submits event payload with expected shape', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    const ref = createRef<EventCreationFormRef>();

    render(
      <EventCreationForm
        ref={ref}
        parents={parents}
        children={children}
        currentParentId="parent-1"
        initialDate="2026-02-20"
        onSubmit={onSubmit}
      />,
    );

    await user.type(screen.getByPlaceholderText('e.g. Emma Soccer Practice'), 'Parent Conference');

    await act(async () => {
      ref.current?.submit();
    });

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Parent Conference',
        type: 'activity',
        startDate: '2026-02-20',
        childIds: ['child-1'],
      }),
    );
  });

  it('supports event type selection', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    const ref = createRef<EventCreationFormRef>();

    render(
      <EventCreationForm
        ref={ref}
        parents={parents}
        children={children}
        currentParentId="parent-1"
        initialDate="2026-02-20"
        onSubmit={onSubmit}
      />,
    );

    await user.click(screen.getByRole('button', { name: /custody/i }));
    await user.type(screen.getByPlaceholderText('e.g. Emma Soccer Practice'), 'Custody block');

    await act(async () => {
      ref.current?.submit();
    });

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'custody',
        allDay: true,
      }),
    );
  });

  it('captures scheduling, recurrence, ownership, and context changes', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    const ref = createRef<EventCreationFormRef>();
    const { container } = render(
      <EventCreationForm
        ref={ref}
        parents={parents}
        children={children}
        currentParentId="parent-1"
        initialDate="2026-02-20"
        onSubmit={onSubmit}
      />,
    );

    await user.type(screen.getByPlaceholderText('e.g. Emma Soccer Practice'), 'Weekly therapy');
    const customType = screen.getByPlaceholderText('e.g. Therapy, Travel, Birthday');
    await user.clear(customType);
    await user.type(customType, 'therapy');

    const allDay = container.querySelector('input[type="checkbox"]');
    expect(allDay).not.toBeNull();
    await user.click(allDay as HTMLInputElement);
    await user.click(allDay as HTMLInputElement);

    const dateInputs = container.querySelectorAll<HTMLInputElement>('input[type="date"]');
    fireEvent.pointerDown(dateInputs[0]);
    fireEvent.mouseDown(dateInputs[0]);
    await user.clear(dateInputs[0]);
    await user.type(dateInputs[0], '2026-03-02');
    await user.clear(dateInputs[1]);
    await user.type(dateInputs[1], '2026-03-02');

    const timeInputs = container.querySelectorAll<HTMLInputElement>('input[type="time"]');
    await user.clear(timeInputs[0]);
    await user.type(timeInputs[0], '09:30');
    await user.clear(timeInputs[1]);
    await user.type(timeInputs[1], '10:30');

    await user.click(screen.getByRole('button', { name: 'Every weekly' }));
    await user.click(screen.getAllByRole('button', { name: 'M' })[0]);
    await user.click(screen.getByRole('button', { name: 'Theo' }));

    const samButtons = screen.getAllByRole('button', { name: /^Sam/ });
    await user.click(samButtons[1]);
    await user.type(screen.getByPlaceholderText('Add location or address'), 'Community Centre');
    await user.type(
      screen.getByPlaceholderText('Add reminders, what to bring, or additional details'),
      'Bring paperwork',
    );

    await act(async () => {
      ref.current?.submit();
    });

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'therapy',
        startDate: '2026-03-02',
        startTime: '09:30',
        endTime: '10:30',
        parentId: null,
        childIds: [],
        location: 'Community Centre',
        notes: 'Bring paperwork',
        recurring: expect.objectContaining({ frequency: 'weekly' }),
      }),
    );
  });

  it('keeps an open-ended series open-ended when it is edited', async () => {
    const onSubmit = vi.fn();
    const ref = createRef<EventCreationFormRef>();
    render(
      <EventCreationForm
        ref={ref}
        parents={parents}
        children={children}
        currentParentId="parent-1"
        initialEvent={{
          id: 'series',
          title: 'Football training',
          type: 'activity',
          startDate: '2026-09-29',
          startTime: '18:00',
          endTime: '19:00',
          allDay: false,
          childIds: ['child-1'],
          recurring: { frequency: 'weekly', days: ['tuesday'], excludedDates: ['2026-10-27'] },
        }}
        onSubmit={onSubmit}
      />,
    );

    expect(screen.getByText('Repeat until')).toBeInTheDocument();
    expect(screen.getByText('No end date — keeps repeating.')).toBeInTheDocument();

    await act(async () => {
      ref.current?.submit();
    });

    const [[submitted]] = onSubmit.mock.calls;
    expect(submitted.endDate).toBeUndefined();
    expect(submitted.recurring).toEqual({
      frequency: 'weekly',
      days: ['tuesday'],
      excludedDates: ['2026-10-27'],
    });
  });

  it('sends the latest skipped dates rather than the ones loaded when the form opened', async () => {
    const onSubmit = vi.fn();
    const ref = createRef<EventCreationFormRef>();
    const series = {
      id: 'series',
      title: 'Swimming',
      type: 'activity',
      startDate: '2026-09-28',
      allDay: false,
      childIds: ['child-1'],
      recurring: {
        frequency: 'weekly' as const,
        days: ['monday'],
        excludedDates: ['2026-10-26'],
      },
    };
    const { rerender } = render(
      <EventCreationForm
        ref={ref}
        parents={parents}
        children={children}
        currentParentId="parent-1"
        initialEvent={series}
        onSubmit={onSubmit}
      />,
    );

    // A date was restored in the drawer while the form stayed open.
    rerender(
      <EventCreationForm
        ref={ref}
        parents={parents}
        children={children}
        currentParentId="parent-1"
        initialEvent={{ ...series, recurring: { ...series.recurring, excludedDates: [] } }}
        onSubmit={onSubmit}
      />,
    );
    await act(async () => {
      ref.current?.submit();
    });

    expect(onSubmit.mock.calls[0][0].recurring.excludedDates).toEqual([]);
  });

  it('starts a new series with no end date and restores one when repeating is turned off', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    const ref = createRef<EventCreationFormRef>();
    const { container } = render(
      <EventCreationForm
        ref={ref}
        parents={parents}
        children={children}
        currentParentId="parent-1"
        initialDate="2026-10-06"
        onSubmit={onSubmit}
      />,
    );
    await user.type(screen.getByPlaceholderText('e.g. Emma Soccer Practice'), 'Cricket');
    const endDate = () => container.querySelectorAll<HTMLInputElement>('input[type="date"]')[1];

    await user.click(screen.getByRole('button', { name: 'Every weekly' }));
    expect(endDate().value).toBe('');

    await user.type(endDate(), '2026-12-18');
    await user.click(screen.getByRole('button', { name: 'Remove end date' }));
    expect(endDate().value).toBe('');

    await user.click(screen.getByRole('button', { name: 'Does not repeat' }));
    expect(endDate().value).toBe('2026-10-06');
    await act(async () => {
      ref.current?.submit();
    });
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ endDate: '2026-10-06', recurring: null }),
    );
  });
});
