import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

import type { SchoolSuggestion } from '../../types/schoolSuggestions';

import { SchoolSuggestionsDrawer } from './SchoolSuggestionsDrawer';

const base: SchoolSuggestion = {
  id: 's1',
  status: 'pending',
  review: null,
  childIds: ['c1', 'c2'],
  details: {
    title: 'Local Area Walk',
    startDate: '2026-11-27',
    endDate: '2026-11-27',
    startTime: '09:15',
    endTime: '10:45',
    location: 'Ladywell Fields',
    notes: null,
    sourceTime: '09.15 to 10.45am',
  },
  proposed: null,
  sourceTitles: ["Harriet's class trip", 'Year 3 Local Area Walk'],
  calendarEventId: null,
};

const renderDrawer = (suggestions: SchoolSuggestion[], anyChildLinked = true) => {
  const handlers = {
    onAccept: vi.fn().mockResolvedValue(undefined),
    onDismiss: vi.fn().mockResolvedValue(undefined),
    onReview: vi.fn().mockResolvedValue(undefined),
  };
  render(
    <MemoryRouter>
      <SchoolSuggestionsDrawer
        open
        onClose={vi.fn()}
        suggestions={suggestions}
        childNames={{ c1: 'Robin', c2: 'Sam' }}
        anyChildLinked={anyChildLinked}
        {...handlers}
      />
    </MemoryRouter>,
  );
  return handlers;
};

describe('SchoolSuggestionsDrawer', () => {
  it('shows what the school said and adds or dismisses it', async () => {
    const user = userEvent.setup();
    const { onAccept, onDismiss } = renderDrawer([base]);

    const item = within(screen.getByRole('listitem', { name: 'Local Area Walk' }));
    expect(item.getByText('Fri 27 Nov, 09:15–10:45')).toBeInTheDocument();
    expect(item.getByText('For Robin and Sam')).toBeInTheDocument();
    expect(item.getByText(/Harriet's class trip · Year 3 Local Area Walk/)).toBeInTheDocument();

    await user.click(item.getByRole('button', { name: 'Add to calendar' }));
    expect(onAccept).toHaveBeenCalledWith('s1');
    await user.click(item.getByRole('button', { name: 'Dismiss' }));
    expect(onDismiss).toHaveBeenCalledWith('s1');
  });

  it('asks again about an accepted event the school moved', async () => {
    const user = userEvent.setup();
    const { onReview } = renderDrawer([
      {
        ...base,
        status: 'accepted',
        review: 'changed',
        proposed: { ...base.details, startTime: '13:30', endTime: null },
      },
    ]);

    expect(screen.getByText('The school changed this since you added it')).toBeInTheDocument();
    expect(screen.getByText('now Fri 27 Nov, 13:30')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Update calendar' }));
    expect(onReview).toHaveBeenCalledWith('s1', 'APPLY');
    await user.click(screen.getByRole('button', { name: 'Keep as it is' }));
    expect(onReview).toHaveBeenCalledWith('s1', 'KEEP');
  });

  it('offers to take off an event the school no longer states', async () => {
    const user = userEvent.setup();
    const { onReview } = renderDrawer([{ ...base, status: 'accepted', review: 'removed' }]);

    expect(screen.getByText('No longer on the school calendar')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove from calendar' }));
    expect(onReview).toHaveBeenCalledWith('s1', 'REMOVE');
  });

  it('says what went wrong when a decision fails', async () => {
    const user = userEvent.setup();
    const handlers = renderDrawer([base]);
    handlers.onAccept.mockRejectedValueOnce(new Error('409'));

    await user.click(screen.getByRole('button', { name: 'Add to calendar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That did not work');
  });

  it('points to Family setup when no child is linked', () => {
    renderDrawer([], false);
    expect(screen.getByRole('link', { name: 'Link one in Family setup' })).toHaveAttribute(
      'href',
      '/family-setup',
    );
  });
});
