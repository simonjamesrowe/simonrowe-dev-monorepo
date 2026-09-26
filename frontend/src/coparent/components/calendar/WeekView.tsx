import { useMemo } from 'react';

import type { Event, Parent } from '../../types/calendar';

import { EventPill } from './EventPill';
import { getEventOwnerLabel } from './eventOwners';
import { dateToYmd as toYmd, expandRecurringEvents, occurrenceTarget } from './recurrence';
import { eventBox, gridHoursFor, isSameDay, nowOffset, useNow } from './timeGrid';

interface WeekViewProps {
  currentDate: Date;
  events: Event[];
  parents: Record<string, Parent>;
  onDayClick: (date: Date) => void;
  onEventClick?: (eventId: string, occurrenceDate?: string) => void;
}

const HOUR_PX = 64;
const DEFAULT_HOURS = { startHour: 7, endHour: 21 }; // 7 AM to 8 PM rows

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

  const now = useNow();

  const formatHour = (hour: number) => {
    if (hour === 0) return '12 AM';
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

  const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const expandedEvents = useMemo(() => {
    const rangeStart = weekDays[0] ?? currentDate;
    const rangeEnd = weekDays[6] ?? currentDate;
    return expandRecurringEvents(events, dateToYmd(rangeStart), dateToYmd(rangeEnd));
  }, [events, weekDays]);

  const weekYmds = useMemo(() => new Set(weekDays.map(toYmd)), [weekDays]);
  const hours = useMemo(
    () =>
      gridHoursFor(
        expandedEvents.filter(
          (e) => weekYmds.has(e.startDate) && e.type.trim().toLowerCase() !== 'custody',
        ),
        DEFAULT_HOURS,
      ),
    [expandedEvents, weekYmds],
  );
  const hourRows = Array.from(
    { length: hours.endHour - hours.startHour },
    (_, i) => i + hours.startHour,
  );
  const nowTop = weekDays.some((date) => isSameDay(date, now))
    ? nowOffset(now, hours, HOUR_PX)
    : null;

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
            const isToday = isSameDay(date, now);

            return (
              <div
                key={date.toISOString()}
                role="button"
                tabIndex={0}
                onClick={() => onDayClick(date)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    onDayClick(date);
                  }
                }}
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
      <div
        className="relative grid grid-cols-[60px_repeat(7,1fr)]"
        style={{ minHeight: `${hourRows.length * HOUR_PX}px` }}
      >
        {/* Time labels */}
        <div className="border-r border-slate-200 dark:border-slate-700">
          {hourRows.map((hour) => (
            // Nudged up with a transform, not a negative margin: margins accumulate down the
            // column, so each label drifted 8px further from its line and 6 PM sat over 8 PM.
            <div
              key={hour}
              className="pr-2 text-right text-xs text-slate-500 dark:text-slate-400"
              style={{ height: `${HOUR_PX}px`, transform: 'translateY(-0.5rem)' }}
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
              {hourRows.map((hour) => (
                <div
                  key={hour}
                  className="border-b border-slate-100 dark:border-slate-700/50"
                  style={{ height: `${HOUR_PX}px` }}
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
                      onClick={() => onEventClick?.(...occurrenceTarget(event))}
                      compact
                    />
                  ))}
                </div>
              )}

              {/* Timed events */}
              {timedEvents.map((event) => {
                const position = eventBox(event, hours, HOUR_PX, 24);
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
                      onClick={() => onEventClick?.(...occurrenceTarget(event))}
                      showTime
                    />
                  </div>
                );
              })}
            </div>
          );
        })}

        {nowTop !== null && (
          <div
            className="pointer-events-none absolute left-[60px] right-0 z-20 flex items-center"
            style={{ top: `${nowTop}px` }}
            data-testid="week-now-line"
          >
            <div className="-ml-1 h-2 w-2 rounded-full bg-rose-500" />
            <div className="h-0.5 flex-1 bg-rose-500" />
          </div>
        )}
      </div>
    </div>
  );
}
