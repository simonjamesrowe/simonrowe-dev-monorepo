import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import * as apiHooks from '../hooks/api';

import CalendarPage from './CalendarPage';

const createEventMutate = vi.fn();

vi.mock('../hooks/api', () => ({
  useFamilies: vi.fn(),
  useParents: vi.fn(),
  useChildren: vi.fn(),
  useEvents: vi.fn(),
  useScheduleChangeRequests: vi.fn(),
  useCreateEvent: vi.fn(),
  useUpdateEvent: vi.fn(),
  useDeleteEvent: vi.fn(),
  useApproveScheduleChangeRequest: vi.fn(),
  useDeclineScheduleChangeRequest: vi.fn(),
}));

const mockedUseFamilies = vi.mocked(apiHooks.useFamilies);
const mockedUseParents = vi.mocked(apiHooks.useParents);
const mockedUseChildren = vi.mocked(apiHooks.useChildren);
const mockedUseEvents = vi.mocked(apiHooks.useEvents);
const mockedUseScheduleChangeRequests = vi.mocked(apiHooks.useScheduleChangeRequests);
const mockedUseCreateEvent = vi.mocked(apiHooks.useCreateEvent);
const mockedUseUpdateEvent = vi.mocked(apiHooks.useUpdateEvent);
const mockedUseDeleteEvent = vi.mocked(apiHooks.useDeleteEvent);
const mockedUseApproveScheduleChangeRequest = vi.mocked(apiHooks.useApproveScheduleChangeRequest);
const mockedUseDeclineScheduleChangeRequest = vi.mocked(apiHooks.useDeclineScheduleChangeRequest);

describe('CalendarPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();

    createEventMutate.mockResolvedValue({ id: 'event-1' });

    mockedUseFamilies.mockReturnValue({
      data: [
        {
          id: 'fam-1',
          name: 'Calendar Family',
          timeZone: 'America/New_York',
          parentIds: ['parent-1'],
          childIds: ['child-1'],
          invitationIds: ['invite-1'],
          createdAt: '2026-01-01T00:00:00.000Z',
        },
      ],
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useFamilies>);

    mockedUseParents.mockReturnValue({
      data: [
        {
          id: 'parent-1',
          familyId: 'fam-1',
          fullName: 'Alex Rowe',
          email: 'alex@example.com',
          role: 'primary',
          status: 'active',
          color: 'violet',
          avatarUrl: null,
          auth0Id: 'auth0|test',
          lastSignedInAt: '2026-01-01T00:00:00.000Z',
        },
      ],
    } as unknown as ReturnType<typeof apiHooks.useParents>);

    mockedUseChildren.mockReturnValue({
      data: [
        {
          id: 'child-1',
          familyId: 'fam-1',
          fullName: 'Theo Rowe',
          dateOfBirth: '2016-04-22',
          school: 'River Elementary',
          medicalNotes: '',
        },
      ],
    } as unknown as ReturnType<typeof apiHooks.useChildren>);

    mockedUseEvents.mockReturnValue({
      data: [],
    } as unknown as ReturnType<typeof apiHooks.useEvents>);

    mockedUseScheduleChangeRequests.mockReturnValue({
      data: [],
    } as unknown as ReturnType<typeof apiHooks.useScheduleChangeRequests>);

    mockedUseCreateEvent.mockReturnValue({
      mutateAsync: createEventMutate,
    } as unknown as ReturnType<typeof apiHooks.useCreateEvent>);

    mockedUseUpdateEvent.mockReturnValue({
      mutateAsync: vi.fn(),
    } as unknown as ReturnType<typeof apiHooks.useUpdateEvent>);

    mockedUseDeleteEvent.mockReturnValue({
      mutateAsync: vi.fn(),
    } as unknown as ReturnType<typeof apiHooks.useDeleteEvent>);

    mockedUseApproveScheduleChangeRequest.mockReturnValue({
      mutateAsync: vi.fn(),
    } as unknown as ReturnType<typeof apiHooks.useApproveScheduleChangeRequest>);

    mockedUseDeclineScheduleChangeRequest.mockReturnValue({
      mutateAsync: vi.fn(),
    } as unknown as ReturnType<typeof apiHooks.useDeclineScheduleChangeRequest>);
  });

  it('renders calendar view', async () => {
    render(
      <MemoryRouter>
        <CalendarPage />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Family Calendar' })).toBeInTheDocument();
    });
  });

  it('opens event creation drawer and submits a new event', async () => {
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <CalendarPage />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Add Event' })).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: 'Add Event' }));
    await user.type(screen.getByPlaceholderText('e.g. Emma Soccer Practice'), 'Test Event');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => {
      expect(createEventMutate).toHaveBeenCalled();
    });
  });
});
