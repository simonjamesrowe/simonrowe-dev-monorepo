import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { CalendarView } from '../components/calendar/CalendarView';
import { EventCreationDrawer } from '../components/calendar/EventCreationDrawer';
import {
  useFamilies,
  useParents,
  useChildren,
  useEvents,
  useScheduleChangeRequests,
  useCreateEvent,
  useUpdateEvent,
  useDeleteEvent,
  useApproveScheduleChangeRequest,
  useDeclineScheduleChangeRequest,
} from '../hooks/api';
import type { Event, ScheduleChangeRequest, Parent, Child } from '../types/calendar';

type ApiEvent = {
  _id?: string;
  id?: string;
  type?: string;
  title?: string;
  startDate?: string;
  endDate?: string;
  startTime?: string;
  endTime?: string;
  allDay?: boolean;
  parentId?: string | { _id?: string } | null;
  parentIds?: Array<string | { _id?: string }> | { _id?: string } | null;
  childIds?: Array<string | { _id?: string }>;
  location?: string;
  notes?: string | null;
  recurring?: Event['recurring'] | null;
};

type ApiScheduleChangeRequest = {
  _id?: string;
  id?: string;
  status?: ScheduleChangeRequest['status'];
  requestedBy?: string | { _id?: string };
  requestedAt?: string;
  resolvedBy?: string | { _id?: string } | null;
  resolvedAt?: string | null;
  originalEventId?: string | { _id?: string } | null;
  proposedChange?: ScheduleChangeRequest['proposedChange'];
  reason?: string;
  responseNote?: string | null;
};

const CalendarPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const [activeFamilyId, setActiveFamilyId] = useState<string | undefined>();
  const dateToYmd = (date: Date) => {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  };
  const todayYmd = dateToYmd(new Date());
  const normalizeDate = (value: string | undefined, fallback: string) => {
    if (!value) return fallback;
    return value.includes('T') ? value.slice(0, 10) : value;
  };
  const unwrapId = (value: string | { _id?: string } | null | undefined) => {
    if (!value) return null;
    if (typeof value === 'string') return value;
    return value._id ?? null;
  };
  const normalizeIdArray = (value: ApiEvent['parentIds'] | ApiEvent['childIds']) => {
    if (!value) return [];
    if (Array.isArray(value)) {
      return value
        .map((entry) => (typeof entry === 'string' ? entry : entry?._id))
        .filter((id): id is string => Boolean(id));
    }
    const single = typeof value === 'string' ? value : value?._id;
    return single ? [single] : [];
  };

  // Get drawer state from URL
  const isCreating = searchParams.get('create') === 'true';
  const editingEventId = searchParams.get('edit') || undefined;
  const isEditing = Boolean(editingEventId);
  const initialDate = searchParams.get('date') || todayYmd;

  const { data: parents = [] } = useParents(activeFamilyId);
  const { data: children = [] } = useChildren(activeFamilyId);
  const { data: events = [] } = useEvents(activeFamilyId);
  const { data: scheduleChangeRequests = [] } = useScheduleChangeRequests(activeFamilyId);

  const createEvent = useCreateEvent();
  const updateEvent = useUpdateEvent();
  const deleteEvent = useDeleteEvent();
  const approveScheduleChangeRequest = useApproveScheduleChangeRequest();
  const declineScheduleChangeRequest = useDeclineScheduleChangeRequest();

  useEffect(() => {
    const [firstFamily] = families;
    if (!activeFamilyId && firstFamily) {
      setActiveFamilyId(firstFamily.id);
    }
  }, [activeFamilyId, families]);

  // Get current parent (the logged-in user)
  const currentParent = parents.find((p) => p.role === 'primary') || parents[0];
  const currentParentId = currentParent?.id || '';

  // Transform data to match component expectations
  const transformedParents: Parent[] = parents.map((p) => ({
    id: p.id,
    name: p.fullName.split(' ')[0] || p.fullName,
    fullName: p.fullName,
    email: p.email || '',
    color: p.color || (p.role === 'primary' ? 'violet' : 'sky'),
    avatarUrl: p.avatarUrl || null,
  }));

  const transformedChildren: Child[] = children.map((c) => ({
    id: c.id,
    name: c.fullName.split(' ')[0] || c.fullName,
    fullName: c.fullName,
    birthdate: c.dateOfBirth,
    avatarUrl: null,
  }));

  const transformedEvents: Event[] = (events as unknown as ApiEvent[]).map((e, index) => {
    const startDate = normalizeDate(e.startDate, todayYmd);
    return {
      id: (e._id ?? e.id ?? `event-${index}`) as string,
      type: e.type ?? 'activity',
      title: e.title ?? 'Untitled event',
      startDate,
      endDate: e.endDate ? normalizeDate(e.endDate, startDate) : undefined,
      startTime: e.startTime ?? undefined,
      endTime: e.endTime ?? undefined,
      allDay: Boolean(e.allDay),
      parentId: unwrapId(e.parentId),
      parentIds: normalizeIdArray(e.parentIds),
      childIds: normalizeIdArray(e.childIds),
      location: e.location ?? undefined,
      notes: e.notes ?? null,
      recurring: e.recurring ?? null,
    };
  });

  const transformedRequests: ScheduleChangeRequest[] = (
    scheduleChangeRequests as unknown as ApiScheduleChangeRequest[]
  ).map((r, index) => ({
    id: (r._id ?? r.id ?? `request-${index}`) as string,
    status: r.status ?? 'pending',
    requestedBy: unwrapId(r.requestedBy) ?? '',
    requestedAt: r.requestedAt ?? new Date().toISOString(),
    resolvedBy: unwrapId(r.resolvedBy),
    resolvedAt: r.resolvedAt ?? null,
    originalEventId: unwrapId(r.originalEventId),
    proposedChange: r.proposedChange ?? {
      type: 'add',
      newStartDate: todayYmd,
      newEndDate: todayYmd,
    },
    reason: r.reason ?? '',
    responseNote: r.responseNote ?? null,
  }));

  const editingEvent = editingEventId
    ? transformedEvents.find((event) => event.id === editingEventId)
    : undefined;
  const drawerOpen = isCreating || isEditing;
  const drawerMode: 'create' | 'edit' = isEditing ? 'edit' : 'create';
  const drawerInitialDate = editingEvent?.startDate || initialDate;

  const handleOpenDrawer = (date?: string) => {
    const params: Record<string, string> = { create: 'true' };
    if (date) {
      params.date = date;
    }
    setSearchParams(params);
  };

  const handleCloseDrawer = () => {
    setSearchParams({});
  };

  const handleCreateEvent = async (eventData: Omit<Event, 'id'>) => {
    if (!activeFamilyId) return;
    await createEvent.mutateAsync({ familyId: activeFamilyId, ...eventData });
  };

  const handleUpdateEvent = async (eventId: string, eventData: Omit<Event, 'id'>) => {
    if (!activeFamilyId) return;
    await updateEvent.mutateAsync({ id: eventId, familyId: activeFamilyId, ...eventData });
  };

  const handleViewEvent = async (eventId: string) => {
    setSearchParams({ edit: eventId });
  };

  const handleEditEvent = async (eventId: string) => {
    setSearchParams({ edit: eventId });
  };

  const handleDeleteEvent = async (eventId: string) => {
    if (!activeFamilyId) return;
    await deleteEvent.mutateAsync({ id: eventId, familyId: activeFamilyId });
  };

  const handleRequestScheduleChange = async (eventId: string) => {
    console.log('Request schedule change for event:', eventId);
  };

  const handleApproveRequest = async (requestId: string, responseNote?: string) => {
    if (!activeFamilyId) return;
    await approveScheduleChangeRequest.mutateAsync({
      id: requestId,
      familyId: activeFamilyId,
      responseNote,
    });
  };

  const handleDeclineRequest = async (requestId: string, responseNote?: string) => {
    if (!activeFamilyId) return;
    await declineScheduleChangeRequest.mutateAsync({
      id: requestId,
      familyId: activeFamilyId,
      responseNote,
    });
  };

  const handleViewRequest = async (requestId: string) => {
    console.log('View request:', requestId);
  };

  const handleChangeView = (view: 'month' | 'week' | 'day') => {
    console.log('View changed to:', view);
  };

  const handleNavigateDate = (date: string) => {
    console.log('Navigate to date:', date);
  };

  if (familiesLoading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="text-slate-600 dark:text-slate-400">Loading...</div>
      </div>
    );
  }

  if (!activeFamilyId) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="text-slate-600 dark:text-slate-400">
          No family found. Please set up your family first.
        </div>
      </div>
    );
  }

  return (
    <div className="h-full">
      <CalendarView
        parents={transformedParents}
        children={transformedChildren}
        events={transformedEvents}
        scheduleChangeRequests={transformedRequests}
        currentParentId={currentParentId}
        onViewEvent={handleViewEvent}
        onCreateEvent={() => handleOpenDrawer()}
        onEditEvent={handleEditEvent}
        onDeleteEvent={handleDeleteEvent}
        onRequestScheduleChange={handleRequestScheduleChange}
        onApproveRequest={handleApproveRequest}
        onDeclineRequest={handleDeclineRequest}
        onViewRequest={handleViewRequest}
        onChangeView={handleChangeView}
        onNavigateDate={handleNavigateDate}
      />

      <EventCreationDrawer
        open={drawerOpen}
        onClose={handleCloseDrawer}
        initialDate={drawerInitialDate}
        parents={transformedParents}
        children={transformedChildren}
        event={editingEvent}
        mode={drawerMode}
        currentParentId={currentParentId}
        onSubmit={(eventData) => {
          if (drawerMode === 'edit' && editingEventId) {
            return handleUpdateEvent(editingEventId, eventData);
          }
          return handleCreateEvent(eventData);
        }}
      />
    </div>
  );
};

export default CalendarPage;
