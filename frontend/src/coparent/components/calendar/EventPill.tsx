import type { Event } from '../../types/calendar';

import { getEventTypeColor } from './eventTypeColors';

interface EventPillProps {
  event: Event;
  onClick?: (e: React.MouseEvent) => void;
  compact?: boolean;
  showTime?: boolean;
  ownerLabel?: string;
}

export function EventPill({
  event,
  onClick,
  compact = false,
  showTime = false,
  ownerLabel,
}: EventPillProps) {
  const color = getEventTypeColor(event.type);

  const colorMap = {
    red: {
      bg: 'bg-red-100 dark:bg-red-900/40 hover:bg-red-200 dark:hover:bg-red-900/60',
      text: 'text-red-700 dark:text-red-300',
      dot: 'bg-red-500',
    },
    amber: {
      bg: 'bg-amber-100 dark:bg-amber-900/40 hover:bg-amber-200 dark:hover:bg-amber-900/60',
      text: 'text-amber-700 dark:text-amber-300',
      dot: 'bg-amber-500',
    },
    green: {
      bg: 'bg-green-100 dark:bg-green-900/40 hover:bg-green-200 dark:hover:bg-green-900/60',
      text: 'text-green-700 dark:text-green-300',
      dot: 'bg-green-500',
    },
    emerald: {
      bg: 'bg-emerald-100 dark:bg-emerald-900/40 hover:bg-emerald-200 dark:hover:bg-emerald-900/60',
      text: 'text-emerald-700 dark:text-emerald-300',
      dot: 'bg-emerald-500',
    },
    teal: {
      bg: 'bg-teal-100 dark:bg-teal-900/40 hover:bg-teal-200 dark:hover:bg-teal-900/60',
      text: 'text-teal-700 dark:text-teal-300',
      dot: 'bg-teal-500',
    },
    pink: {
      bg: 'bg-pink-100 dark:bg-pink-900/40 hover:bg-pink-200 dark:hover:bg-pink-900/60',
      text: 'text-pink-700 dark:text-pink-300',
      dot: 'bg-pink-500',
    },
    indigo: {
      bg: 'bg-indigo-100 dark:bg-indigo-900/40 hover:bg-indigo-200 dark:hover:bg-indigo-900/60',
      text: 'text-indigo-700 dark:text-indigo-300',
      dot: 'bg-indigo-500',
    },
    slate: {
      bg: 'bg-slate-100 dark:bg-slate-700/60 hover:bg-slate-200 dark:hover:bg-slate-700',
      text: 'text-slate-700 dark:text-slate-300',
      dot: 'bg-slate-500',
    },
  } satisfies Record<string, { bg: string; text: string; dot: string }>;

  const colors = colorMap[color as keyof typeof colorMap] ?? colorMap.slate;

  if (compact) {
    return (
      <button
        onClick={onClick}
        className={`flex w-full items-center gap-1 rounded px-1.5 py-0.5 text-left transition-colors duration-150 ${colors.bg} `}
      >
        <span className={`h-1.5 w-1.5 flex-shrink-0 rounded-full ${colors.dot}`} />
        <span className={`truncate text-[10px] font-medium sm:text-xs ${colors.text}`}>
          {event.title}
        </span>
        {ownerLabel && (
          <span className="ml-auto text-[9px] uppercase tracking-wide text-slate-400">
            {ownerLabel}
          </span>
        )}
      </button>
    );
  }

  return (
    <button
      onClick={onClick}
      className={`flex h-full w-full flex-col rounded-lg p-2 text-left transition-all duration-150 ${colors.bg} hover:border-current/10 border border-transparent`}
    >
      <div className="flex items-center gap-1.5">
        <span className={`h-2 w-2 flex-shrink-0 rounded-full ${colors.dot}`} />
        <span className={`truncate text-xs font-medium sm:text-sm ${colors.text}`}>
          {event.title}
        </span>
      </div>

      {ownerLabel && (
        <div className="mt-0.5 text-[10px] text-slate-500 dark:text-slate-400">{ownerLabel}</div>
      )}

      {showTime && event.startTime && (
        <div className="mt-1 text-[10px] text-slate-500 dark:text-slate-400">
          {event.startTime} – {event.endTime}
        </div>
      )}

      {event.location && !compact && (
        <div className="mt-1 flex items-center gap-1 text-[10px] text-slate-500 dark:text-slate-400">
          <svg
            className="h-2.5 w-2.5 flex-shrink-0"
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
          </svg>
          <span className="truncate">{event.location}</span>
        </div>
      )}
    </button>
  );
}
