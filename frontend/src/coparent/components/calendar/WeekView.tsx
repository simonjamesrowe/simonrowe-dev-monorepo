import { useMemo } from 'react';

import type { Event, Parent } from '../../types/calendar';

import { EventPill } from './EventPill';
import { getEventOwnerLabel } from './eventOwners';
import { expandRecurringEvents } from './recurrence';

interface WeekViewProps {
  currentDate: Date;
  events: Event[];
  parents: Record<string, Parent>;
  onDayClick: (date: Date) => void;
  onEventClick?: (eventId: string) => void;
}

const HOURS = Array.from({ length: 14 }, (_, i) => i + 7); // 7 AM to 8 PM

export function WeekView({
  currentDate,
  events,
  parents,
  onDayClick,
  onEventClick,
}: WeekViewProps) {
  const isCustodyType = (value: string) => value.trim().toLowerCase() === 'custody';
  const toLocalDay = (value: string) => {
    const ymd = value.includes('T') ? value.slice(0, 10) : value;
    return new Date(`${ymd}T12:00:00`);
  };
  const dateToYmd = (date: Date) => {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  };
  const weekDays = useMemo(() => {
    const startOfWeek = new Date(currentDate);
    startOfWeek.setDate(currentDate.getDate() - currentDate.getDay());

    const days = [];
    for (let i = 0; i < 7; i++) {
      const date = new Date(startOfWeek);
      date.setDate(startOfWeek.getDate() + i);
      days.push(date);
    }
    return days;
  }, [currentDate]);

  const today = new Date(2025, 0, 6); // Sample "today"

  const formatHour = (hour: number) => {
    if (hour === 12) return '12 PM';
    if (hour > 12) return `${hour - 12} PM`;
    return `${hour} AM`;
  };

  const getEventsForDay = (date: Date, list: Event[]) => {
    const dateStr = dateToYmd(date);
    return list.filter((e) => e.startDate === dateStr && !isCustodyType(e.type));
  };

  const getCustodyForDay = (date: Date, list: Event[]) => {
    return list.find((e) => {
      if (!isCustodyType(e.type)) return false;
      const start = toLocalDay(e.startDate);
      const end = e.endDate ? toLocalDay(e.endDate) : start;
      const day = new Date(date);
      day.setHours(12, 0, 0, 0);
      return day >= start && day <= end;
    });
  };

  const getEventPosition = (event: Event) => {
    if (!event.startTime) return null;

    const [startHour = 0, startMin = 0] = event.startTime.split(':').map(Number);
    const [endHour, endMin] = event.endTime
      ? event.endTime.split(':').map(Number)
      : [startHour + 1, startMin];
    const resolvedEndHour = endHour ?? startHour + 1;
    const resolvedEndMin = endMin ?? startMin;

    const top = (((startHour - 7) * 60 + startMin) / 60) * 64; // 64px per hour
    const height = (((resolvedEndHour - startHour) * 60 + (resolvedEndMin - startMin)) / 60) * 64;

    return { top, height: Math.max(height, 24) };
  };

  const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const expandedEvents = useMemo(() => {
    const rangeStart = weekDays[0] ?? currentDate;
    const rangeEnd = weekDays[6] ?? currentDate;
    return expandRecurringEvents(events, dateToYmd(rangeStart), dateToYmd(rangeEnd));
  }, [events, weekDays]);

  return (
    <div className="overflow-x-auto">
      {/* Header with days */}
      <div className="sticky top-0 z-10 border-b border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-800">
        <div className="grid grid-cols-[60px_repeat(7,1fr)]">
          <div className="p-2" /> {/* Empty corner */}
          {weekDays.map((date, i) => {
            const custodyEvent = getCustodyForDay(date, expandedEvents);
            const custodyParent = custodyEvent?.parentId
              ? (parents[custodyEvent.parentId] ?? null)
              : null;
            const isToday = date.getTime() === today.getTime();

            return (
              <div
                key={date.toISOString()}
                onClick={() => onDayClick(date)}
                className={`cursor-pointer p-3 text-center transition-colors hover:bg-slate-50 dark:hover:bg-slate-700/50 ${i < 6 ? 'border-r border-slate-200 dark:border-slate-700' : ''} `}
              >
                <div className="text-xs font-medium uppercase text-slate-500 dark:text-slate-400">
                  {dayNames[date.getDay()] ?? ''}
                </div>
                <div
                  className={`mx-auto mt-1 flex h-10 w-10 items-center justify-center text-lg font-semibold ${
                    isToday
                      ? 'rounded-full bg-teal-600 text-white'
                      : 'text-slate-800 dark:text-slate-100'
                  } `}
                >
                  {date.getDate()}
                </div>
                {custodyParent && (
                  <button
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      if (custodyEvent) {
                        onEventClick?.(custodyEvent.id);
                      }
                    }}
                    className={`mt-2 inline-block rounded-full px-2 py-1 text-[10px] font-medium ${
                      custodyParent.color === 'violet'
                        ? 'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300'
                        : 'bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300'
                    } `}
                  >
                    {custodyParent.name}'s day
                  </button>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* Time grid */}
      <div className="relative grid min-h-[896px] grid-cols-[60px_repeat(7,1fr)]">
        {/* Time labels */}
        <div className="border-r border-slate-200 dark:border-slate-700">
          {HOURS.map((hour) => (
            <div
              key={hour}
              className="-mt-2 h-16 pr-2 text-right text-xs text-slate-500 dark:text-slate-400"
            >
              {formatHour(hour)}
            </div>
          ))}
        </div>

        {/* Day columns */}
        {weekDays.map((date, dayIndex) => {
          const dayEvents = getEventsForDay(date, expandedEvents);
          const timedEvents = dayEvents.filter((e) => e.startTime);
          const allDayEvents = dayEvents.filter((e) => e.allDay || !e.startTime);

          return (
            <div
              key={date.toISOString()}
              className={`relative ${dayIndex < 6 ? 'border-r border-slate-200 dark:border-slate-700' : ''} `}
            >
              {/* Hour lines */}
              {HOURS.map((hour) => (
                <div
                  key={hour}
                  className="h-16 border-b border-slate-100 dark:border-slate-700/50"
                />
              ))}

              {/* All-day events at top */}
              {allDayEvents.length > 0 && (
                <div className="absolute left-1 right-1 top-0 space-y-1 pt-1">
                  {allDayEvents.slice(0, 2).map((event) => (
                    <EventPill
                      key={event.id}
                      event={event}
                      ownerLabel={getEventOwnerLabel(event, parents)}
                      onClick={() => onEventClick?.(event.sourceId ?? event.id)}
                      compact
                    />
                  ))}
                </div>
              )}

              {/* Timed events */}
              {timedEvents.map((event) => {
                const position = getEventPosition(event);
                if (!position) return null;

                return (
                  <div
                    key={event.id}
                    className="absolute left-1 right-1"
                    style={{
                      top: `${position.top}px`,
                      height: `${position.height}px`,
                    }}
                  >
                    <EventPill
                      event={event}
                      ownerLabel={getEventOwnerLabel(event, parents)}
                      onClick={() => onEventClick?.(event.sourceId ?? event.id)}
                      showTime
                    />
                  </div>
                );
              })}
            </div>
          );
        })}

        {/* Current time indicator */}
        <div
          className="pointer-events-none absolute left-[60px] right-0 z-20 flex items-center"
          style={{ top: `${(((9 - 7) * 60 + 30) / 60) * 64}px` }} // 9:30 AM for demo
        >
          <div className="-ml-1 h-2 w-2 rounded-full bg-rose-500" />
          <div className="h-0.5 flex-1 bg-rose-500" />
        </div>
      </div>
    </div>
  );
}
