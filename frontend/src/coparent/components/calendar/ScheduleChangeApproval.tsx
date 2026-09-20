import { useMemo, useState } from 'react';

import type {
  CalendarSchedulingProps,
  ScheduleChangeRequest,
  Event,
  Parent,
} from '../../types/calendar';

type FilterMode = 'all' | 'pending' | 'approved' | 'declined';

const STATUS_STYLES: Record<ScheduleChangeRequest['status'], string> = {
  pending: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-200',
  approved: 'bg-teal-100 text-teal-700 dark:bg-teal-900/30 dark:text-teal-200',
  declined: 'bg-rose-100 text-rose-700 dark:bg-rose-900/30 dark:text-rose-200',
};

const STATUS_DOT: Record<ScheduleChangeRequest['status'], string> = {
  pending: 'bg-amber-500',
  approved: 'bg-teal-500',
  declined: 'bg-rose-500',
};

function formatDate(dateStr?: string | null): string {
  if (!dateStr) return '—';
  const date = new Date(dateStr + 'T12:00:00');
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    weekday: 'short',
  });
}

function formatDateLong(dateStr?: string | null): string {
  if (!dateStr) return '—';
  const date = new Date(dateStr + 'T12:00:00');
  return date.toLocaleDateString('en-US', {
    month: 'long',
    day: 'numeric',
    year: 'numeric',
    weekday: 'long',
  });
}

