import { useState, useMemo } from 'react';

import type { CalendarSchedulingProps, Parent } from '../../types/calendar';

import { CalendarHeader } from './CalendarHeader';
import { DayView } from './DayView';
import { MonthView } from './MonthView';
import { PendingRequestsBadge } from './PendingRequestsBadge';
import { dateToYmd as toYmd, expandRecurringEvents } from './recurrence';
import { WeekView } from './WeekView';

type ViewMode = 'month' | 'week' | 'day';

export function CalendarView({
  parents,
  children,
  events,
  scheduleChangeRequests,
  currentParentId,
  onViewEvent,
  onCreateEvent,
  onViewRequest,
  onChangeView,
  onNavigateDate,
}: CalendarSchedulingProps) {
  const [viewMode, setViewMode] = useState<ViewMode>('month');
  const [currentDate, setCurrentDate] = useState(new Date());
  const dateToYmd = (date: Date) => {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  };

  const pendingRequests = scheduleChangeRequests.filter((r) => r.status === 'pending');
  const incomingRequests = pendingRequests.filter((r) => r.requestedBy !== currentParentId);
  const [firstIncomingRequest] = incomingRequests;

  const handleViewChange = (view: ViewMode) => {
    setViewMode(view);
    onChangeView?.(view);
  };

  const handleNavigate = (direction: 'prev' | 'next' | 'today') => {
    let newDate: Date;

    if (direction === 'today') {
      newDate = new Date(); // Current date
    } else {
      const offset = direction === 'prev' ? -1 : 1;
      switch (viewMode) {
        case 'month':
          newDate = new Date(currentDate.getFullYear(), currentDate.getMonth() + offset, 1);
          break;
        case 'week':
          newDate = new Date(currentDate.getTime() + offset * 7 * 24 * 60 * 60 * 1000);
          break;
        case 'day':
          newDate = new Date(currentDate.getTime() + offset * 24 * 60 * 60 * 1000);
          break;
        default:
          newDate = currentDate;
      }
    }

    setCurrentDate(newDate);
    onNavigateDate?.(dateToYmd(newDate));
  };

  const handleDayClick = (date: Date) => {
    setCurrentDate(date);
    if (viewMode === 'month') {
      setViewMode('day');
      onChangeView?.('day');
    }
    onNavigateDate?.(dateToYmd(date));
  };

  const parentsMap = useMemo(() => {
    const map: Record<string, Parent> = {};
    parents.forEach((p) => {
      map[p.id] = p;
    });
    return map;
  }, [parents]);

  const handleCreateEventClick = () => {
    // From the day view a new event belongs to the day being looked at, not to today.
    onCreateEvent?.(viewMode === 'day' ? dateToYmd(currentDate) : undefined);
  };

  const normalizeType = (value: string) => value.trim().toLowerCase();
  // The footer counts what is on the calendar this month, so a weekly series counts once per
  // occurrence; counting stored events would miss every series that started in an earlier month.
  const monthOccurrences = useMemo(() => {
    const first = new Date(currentDate.getFullYear(), currentDate.getMonth(), 1);
    const last = new Date(currentDate.getFullYear(), currentDate.getMonth() + 1, 0);
    return expandRecurringEvents(events, toYmd(first), toYmd(last)).filter(
      (event) => event.startDate.slice(0, 7) === toYmd(first).slice(0, 7),
    );
  }, [events, currentDate]);

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-white to-teal-50/30 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
      {/* Subtle pattern overlay */}
      <div
        className="pointer-events-none fixed inset-0 opacity-[0.015] dark:opacity-[0.03]"
        style={{
          backgroundImage: `radial-gradient(circle at 1px 1px, currentColor 1px, transparent 0)`,
          backgroundSize: '24px 24px',
        }}
      />

      <div className="relative mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8 lg:px-8">
        {/* Header Section */}
        <div className="mb-6 sm:mb-8">
          <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-2xl font-semibold tracking-tight text-slate-800 sm:text-3xl dark:text-slate-100">
                Family Calendar
              </h1>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                Shared schedule for {children.map((c) => c.name).join(' & ')}
              </p>
            </div>

            <div className="flex items-center gap-3">
              {/* Pending Requests Badge */}
              {scheduleChangeRequests.length > 0 && incomingRequests.length === 0 && (
                <button
                  type="button"
                  onClick={() => onViewRequest?.()}
                  className="rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700"
                >
                  Change requests
                </button>
              )}
              {incomingRequests.length > 0 && (
                <PendingRequestsBadge
                  count={incomingRequests.length}
                  onClick={() => {
                    if (firstIncomingRequest) {
                      onViewRequest?.(firstIncomingRequest.id);
                    }
                  }}
                />
              )}

              {/* Add Event Button */}
              <button
                onClick={handleCreateEventClick}
                className="inline-flex items-center gap-2 rounded-xl bg-teal-600 px-4 py-2.5 text-sm font-medium text-white shadow-lg shadow-teal-500/20 transition-all duration-200 hover:-translate-y-0.5 hover:bg-teal-700 hover:shadow-xl hover:shadow-teal-500/30 active:translate-y-0"
              >
                <svg
                  className="h-4 w-4"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                </svg>
                <span className="hidden sm:inline">Add Event</span>
              </button>
            </div>
          </div>

          {/* Parent Legend */}
          <div className="mb-6 flex flex-wrap items-center gap-4">
            {parents.map((parent) => (
              <div key={parent.id} className="flex items-center gap-2">
                <div
                  className={`h-3 w-3 rounded-full ${
                    parent.color === 'violet' ? 'bg-violet-500' : 'bg-sky-500'
                  }`}
                />
                <span className="text-sm text-slate-600 dark:text-slate-400">
                  {parent.name}'s time
                </span>
              </div>
            ))}
          </div>

          {/* Calendar Navigation Header */}
          <CalendarHeader
            currentDate={currentDate}
            viewMode={viewMode}
            onViewChange={handleViewChange}
            onNavigate={handleNavigate}
          />
        </div>

        {/* Calendar Body */}
        <div className="overflow-hidden rounded-2xl border border-slate-200/60 bg-white shadow-xl shadow-slate-200/50 backdrop-blur-sm dark:border-slate-700/60 dark:bg-slate-800/50 dark:shadow-slate-900/50">
          {viewMode === 'month' && (
            <MonthView
              currentDate={currentDate}
              events={events}
              parents={parentsMap}
              onDayClick={handleDayClick}
              onEventClick={onViewEvent}
            />
          )}
          {viewMode === 'week' && (
            <WeekView
              currentDate={currentDate}
              events={events}
              parents={parentsMap}
              onDayClick={handleDayClick}
              onEventClick={onViewEvent}
            />
          )}
          {viewMode === 'day' && (
            <DayView
              currentDate={currentDate}
              events={events}
              parents={parentsMap}
              children={children}
              onEventClick={onViewEvent}
              onCreateEvent={handleCreateEventClick}
            />
          )}
        </div>

        {/* Quick Stats Footer */}
        <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            {
              label: 'This Month',
              value: monthOccurrences.length,
              suffix: 'events',
            },
            {
              label: 'Activities',
              value: monthOccurrences.filter((e) => normalizeType(e.type) === 'activity').length,
              suffix: 'scheduled',
            },
            {
              label: 'Medical',
              value: monthOccurrences.filter((e) => normalizeType(e.type) === 'medical').length,
              suffix: 'appointments',
            },
            { label: 'Pending', value: pendingRequests.length, suffix: 'requests' },
          ].map((stat, i) => (
            <div
              key={stat.label}
              className="rounded-xl border border-slate-200/40 bg-white/60 p-3 backdrop-blur-sm dark:border-slate-700/40 dark:bg-slate-800/30"
              style={{ animationDelay: `${i * 50}ms` }}
            >
              <p className="text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
                {stat.label}
              </p>
              <p className="mt-1 text-lg font-semibold text-slate-800 dark:text-slate-100">
                {stat.value}{' '}
                <span className="text-xs font-normal text-slate-400">{stat.suffix}</span>
              </p>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
