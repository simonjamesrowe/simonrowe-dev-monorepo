import { useMemo } from 'react';

import type { Event, Parent, Child } from '../../types/calendar';

import { getEventOwnerLabel } from './eventOwners';
import { getEventTypeColor } from './eventTypeColors';
import { expandRecurringEvents } from './recurrence';

interface DayViewProps {
  currentDate: Date;
  events: Event[];
  parents: Record<string, Parent>;
  children: Child[];
  onEventClick?: (eventId: string) => void;
  onCreateEvent?: () => void;
}

const HOURS = Array.from({ length: 16 }, (_, i) => i + 6); // 6 AM to 9 PM

export function DayView({
  currentDate,
  events,
  parents,
  children,
  onEventClick,
  onCreateEvent,
}: DayViewProps) {
  const dateToYmd = (date: Date) => {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  };
  const dateStr = dateToYmd(currentDate);
  const isCustodyType = (value: string) => value.trim().toLowerCase() === 'custody';
  const toLocalDay = (value: string) => {
    const ymd = value.includes('T') ? value.slice(0, 10) : value;
    return new Date(`${ymd}T12:00:00`);
  };

  const expandedEvents = useMemo(() => {
    return expandRecurringEvents(events, dateToYmd(currentDate), dateToYmd(currentDate));
  }, [events, currentDate]);

  const dayEvents = useMemo(() => {
    return expandedEvents.filter((e) => {
      if (isCustodyType(e.type)) return false;
      return e.startDate === dateStr;
    });
  }, [expandedEvents, dateStr]);

  const custodyEvent = useMemo(() => {
    return expandedEvents.find((e) => {
      if (!isCustodyType(e.type)) return false;
      const start = toLocalDay(e.startDate);
      const end = e.endDate ? toLocalDay(e.endDate) : start;
      const day = new Date(currentDate);
      day.setHours(12, 0, 0, 0);
      return day >= start && day <= end;
    });
  }, [expandedEvents, currentDate]);

  const custodyParent = custodyEvent?.parentId ? parents[custodyEvent.parentId] : null;

  const formatHour = (hour: number) => {
    if (hour === 0) return '12 AM';
    if (hour === 12) return '12 PM';
    if (hour > 12) return `${hour - 12} PM`;
    return `${hour} AM`;
  };

  const getEventPosition = (event: Event) => {
    if (!event.startTime) return null;

    const [startHour = 0, startMin = 0] = event.startTime.split(':').map(Number);
    const [endHour, endMin] = event.endTime
      ? event.endTime.split(':').map(Number)
      : [startHour + 1, startMin];
    const resolvedEndHour = endHour ?? startHour + 1;
    const resolvedEndMin = endMin ?? startMin;

    const top = (((startHour - 6) * 60 + startMin) / 60) * 80; // 80px per hour
    const height = (((resolvedEndHour - startHour) * 60 + (resolvedEndMin - startMin)) / 60) * 80;

    return { top, height: Math.max(height, 40) };
  };

  const getEventColors = (eventType: string) => {
    const color = getEventTypeColor(eventType);
    const colorMap = {
      red: {
        bg: 'bg-red-50 dark:bg-red-900/30',
        text: 'text-red-700 dark:text-red-300',
        border: 'border-red-200 dark:border-red-800',
      },
      amber: {
        bg: 'bg-amber-50 dark:bg-amber-900/30',
        text: 'text-amber-700 dark:text-amber-300',
        border: 'border-amber-200 dark:border-amber-800',
      },
      green: {
        bg: 'bg-green-50 dark:bg-green-900/30',
        text: 'text-green-700 dark:text-green-300',
        border: 'border-green-200 dark:border-green-800',
      },
      emerald: {
        bg: 'bg-emerald-50 dark:bg-emerald-900/30',
        text: 'text-emerald-700 dark:text-emerald-300',
        border: 'border-emerald-200 dark:border-emerald-800',
      },
      teal: {
        bg: 'bg-teal-50 dark:bg-teal-900/30',
        text: 'text-teal-700 dark:text-teal-300',
        border: 'border-teal-200 dark:border-teal-800',
      },
      pink: {
        bg: 'bg-pink-50 dark:bg-pink-900/30',
        text: 'text-pink-700 dark:text-pink-300',
        border: 'border-pink-200 dark:border-pink-800',
      },
      indigo: {
        bg: 'bg-indigo-50 dark:bg-indigo-900/30',
        text: 'text-indigo-700 dark:text-indigo-300',
        border: 'border-indigo-200 dark:border-indigo-800',
      },
      slate: {
        bg: 'bg-slate-50 dark:bg-slate-800',
        text: 'text-slate-700 dark:text-slate-300',
        border: 'border-slate-200 dark:border-slate-700',
      },
    } satisfies Record<string, { bg: string; text: string; border: string }>;

    return colorMap[color as keyof typeof colorMap] ?? colorMap.slate;
  };

  const timedEvents = dayEvents.filter((e) => e.startTime);
  const allDayEvents = dayEvents.filter((e) => e.allDay || !e.startTime);

  const weekdays = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  const months = [
    'January',
    'February',
    'March',
    'April',
    'May',
    'June',
    'July',
    'August',
    'September',
    'October',
    'November',
    'December',
  ];

  return (
    <div className="flex flex-col lg:flex-row">
      {/* Sidebar: Day summary */}
      <div className="border-b border-slate-200 bg-slate-50/50 p-6 lg:w-72 lg:border-b-0 lg:border-r dark:border-slate-700 dark:bg-slate-800/30">
        {/* Date display */}
        <div className="mb-6 text-center lg:text-left">
          <div className="text-sm uppercase tracking-wide text-slate-500 dark:text-slate-400">
            {weekdays[currentDate.getDay()] ?? ''}
          </div>
          <div className="my-2 text-5xl font-bold text-slate-800 dark:text-slate-100">
            {currentDate.getDate()}
          </div>
          <div className="text-slate-600 dark:text-slate-300">
            {months[currentDate.getMonth()] ?? ''} {currentDate.getFullYear()}
          </div>
        </div>

        {/* Custody info */}
        {custodyParent && (
          <button
            type="button"
            onClick={() => {
              if (custodyEvent) {
                onEventClick?.(custodyEvent.id);
              }
            }}
            className={`mb-6 w-full rounded-xl border p-4 text-left transition ${
              custodyParent.color === 'violet'
                ? 'border-violet-200 bg-violet-50 dark:border-violet-800 dark:bg-violet-900/20'
                : 'border-sky-200 bg-sky-50 dark:border-sky-800 dark:bg-sky-900/20'
            } hover:shadow-sm`}
          >
            <div className="flex items-center gap-3">
              <div
                className={`flex h-10 w-10 items-center justify-center rounded-full font-semibold text-white ${custodyParent.color === 'violet' ? 'bg-violet-500' : 'bg-sky-500'} `}
              >
                {custodyParent.name[0]}
              </div>
              <div>
                <div
                  className={`text-sm font-medium ${
                    custodyParent.color === 'violet'
                      ? 'text-violet-700 dark:text-violet-300'
                      : 'text-sky-700 dark:text-sky-300'
                  } `}
                >
                  {custodyParent.name}'s day
                </div>
                <div className="text-xs text-slate-500 dark:text-slate-400">
                  {children.map((c) => c.name).join(' & ')} with {custodyParent.name}
                </div>
              </div>
            </div>
          </button>
        )}

        {/* All-day events */}
        {allDayEvents.length > 0 && (
          <div className="mb-6">
            <h3 className="mb-3 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
              All Day
            </h3>
            <div className="space-y-2">
              {allDayEvents.map((event) => {
                const colors = getEventColors(event.type);
                return (
                  <button
                    key={event.id}
                    onClick={() => onEventClick?.(event.sourceId ?? event.id)}
                    className={`w-full rounded-xl border p-3 text-left transition-all duration-150 hover:shadow-md ${colors.bg} ${colors.border} `}
                  >
                    <div className={`text-sm font-medium ${colors.text}`}>{event.title}</div>
                    {getEventOwnerLabel(event, parents) && (
                      <div className="mt-0.5 text-[11px] text-slate-500 dark:text-slate-400">
                        {getEventOwnerLabel(event, parents)}
                      </div>
                    )}
                    {event.location && (
                      <div className="mt-1 flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400">
                        <svg
                          className="h-3 w-3"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"
                          />
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"
                          />
                        </svg>
                        {event.location}
                      </div>
                    )}
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* Quick add button */}
        <button
          onClick={onCreateEvent}
          className="flex w-full items-center justify-center gap-2 rounded-xl border-2 border-dashed border-slate-300 px-4 py-3 text-sm font-medium text-slate-500 transition-colors hover:border-teal-500 hover:text-teal-600 dark:border-slate-600 dark:text-slate-400 dark:hover:border-teal-500 dark:hover:text-teal-400"
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
          Add event
        </button>
      </div>

      {/* Main: Time grid */}
      <div className="flex-1 overflow-x-auto">
        <div className="relative min-h-[1280px]">
          {' '}
          {/* 16 hours × 80px */}
          {/* Hour lines */}
          {HOURS.map((hour) => (
            <div
              key={hour}
              className="absolute left-0 right-0 flex items-start border-t border-slate-100 dark:border-slate-700/50"
              style={{ top: `${(hour - 6) * 80}px`, height: '80px' }}
            >
              <div className="-mt-2 w-16 flex-shrink-0 pr-2 text-right text-xs text-slate-400 dark:text-slate-500">
                {formatHour(hour)}
              </div>
              <div className="flex-1" />
            </div>
          ))}
          {/* Timed events */}
          {timedEvents.map((event) => {
            const position = getEventPosition(event);
            if (!position) return null;

            const colors = getEventColors(event.type);
            const eventChildren = children.filter((c) => event.childIds.includes(c.id));

            return (
              <button
                key={event.id}
                onClick={() => onEventClick?.(event.sourceId ?? event.id)}
                className={`absolute left-20 right-4 rounded-xl border p-3 text-left transition-all duration-150 hover:scale-[1.02] hover:shadow-lg ${colors.bg} ${colors.border} `}
                style={{
                  top: `${position.top}px`,
                  minHeight: `${position.height}px`,
                }}
              >
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <div className={`font-semibold ${colors.text}`}>{event.title}</div>
                    {getEventOwnerLabel(event, parents) && (
                      <div className="mt-0.5 text-[11px] text-slate-500 dark:text-slate-400">
                        {getEventOwnerLabel(event, parents)}
                      </div>
                    )}
                    <div className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                      {event.startTime} – {event.endTime}
                    </div>
                  </div>
                  {eventChildren.length > 0 && (
                    <div className="flex -space-x-1">
                      {eventChildren.map((child) => (
                        <div
                          key={child.id}
                          className="flex h-6 w-6 items-center justify-center rounded-full border-2 border-white bg-slate-200 text-[10px] font-medium text-slate-600 dark:border-slate-800 dark:bg-slate-600 dark:text-slate-300"
                          title={child.name}
                        >
                          {child.name[0]}
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {event.location && position.height > 60 && (
                  <div className="mt-2 flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400">
                    <svg
                      className="h-3 w-3 flex-shrink-0"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"
                      />
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"
                      />
                    </svg>
                    <span className="truncate">{event.location}</span>
                  </div>
                )}

                {event.notes && position.height > 80 && (
                  <div className="mt-2 line-clamp-2 text-xs text-slate-500 dark:text-slate-400">
                    {event.notes}
                  </div>
                )}
              </button>
            );
          })}
          {/* Current time indicator */}
          <div
            className="pointer-events-none absolute left-16 right-0 z-20 flex items-center"
            style={{ top: `${(((9 - 6) * 60 + 30) / 60) * 80}px` }} // 9:30 AM for demo
          >
            <div className="-ml-1.5 h-3 w-3 rounded-full bg-rose-500 shadow-lg shadow-rose-500/50" />
            <div className="h-0.5 flex-1 bg-rose-500 shadow-sm shadow-rose-500/50" />
          </div>
        </div>
      </div>
    </div>
  );
}
