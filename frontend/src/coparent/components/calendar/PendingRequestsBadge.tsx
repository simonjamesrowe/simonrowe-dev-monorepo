interface PendingRequestsBadgeProps {
  count: number;
  onClick?: () => void;
}

export function PendingRequestsBadge({ count, onClick }: PendingRequestsBadgeProps) {
  return (
    <button
      onClick={onClick}
      className="group relative inline-flex items-center gap-2 rounded-xl border border-rose-200 bg-rose-50 px-4 py-2.5 text-rose-700 transition-all duration-200 hover:bg-rose-100 hover:shadow-lg hover:shadow-rose-500/10 dark:border-rose-800 dark:bg-rose-900/30 dark:text-rose-300 dark:hover:bg-rose-900/50"
    >
      {/* Animated ping effect */}
      <span className="absolute -right-1 -top-1 flex h-4 w-4">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-rose-400 opacity-75" />
        <span className="relative inline-flex h-4 w-4 items-center justify-center rounded-full bg-rose-500 text-[10px] font-bold text-white">
          {count}
        </span>
      </span>

      {/* Bell icon */}
      <svg
        className="h-5 w-5 group-hover:animate-[wiggle_0.3s_ease-in-out]"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        strokeWidth={2}
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"
        />
      </svg>

      <span className="text-sm font-medium">
        {count} pending {count === 1 ? 'request' : 'requests'}
      </span>
    </button>
  );
}
