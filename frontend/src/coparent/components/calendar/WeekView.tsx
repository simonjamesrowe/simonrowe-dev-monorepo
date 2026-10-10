import { useMemo } from 'react';

import type { Event, Parent } from '../../types/calendar';

import { EventPill } from './EventPill';
import { getEventOwnerLabel } from './eventOwners';
import { dateToYmd as toYmd, expandRecurringEvents, occurrenceTarget } from './recurrence';
import {
  eventBox,
  gridHoursFor,
  isSameDay,
  laneStyle,
  nowOffset,
  overlapLanes,
  useNow,
} from './timeGrid';

interface WeekViewProps {
  currentDate: Date;
  events: Event[];
  parents: Record<string, Parent>;
  onDayClick: (date: Date) => void;
  onEventClick?: (eventId: string, occurrenceDate?: string) => void;
}

const HOUR_PX = 64;
const MIN_BOX_PX = 24;
const DEFAULT_HOURS = { startHour: 7, endHour: 21 }; // 7 AM to 8 PM rows
// The header and the grid share one column template. With plain `1fr` a header cell's content
// sets a minimum width the empty grid columns below it do not have, so on a phone the two drifted
// apart and events sat under the wrong day. `minmax(0, 1fr)` plus a minimum width for the whole
// week keeps them locked together; a narrow screen scrolls sideways instead.
const WEEK_COLUMNS = '60px repeat(7, minmax(0, 1fr))';
const WEEK_MIN_WIDTH = '40rem';
const ALL_DAY_SHOWN = 2;

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
  const isTimed = (event: Event) => Boolean(event.startTime) && !event.allDay;

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
          (e) => weekYmds.has(e.startDate) && !isCustodyType(e.type) && isTimed(e),
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
      <div style={{ minWidth: WEEK_MIN_WIDTH }}>
        {/* Header with days */}
        <div className="sticky top-0 z-10 border-b border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-800">
          <div className="grid" style={{ gridTemplateColumns: WEEK_COLUMNS }}>
            <div className="p-2" /> {/* Empty corner */}
            {weekDays.map((date, i) => {
              const custodyEvent = getCustodyForDay(date, expandedEvents);
              const custodyParent = custodyEvent?.parentId
                ? (parents[custodyEvent.parentId] ?? null)
                : null;
              const isToday = isSameDay(date, now);
              // All-day events live in the header, so they never cover the first hours of the grid.
              const allDay = getEventsForDay(date, expandedEvents).filter((e) => !isTimed(e));

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
                  {allDay.length > 0 && (
                    <div className="mt-2 space-y-1 text-left">
                      {allDay.slice(0, ALL_DAY_SHOWN).map((event) => (
                        <EventPill
                          key={event.id}
                          event={event}
                          ownerLabel={getEventOwnerLabel(event, parents)}
                          onClick={(clickEvent) => {
                            clickEvent.stopPropagation();
                            onEventClick?.(...occurrenceTarget(event));
                          }}
                          compact
                        />
                      ))}
                      {allDay.length > ALL_DAY_SHOWN && (
                        <button
                          type="button"
                          onClick={(clickEvent) => {
                            clickEvent.stopPropagation();
                            onDayClick(date);
                          }}
                          className="w-full pl-1 text-left text-[10px] font-medium text-slate-500 hover:text-teal-600 dark:text-slate-400"
                        >
                          +{allDay.length - ALL_DAY_SHOWN} more
                        </button>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        {/* Time grid */}
        {/* The top padding leaves room for the first hour's label, which sits across its line. */}
        <div
          className="relative grid pt-2"
          style={{ gridTemplateColumns: WEEK_COLUMNS, minHeight: `${hourRows.length * HOUR_PX}px` }}
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
            const timedEvents = getEventsForDay(date, expandedEvents).filter(isTimed);
            const lanes = overlapLanes(timedEvents, (MIN_BOX_PX / HOUR_PX) * 60);
            const showNow = nowTop !== null && isSameDay(date, now);

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

                {/* Timed events, side by side where they overlap */}
                {timedEvents.map((event) => {
                  const position = eventBox(event, hours, HOUR_PX, MIN_BOX_PX);
                  if (!position) return null;
                  const lane = lanes.get(event.id);
                  // A third of a day column has no room for the full card, whatever its height.
                  const dense = position.height < 48 || (lane?.lanes ?? 1) >= 3;

                  return (
                    <div
                      key={event.id}
                      className="absolute"
                      style={{
                        top: `${position.top}px`,
                        height: `${position.height}px`,
                        ...laneStyle(lane, 4, 4),
                      }}
                    >
                      <EventPill
                        event={event}
                        ownerLabel={getEventOwnerLabel(event, parents)}
                        onClick={() => onEventClick?.(...occurrenceTarget(event))}
                        dense={dense}
                        showTime
                      />
                    </div>
                  );
                })}

                {/* The current time belongs to today, not to the whole week. */}
                {showNow && (
                  <div
                    className="pointer-events-none absolute left-0 right-0 z-20 flex items-center"
                    style={{ top: `${nowTop}px` }}
                    data-testid="week-now-line"
                  >
                    <div className="-ml-1 h-2 w-2 rounded-full bg-rose-500" />
                    <div className="h-0.5 flex-1 bg-rose-500" />
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
