import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { Event } from '../../types/calendar';

import { DashboardBriefing, type DashboardBriefingProps } from './DashboardBriefing';
import { briefingChildren } from './briefing';

const kids = briefingChildren([
  { id: 'ethan', fullName: 'Ethan Rowe' },
  { id: 'ava', fullName: 'Ava Rowe' },
]);

const event = (overrides: Partial<Event>): Event => ({
  id: 'football',
  type: 'activity',
  title: 'Ethan - Football training',
  startDate: '2026-10-10T00:00:00Z',
  startTime: '09:00',
  endTime: '10:15',
  allDay: false,
  parentId: 'simon',
  childIds: ['ethan'],
  location: 'the bridge',
  notes: null,
  recurring: null,
  ...overrides,
});

const events = [
  event({}),
  event({ id: 'slda', title: 'Ava - SLDA class', childIds: ['ava'], startTime: '11:00', endTime: '13:45', location: undefined }),
  event({ id: 'game', title: 'Ethan - Football game', startDate: '2026-10-11T00:00:00Z', startTime: '12:00', endTime: '14:30' }),
  event({
    id: 'swim',
    title: 'Ava - Swimming',
    childIds: ['ava'],
    startDate: '2026-10-12T00:00:00Z',
    startTime: '18:30',
    endTime: '19:00',
    location: undefined,
    recurring: { frequency: 'weekly', days: ['monday'] },
  }),
];

const renderBriefing = (overrides: Partial<DashboardBriefingProps> = {}) => {
  const props: DashboardBriefingProps = {
    // Saturday 10 October 2026, 15:45: both of today's activities are over.
    now: new Date(2026, 9, 10, 15, 45),
    children: kids,
    events,
    custodyParentName: null,
    handover: null,
    needs: [],
    money: { headline: 'All square', detail: 'No shared costs coming up.', actions: [] },
    onAddEvent: vi.fn(),
    onOpenCalendar: vi.fn(),
    onOpenEvent: vi.fn(),
    onEditProfile: vi.fn(),
    onFamilySetup: vi.fn(),
    ...overrides,
  };
  render(<DashboardBriefing {...props} />);
  return props;
};

describe('DashboardBriefing', () => {
  it('leads with the children and what is left of their day', () => {
    renderBriefing();

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      'Ethan and Ava have nothing else on today.',
    );
    expect(screen.getByText(/Tomorrow: Ethan's football game at 12:00\./)).toBeInTheDocument();
    expect(screen.getByText('Saturday 10 October')).toBeInTheDocument();
  });

  it('says who has the children and when they move, once custody days exist', () => {
    renderBriefing({
      events: [...events, event({ id: 'custody', type: 'custody', title: 'With Simon', allDay: true, startTime: undefined })],
      custodyParentName: 'you',
      handover: { date: '2026-10-12', parentName: 'Rhian' },
    });

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Ethan and Ava are with you.');
    expect(screen.getByText(/Next handover/)).toHaveTextContent('Next handover Monday 12 October, to Rhian.');
    expect(screen.queryByText(/Add custody days/)).not.toBeInTheDocument();
  });

  it('marks finished items as done and names each by child, without repeating the child in the title', async () => {
    const user = userEvent.setup();
    const props = renderBriefing();

    const today = screen.getByRole('region', { name: 'Today' });
    const football = within(today).getByRole('button', { name: /Football training/ });
    expect(football).toHaveTextContent('09:00–10:15EthanFootball trainingthe bridgeDone');
    await user.click(football);
    expect(props.onOpenEvent).toHaveBeenCalledWith('football', undefined);
  });

  it('opens the right week of a repeating event from the days ahead', async () => {
    const user = userEvent.setup();
    const props = renderBriefing();

    const comingUp = screen.getByRole('region', { name: 'Coming up' });
    await user.click(within(comingUp).getByRole('button', { name: /Swimming/ }));
    expect(props.onOpenEvent).toHaveBeenCalledWith('swim', '2026-10-12');
    expect(within(comingUp).getAllByText('Nothing on')).toHaveLength(4);
  });

  it('lists what needs the parent, with its actions, or says nothing does', async () => {
    const user = userEvent.setup();
    const resend = vi.fn();
    renderBriefing({
      needs: [
        {
          id: 'invite',
          kind: 'invite',
          title: "Rhian hasn't joined yet",
          detail: 'Invitation sent Thursday 8 October.',
          actions: [{ label: 'Resend', primary: true, onClick: resend }],
        },
      ],
    });

    const needs = screen.getByRole('region', { name: 'Needs you' });
    expect(needs).toHaveTextContent("Rhian hasn't joined yet");
    await user.click(within(needs).getByRole('button', { name: 'Resend' }));
    expect(resend).toHaveBeenCalled();
  });

  it('has a plain empty state for each section', () => {
    renderBriefing({ events: [] });

    expect(screen.getByText('Nothing needs you right now.')).toBeInTheDocument();
    expect(screen.getByText('Nothing on the calendar today.')).toBeInTheDocument();
    expect(screen.getByText('Nothing on the calendar tomorrow.')).toBeInTheDocument();
  });

  it('offers Quick add only when it is switched on', () => {
    renderBriefing();
    expect(screen.queryByRole('button', { name: 'Quick add' })).not.toBeInTheDocument();
  });

  it('puts the time line at now and fades what is over', () => {
    const { container } = render(
      <DashboardBriefing
        now={new Date(2026, 9, 10, 15, 45)}
        children={kids}
        events={events}
        custodyParentName={null}
        handover={null}
        needs={[]}
        money={null}
        onAddEvent={vi.fn()}
        onOpenCalendar={vi.fn()}
        onOpenEvent={vi.fn()}
        onEditProfile={vi.fn()}
        onFamilySetup={vi.fn()}
      />,
    );

    // 15:45 on a 7am to 9pm line: 8h45m of 14h.
    expect(screen.getByTestId('briefing-now').style.left).toBe('62.5%');
    expect(container.querySelectorAll('.cp-brief__strip-block--past')).toHaveLength(2);
  });
});