function formatDateTime(dateStr?: string | null): string {
  if (!dateStr) return '—';
  const date = new Date(dateStr);
  return date.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

function getDaysBetween(start?: string, end?: string): number {
  if (!start || !end) return 0;
  const startDate = new Date(start + 'T12:00:00');
  const endDate = new Date(end + 'T12:00:00');
  return Math.max(1, Math.ceil((endDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24)));
}

function getRequestLabel(request: ScheduleChangeRequest): string {
  const typeMap: Record<ScheduleChangeRequest['proposedChange']['type'], string> = {
    swap: 'Swap days',
    extend: 'Adjust dates',
    add: 'Add time',
    remove: 'Remove time',
  };
  return typeMap[request.proposedChange.type];
}

function getEventOwner(event: Event | null, parentsMap: Record<string, Parent>): Parent | null {
  if (!event?.parentId) return null;
  return parentsMap[event.parentId] || null;
}

export function ScheduleChangeApproval({
  parents,
  children,
  events,
  scheduleChangeRequests,
  currentParentId,
  onViewEvent,
  onApproveRequest,
  onDeclineRequest,
  onViewRequest,
}: CalendarSchedulingProps) {
  const parentsMap = useMemo(() => {
    const map: Record<string, Parent> = {};
    parents.forEach((parent) => {
      map[parent.id] = parent;
    });
    return map;
  }, [parents]);

  const eventMap = useMemo(() => {
    const map: Record<string, Event> = {};
    events.forEach((event) => {
      map[event.id] = event;
    });
    return map;
  }, [events]);

  const [filter, setFilter] = useState<FilterMode>('all');
  const [selectedId, setSelectedId] = useState(() => {
    const incomingPending = scheduleChangeRequests.find(
      (request) => request.status === 'pending' && request.requestedBy !== currentParentId,
    );
    return incomingPending?.id || scheduleChangeRequests[0]?.id || '';
  });
  const [responseNote, setResponseNote] = useState('');

  const filteredRequests = useMemo(() => {
    if (filter === 'all') return scheduleChangeRequests;
    return scheduleChangeRequests.filter((request) => request.status === filter);
  }, [filter, scheduleChangeRequests]);

  const selectedRequest = scheduleChangeRequests.find((request) => request.id === selectedId);
  const selectedEvent = selectedRequest?.originalEventId
    ? (eventMap[selectedRequest.originalEventId] ?? null)
    : null;
  const requestingParent = selectedRequest ? parentsMap[selectedRequest.requestedBy] : null;
  const eventOwner = getEventOwner(selectedEvent, parentsMap);

  const originalDays = selectedEvent
    ? getDaysBetween(selectedEvent.startDate, selectedEvent.endDate || selectedEvent.startDate)
    : 0;
  const proposedDays = selectedRequest
    ? getDaysBetween(
        selectedRequest.proposedChange.newStartDate,
        selectedRequest.proposedChange.newEndDate,
      )
    : 0;
  const dayDelta = proposedDays - originalDays;

  const incomingPendingCount = scheduleChangeRequests.filter(
    (request) => request.status === 'pending' && request.requestedBy !== currentParentId,
  ).length;

  const handleSelect = (requestId: string) => {
    setSelectedId(requestId);
    setResponseNote('');
    onViewRequest?.(requestId);
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-white to-teal-50/40 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
      <div
        className="pointer-events-none fixed inset-0 opacity-[0.02] dark:opacity-[0.03]"
        style={{
          backgroundImage: 'radial-gradient(circle at 1px 1px, currentColor 1px, transparent 0)',
          backgroundSize: '26px 26px',
        }}
      />

      <div className="relative mx-auto max-w-6xl px-4 py-8 sm:px-6 lg:px-8">
        <div className="mb-8 flex flex-col gap-6 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.25em] text-slate-400">
              Review & Decide
            </p>
            <h1 className="mt-2 text-2xl font-semibold tracking-tight text-slate-800 sm:text-3xl dark:text-slate-100">
              Schedule Change Approvals
            </h1>
            <p className="mt-2 max-w-2xl text-sm text-slate-500 dark:text-slate-400">
              Compare original custody plans to proposed changes, then approve or decline with a
              response note.
            </p>
          </div>

          <div className="flex flex-wrap gap-3">
            <div className="flex items-center gap-2 rounded-xl border border-slate-200/60 bg-white/70 px-4 py-2 dark:border-slate-700/60 dark:bg-slate-800/60">
              <span className="text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
                Pending
              </span>
              <span className="text-lg font-semibold text-teal-600 dark:text-teal-300">
                {incomingPendingCount}
              </span>
            </div>
            <div className="flex items-center gap-2 rounded-xl border border-slate-200/60 bg-white/70 px-4 py-2 dark:border-slate-700/60 dark:bg-slate-800/60">
              <span className="text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
                Total
              </span>
              <span className="text-lg font-semibold text-slate-800 dark:text-slate-100">
                {scheduleChangeRequests.length}
              </span>
            </div>
          </div>
        </div>

        <div className="mb-6 flex flex-wrap gap-2">
          {(['all', 'pending', 'approved', 'declined'] as FilterMode[]).map((mode) => (
            <button
              key={mode}
              onClick={() => setFilter(mode)}
              className={`rounded-full border px-4 py-2 text-sm font-medium transition-all ${
                filter === mode
                  ? 'border-teal-500 bg-teal-600 text-white shadow-lg shadow-teal-500/20'
                  : 'border-slate-200/70 bg-white/60 text-slate-600 hover:border-slate-300 dark:border-slate-700/70 dark:bg-slate-800/60 dark:text-slate-300 dark:hover:border-slate-600'
              }`}
            >
              {mode.charAt(0).toUpperCase() + mode.slice(1)}
            </button>
          ))}
        </div>

        <div className="grid gap-6 lg:grid-cols-[320px_1fr]">
          <div className="space-y-3">
            {filteredRequests.map((request) => {
              const requester = parentsMap[request.requestedBy];
              const isSelected = request.id === selectedId;
              const requestEvent = request.originalEventId
                ? eventMap[request.originalEventId]
                : null;
              return (
                <button
                  key={request.id}
                  onClick={() => handleSelect(request.id)}
                  className={`w-full rounded-2xl border p-4 text-left transition-all duration-200 ${
                    isSelected
                      ? 'border-teal-500 bg-white shadow-xl shadow-teal-500/10 dark:bg-slate-800'
                      : 'border-slate-200/60 bg-white/70 hover:border-slate-300 dark:border-slate-700/60 dark:bg-slate-800/40 dark:hover:border-slate-600'
                  }`}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <p className="text-sm font-semibold text-slate-800 dark:text-slate-100">
                        {requester?.name || 'Parent'} · {getRequestLabel(request)}
                      </p>
                      <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                        {formatDate(request.proposedChange.newStartDate)} →{' '}
                        {formatDate(request.proposedChange.newEndDate)}
                      </p>
                    </div>
                    <span
                      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${STATUS_STYLES[request.status]}`}
                    >
                      <span className={`h-1.5 w-1.5 rounded-full ${STATUS_DOT[request.status]}`} />
                      {request.status}
                    </span>
                  </div>
                  <p className="mt-3 line-clamp-2 text-xs text-slate-500 dark:text-slate-400">
                    {request.reason}
                  </p>
                  {requestEvent && (
                    <div className="mt-3 text-xs text-slate-400 dark:text-slate-500">
                      Original: {requestEvent.title}
                    </div>
                  )}
                </button>
              );
            })}

            {filteredRequests.length === 0 && (
              <div className="rounded-2xl border border-dashed border-slate-300/70 p-6 text-center text-sm text-slate-500 dark:border-slate-700/70 dark:text-slate-400">
                No requests in this view.
              </div>
            )}
          </div>

          <div className="overflow-hidden rounded-2xl border border-slate-200/60 bg-white/80 shadow-2xl shadow-slate-200/40 dark:border-slate-700/60 dark:bg-slate-900/50 dark:shadow-slate-950/40">
            {selectedRequest ? (
              <div className="space-y-6 p-6 sm:p-8">
                <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                  <div>
                    <p className="text-xs uppercase tracking-[0.2em] text-slate-400">
                      Request detail
                    </p>
                    <h2 className="mt-2 text-xl font-semibold text-slate-800 dark:text-slate-100">
                      {getRequestLabel(selectedRequest)} · {requestingParent?.name || 'Parent'}
                    </h2>
                    <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                      Requested {formatDateLong(selectedRequest.proposedChange.newStartDate)}
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <span
                      className={`inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-xs font-semibold ${STATUS_STYLES[selectedRequest.status]}`}
                    >
                      <span
                        className={`h-2 w-2 rounded-full ${STATUS_DOT[selectedRequest.status]}`}
                      />
                      {selectedRequest.status}
                    </span>
                    {selectedEvent && (
                      <button
                        onClick={() => onViewEvent?.(selectedEvent.id)}
                        className="rounded-full border border-slate-200/70 px-3 py-1.5 text-xs font-medium text-slate-600 hover:border-slate-300 dark:border-slate-700/70 dark:text-slate-300 dark:hover:border-slate-600"
                      >
                        View original event
                      </button>
                    )}
                  </div>
                </div>

                <div className="grid gap-4 lg:grid-cols-2">
                  <div className="rounded-2xl border border-slate-200/60 bg-slate-50 p-4 dark:border-slate-700/60 dark:bg-slate-800/60">
                    <div className="mb-3 flex items-center justify-between">
                      <span className="text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
                        Original schedule
                      </span>
                      {eventOwner && (
                        <span className="text-xs text-slate-500 dark:text-slate-400">
                          {eventOwner.name}'s time
                        </span>
                      )}
                    </div>
                    {selectedEvent ? (
                      <>
                        <p className="text-base font-semibold text-slate-800 dark:text-slate-100">
                          {formatDate(selectedEvent.startDate)} →{' '}
                          {formatDate(selectedEvent.endDate || selectedEvent.startDate)}
                        </p>
                        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
                          {selectedEvent.title} · {children.map((child) => child.name).join(' & ')}
                        </p>
                        <div className="mt-4 flex items-center justify-between text-sm">
                          <span className="text-slate-500 dark:text-slate-400">Duration</span>
                          <span className="font-semibold text-slate-700 dark:text-slate-200">
                            {originalDays} {originalDays === 1 ? 'day' : 'days'}
                          </span>
                        </div>
                      </>
                    ) : (
                      <div className="text-sm text-slate-500 dark:text-slate-400">
                        No original event associated. This request adds new time.
                      </div>
                    )}
                  </div>

                  <div className="rounded-2xl border border-teal-200/60 bg-teal-50/70 p-4 dark:border-teal-700/60 dark:bg-teal-900/20">
                    <div className="mb-3 flex items-center justify-between">
                      <span className="text-xs uppercase tracking-wide text-teal-700 dark:text-teal-300">
                        Proposed schedule
                      </span>
                      <span className="text-xs text-teal-600 dark:text-teal-300">
                        {getRequestLabel(selectedRequest)}
                      </span>
                    </div>
                    <p className="text-base font-semibold text-slate-800 dark:text-slate-100">
                      {formatDate(selectedRequest.proposedChange.newStartDate)} →{' '}
                      {formatDate(selectedRequest.proposedChange.newEndDate)}
                    </p>
                    <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
                      {children.map((child) => child.name).join(' & ')}
                    </p>
                    <div className="mt-4 flex items-center justify-between text-sm">
                      <span className="text-teal-600 dark:text-teal-300">Duration</span>
                      <span className="font-semibold text-slate-700 dark:text-slate-200">
                        {proposedDays} {proposedDays === 1 ? 'day' : 'days'}
                        {originalDays > 0 && (
                          <span
                            className={`ml-2 text-xs ${
                              dayDelta === 0
                                ? 'text-teal-600 dark:text-teal-300'
                                : dayDelta > 0
                                  ? 'text-emerald-600 dark:text-emerald-300'
                                  : 'text-rose-600 dark:text-rose-300'
                            }`}
                          >
                            {dayDelta > 0 ? '+' : ''}
                            {dayDelta}
                          </span>
                        )}
                      </span>
                    </div>
                  </div>
                </div>

                <div className="grid gap-4 lg:grid-cols-[1.1fr_0.9fr]">
                  <div className="rounded-2xl border border-slate-200/60 bg-white/70 p-4 dark:border-slate-700/60 dark:bg-slate-900/40">
                    <div className="mb-3 flex items-center justify-between">
                      <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
                        Reason
                      </h3>
                      <span className="text-xs text-slate-400 dark:text-slate-500">
                        {requestingParent?.name || 'Parent'}'s note
                      </span>
                    </div>
                    <p className="text-sm leading-relaxed text-slate-600 dark:text-slate-300">
                      {selectedRequest.reason}
                    </p>
                  </div>

                  <div className="rounded-2xl border border-slate-200/60 bg-white/70 p-4 dark:border-slate-700/60 dark:bg-slate-900/40">
                    <h3 className="mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
                      Timeline
                    </h3>
                    <div className="space-y-3 text-sm text-slate-500 dark:text-slate-400">
                      <div className="flex items-center justify-between">
                        <span>Requested</span>
                        <span className="text-slate-700 dark:text-slate-200">
                          {formatDateTime(selectedRequest.requestedAt)}
                        </span>
                      </div>
                      <div className="flex items-center justify-between">
                        <span>Resolved</span>
                        <span className="text-slate-700 dark:text-slate-200">
                          {formatDateTime(selectedRequest.resolvedAt)}
                        </span>
                      </div>
                      {selectedRequest.responseNote && (
                        <div className="border-t border-slate-200/60 pt-3 dark:border-slate-700/60">
                          <p className="text-xs uppercase tracking-wide text-slate-400">
                            Response note
                          </p>
                          <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">
                            {selectedRequest.responseNote}
                          </p>
                        </div>
                      )}
                    </div>
                  </div>
                </div>

                {selectedRequest.status === 'pending' &&
                  selectedRequest.requestedBy !== currentParentId && (
                    <div className="rounded-2xl border border-slate-200/60 bg-slate-50/80 p-4 dark:border-slate-700/60 dark:bg-slate-800/50">
                      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
                        <div className="flex-1">
                          <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-200">
                            Response note (optional)
                          </label>
                          <textarea
                            value={responseNote}
                            onChange={(event) => setResponseNote(event.target.value)}
                            rows={3}
                            placeholder="Add context for your decision..."
                            className="w-full resize-none rounded-xl border border-slate-200/80 bg-white px-3 py-2 text-sm text-slate-700 outline-none transition-shadow placeholder:text-slate-400 focus:border-transparent focus:ring-2 focus:ring-teal-500 dark:border-slate-700/70 dark:bg-slate-900 dark:text-slate-100"
                          />
                        </div>
                        <div className="flex flex-col gap-3 sm:flex-row">
                          <button
                            onClick={() =>
                              onDeclineRequest?.(
                                selectedRequest.id,
                                responseNote.trim() || undefined,
                              )
                            }
                            className="rounded-xl border border-rose-200 px-5 py-2.5 text-sm font-semibold text-rose-600 transition-colors hover:bg-rose-50 dark:border-rose-700 dark:text-rose-300 dark:hover:bg-rose-900/20"
                          >
                            Decline
                          </button>
                          <button
                            onClick={() =>
                              onApproveRequest?.(
                                selectedRequest.id,
                                responseNote.trim() || undefined,
                              )
                            }
                            className="rounded-xl bg-teal-600 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-teal-500/20 transition-all hover:bg-teal-700 hover:shadow-xl hover:shadow-teal-500/30"
                          >
                            Approve change
                          </button>
                        </div>
                      </div>
                    </div>
                  )}

                {selectedRequest.status !== 'pending' && (
                  <div className="rounded-2xl border border-slate-200/60 bg-slate-50/80 p-4 dark:border-slate-700/60 dark:bg-slate-800/50">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="text-sm font-semibold text-slate-700 dark:text-slate-200">
                          Decision recorded
                        </p>
                        <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                          This request has already been {selectedRequest.status}.
                        </p>
                      </div>
                      <span
                        className={`rounded-full px-3 py-1.5 text-xs font-semibold ${STATUS_STYLES[selectedRequest.status]}`}
                      >
                        {selectedRequest.status}
                      </span>
                    </div>
                  </div>
                )}
              </div>
            ) : (
              <div className="p-8 text-center text-slate-500 dark:text-slate-400">
                Select a request to review.
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
