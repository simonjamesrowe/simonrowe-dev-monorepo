import { useMemo } from 'react';

import type { Event, Parent } from '../../types/calendar';

import { EventPill } from './EventPill';
import { getEventOwnerLabel } from './eventOwners';
import { expandRecurringEvents } from './recurrence';

interface MonthViewProps {
  currentDate: Date;
  events: Event[];
  parents: Record<string, Parent>;
  onDayClick: (date: Date) => void;
  onEventClick?: (eventId: string) => void;
}

interface DayData {
  date: Date;
  isCurrentMonth: boolean;
  isToday: boolean;
  custodyParent: Parent | null;
  custodyEventId: string | null;
  events: Event[];
}

export function MonthView({
  currentDate,
  events,
  parents,
  onDayClick,
  onEventClick,
}: MonthViewProps) {
  const weekDays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
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

  const calendarDays = useMemo(() => {
    const year = currentDate.getFullYear();
    const month = currentDate.getMonth();

    // First day of the month
    const firstDay = new Date(year, month, 1);
    // Last day of the month
    const lastDay = new Date(year, month + 1, 0);

    // Start from Sunday of the first week
    const startDate = new Date(firstDay);
    startDate.setDate(startDate.getDate() - firstDay.getDay());

    // End on Saturday of the last week
    const endDate = new Date(lastDay);
    endDate.setDate(endDate.getDate() + (6 - lastDay.getDay()));

    const days: DayData[] = [];
    const current = new Date(startDate);
    const today = new Date(2025, 0, 6); // Sample "today" for demo

    const expandedEvents = expandRecurringEvents(events, dateToYmd(startDate), dateToYmd(endDate));

    while (current <= endDate) {
      const dateStr = dateToYmd(current);

      // Find custody parent for this day
      const custodyEvent = expandedEvents.find((e) => {
        if (!isCustodyType(e.type)) return false;
        const start = toLocalDay(e.startDate);
        const end = e.endDate ? toLocalDay(e.endDate) : start;
        const currentDay = new Date(current);
        currentDay.setHours(12, 0, 0, 0);
        return currentDay >= start && currentDay <= end;
      });

      // Find non-custody events for this day
      const dayEvents = expandedEvents.filter((e) => {
        if (isCustodyType(e.type)) return false;
        return e.startDate === dateStr;
      });

      days.push({
        date: new Date(current),
        isCurrentMonth: current.getMonth() === month,
        isToday: current.getTime() === today.getTime(),
        custodyParent: custodyEvent?.parentId ? (parents[custodyEvent.parentId] ?? null) : null,
        custodyEventId: custodyEvent?.id ?? null,
        events: dayEvents,
      });

      current.setDate(current.getDate() + 1);
    }

    return days;
  }, [currentDate, events, parents]);

  const getCustodyGradient = (
    parent: Parent | null,
    position: 'start' | 'middle' | 'end' | 'single',
  ) => {
    if (!parent) return '';

    const colors =
      parent.color === 'violet'
        ? {
            bg: 'bg-violet-100 dark:bg-violet-900/30',
            border: 'border-violet-200 dark:border-violet-800',
          }
        : { bg: 'bg-sky-100 dark:bg-sky-900/30', border: 'border-sky-200 dark:border-sky-800' };

    let roundedClass = '';
    switch (position) {
      case 'start':
        roundedClass = 'rounded-l-xl';
        break;
      case 'end':
        roundedClass = 'rounded-r-xl';
        break;
      case 'single':
        roundedClass = 'rounded-xl';
        break;
      default:
        roundedClass = '';
    }

    return `${colors.bg} ${roundedClass}`;
  };

  // Determine custody block position for each day
  const getCustodyPosition = (dayIndex: number): 'start' | 'middle' | 'end' | 'single' | null => {
    const day = calendarDays[dayIndex];
    if (!day) return null;
    if (!day.custodyParent) return null;

    const prevDay = calendarDays[dayIndex - 1];
    const nextDay = calendarDays[dayIndex + 1];

    const hasPrev = prevDay?.custodyParent?.id === day.custodyParent.id;
    const hasNext = nextDay?.custodyParent?.id === day.custodyParent.id;

    if (!hasPrev && !hasNext) return 'single';
    if (!hasPrev && hasNext) return 'start';
    if (hasPrev && hasNext) return 'middle';
    if (hasPrev && !hasNext) return 'end';
    return null;
  };

  return (
    <div className="p-2 sm:p-4">
      {/* Week day headers */}
      <div className="mb-2 grid grid-cols-7">
        {weekDays.map((day) => (
          <div
            key={day}
            className="py-2 text-center text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400"
          >
            {day}
          </div>
        ))}
      </div>

      {/* Calendar grid */}
      <div className="grid grid-cols-7 gap-y-1">
        {calendarDays.map((day, index) => {
          const custodyPosition = getCustodyPosition(index);

          return (
            <div
              key={day.date.toISOString()}
              onClick={() => onDayClick(day.date)}
              className={`relative min-h-[80px] cursor-pointer p-1 transition-all duration-150 sm:min-h-[100px] ${!day.isCurrentMonth ? 'opacity-40' : ''} ${day.isToday ? 'z-10 rounded-xl ring-2 ring-inset ring-teal-500' : ''} hover:bg-slate-50 dark:hover:bg-slate-700/30 ${custodyPosition ? getCustodyGradient(day.custodyParent, custodyPosition) : ''} `}
            >
              {/* Day number */}
              <div
                className={`flex h-7 w-7 items-center justify-center text-sm font-medium ${
                  day.isToday
                    ? 'rounded-full bg-teal-600 text-white'
                    : day.isCurrentMonth
                      ? 'text-slate-700 dark:text-slate-200'
                      : 'text-slate-400 dark:text-slate-500'
                } `}
              >
                {day.date.getDate()}
              </div>

              {/* Custody indicator */}
              {day.custodyParent &&
                (custodyPosition === 'start' || custodyPosition === 'single') && (
                  <button
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      if (day.custodyEventId) {
                        onEventClick?.(day.custodyEventId);
                      }
                    }}
                    className="absolute right-1 top-1 rounded-full bg-white/80 px-2 py-0.5 text-[10px] font-medium text-slate-600 shadow-sm hover:bg-white"
                  >
                    {day.custodyParent.name}'s day
                  </button>
                )}

              {/* Events */}
              <div className="mt-1 space-y-0.5 overflow-hidden">
                {day.events.slice(0, 2).map((event) => (
                  <EventPill
                    key={event.id}
                    event={event}
                    ownerLabel={getEventOwnerLabel(event, parents)}
                    onClick={(e) => {
                      e.stopPropagation();
                      onEventClick?.(event.sourceId ?? event.id);
                    }}
                    compact
                  />
                ))}
                {day.events.length > 2 && (
                  <div className="pl-1 text-[10px] font-medium text-slate-500 dark:text-slate-400">
                    +{day.events.length - 2} more
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
