import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { Event } from '../../types/calendar';

import { EventCreationDrawer } from './EventCreationDrawer';

const series: Event = {
  id: 'series',
  type: 'activity',
  title: 'Swimming',
  startDate: '2026-09-28',
  startTime: '18:30',
  endTime: '19:00',
  allDay: false,
  parentId: null,
  childIds: ['child-1'],
  notes: null,
  recurring: { frequency: 'weekly', days: ['monday'], excludedDates: ['2026-10-26'] },
};

const renderDrawer = (props: Partial<Parameters<typeof EventCreationDrawer>[0]>) =>
  render(
    <EventCreationDrawer
      open
      onClose={vi.fn()}
      initialDate="2026-10-05"
      parents={[]}
      children={[]}
      event={series}
      mode="edit"
      currentParentId="parent-1"
      onSubmit={vi.fn()}
      {...props}
    />,
  );

describe('EventCreationDrawer', () => {
  it('skips only the occurrence that was opened, then closes', async () => {
    const user = userEvent.setup();
    const onSkipOccurrence = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();
    renderDrawer({ occurrenceDate: '2026-10-05', onSkipOccurrence, onClose });

    await user.click(screen.getByRole('button', { name: /^Skip .* only$/ }));

    expect(onSkipOccurrence).toHaveBeenCalledWith('2026-10-05', true);
    expect(onClose).toHaveBeenCalled();
  });

  it('restores a skipped date without closing', async () => {
    const user = userEvent.setup();
    const onSkipOccurrence = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();
    renderDrawer({ onSkipOccurrence, onClose });

    expect(screen.queryByRole('button', { name: /^Skip / })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Restore / }));

    expect(onSkipOccurrence).toHaveBeenCalledWith('2026-10-26', false);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('offers no occurrence controls for a one-off event', () => {
    renderDrawer({
      event: { ...series, recurring: null },
      occurrenceDate: '2026-10-05',
      onSkipOccurrence: vi.fn(),
    });

    expect(screen.queryByText(/This event repeats/)).not.toBeInTheDocument();
  });

  it('shows why a save failed, keeps the drawer open and saves once per click', async () => {
    const user = userEvent.setup();
    let reject: (reason: unknown) => void = () => undefined;
    const onSubmit = vi.fn(
      () =>
        new Promise<void>((_, rejectPromise) => {
          reject = rejectPromise;
        }),
    );
    const onClose = vi.fn();
    renderDrawer({
      onSubmit,
      onClose,
      children: [
        { id: 'child-1', name: 'Ava', fullName: 'Ava Rowe', birthdate: '', avatarUrl: null },
      ],
    });

    const save = screen.getByRole('button', { name: 'Save' });
    await user.click(save);
    expect(screen.getByRole('button', { name: 'Saving...' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Saving...' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);

    reject({ response: { data: { message: 'At least one valid family child is required' } } });
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'At least one valid family child is required',
    );
    expect(onClose).not.toHaveBeenCalled();
  });

  it('asks once more before deleting, and names the whole series', async () => {
    const user = userEvent.setup();
    const onDelete = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();
    renderDrawer({ onDelete, onClose });

    await user.click(screen.getByRole('button', { name: 'Delete' }));
    expect(onDelete).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Delete every occurrence?' }));

    expect(onDelete).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalled();
  });

  it('offers no delete while creating', () => {
    renderDrawer({ mode: 'create', event: undefined, onDelete: vi.fn() });

    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });
});
