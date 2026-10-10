import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Event } from '../../types/calendar';

import { DayView } from './DayView';

const event = (id: string, startTime: string, endTime: string): Event => ({
  id,
  type: 'activity',
  title: id,
  startDate: '2026-10-14',
  startTime,
  endTime,
  allDay: false,
  parentId: null,
  childIds: [],
  notes: null,
  recurring: null,
});

const renderDay = (events: Event[]) =>
  render(
    <DayView
      currentDate={new Date(2026, 9, 14)}
      events={events}
      parents={{}}
      children={[]}
    />,
  );

describe('DayView', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 10, 15, 45));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('makes a box exactly as tall as its event and keeps the title at the top', () => {
    renderDay([event('SLDA class', '11:00', '13:45')]);

    const box = screen.getByRole('button', { name: /SLDA class/ });
    // 2h45m at 80px an hour. A min-height let the content stretch the box past its end time.
    expect(box.style.height).toBe('220px');
    expect(box.className).toContain('flex-col');
    expect(box.className).toContain('overflow-hidden');
  });

  it('draws a half-hour event on one line', () => {
    renderDay([event('Swimming', '18:30', '19:00')]);

    // Title and time share one row; stacked, the time fell below a 40px box.
    const time = screen.getByText('18:30 – 19:00');
    expect(time.parentElement).toHaveTextContent('Swimming18:30 – 19:00');
    expect(time.parentElement?.className).toContain('items-center');
  });

  it('puts overlapping events side by side', () => {
    renderDay([event('Dentist', '15:30', '16:30'), event('Piano', '16:15', '16:45')]);

    const dentist = screen.getByRole('button', { name: /Dentist/ });
    const piano = screen.getByRole('button', { name: /Piano/ });
    expect(dentist.style.left).not.toBe(piano.style.left);
    expect(piano.style.width).toContain('0.5');
  });

  it('shows an event with no end time without a dangling dash', () => {
    renderDay([event('Pick-up', '15:15', '')]);

    expect(screen.getByRole('button', { name: /Pick-up/ })).not.toHaveTextContent('–');
  });
});
