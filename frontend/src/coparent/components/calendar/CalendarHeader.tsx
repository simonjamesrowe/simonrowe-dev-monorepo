interface CalendarHeaderProps {
  currentDate: Date;
  viewMode: 'month' | 'week' | 'day';
  onViewChange: (view: 'month' | 'week' | 'day') => void;
  onNavigate: (direction: 'prev' | 'next' | 'today') => void;
}

export function CalendarHeader({
  currentDate,
  viewMode,
  onViewChange,
  onNavigate,
}: CalendarHeaderProps) {
  const formatTitle = () => {
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
    const month = months[currentDate.getMonth()] ?? '';
    const year = currentDate.getFullYear();

    switch (viewMode) {
      case 'month':
        return `${month} ${year}`;
      case 'week': {
        const startOfWeek = new Date(currentDate);
        startOfWeek.setDate(currentDate.getDate() - currentDate.getDay());
        const endOfWeek = new Date(startOfWeek);
        endOfWeek.setDate(startOfWeek.getDate() + 6);

        const startMonth = (months[startOfWeek.getMonth()] ?? '').slice(0, 3);
        const endMonth = (months[endOfWeek.getMonth()] ?? '').slice(0, 3);

        if (startOfWeek.getMonth() === endOfWeek.getMonth()) {
          return `${startMonth} ${startOfWeek.getDate()} – ${endOfWeek.getDate()}, ${year}`;
        }
        return `${startMonth} ${startOfWeek.getDate()} – ${endMonth} ${endOfWeek.getDate()}, ${year}`;
      }
      case 'day': {
        const weekdays = [
          'Sunday',
          'Monday',
          'Tuesday',
          'Wednesday',
          'Thursday',
          'Friday',
          'Saturday',
        ];
        return `${weekdays[currentDate.getDay()] ?? ''}, ${month} ${currentDate.getDate()}`;
      }
    }
  };

  const views: { id: 'month' | 'week' | 'day'; label: string }[] = [
    { id: 'month', label: 'Month' },
    { id: 'week', label: 'Week' },
    { id: 'day', label: 'Day' },
  ];

  return (
    <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      {/* Navigation Controls */}
      <div className="flex items-center gap-2 sm:gap-3">
        <button
          onClick={() => onNavigate('today')}
          className="rounded-lg border border-teal-200/60 bg-teal-50 px-3 py-1.5 text-sm font-medium text-teal-700 transition-colors hover:bg-teal-100 dark:border-teal-800/60 dark:bg-teal-900/30 dark:text-teal-400 dark:hover:bg-teal-900/50"
        >
          Today
        </button>

        <div className="flex items-center rounded-lg bg-slate-100 p-0.5 dark:bg-slate-800">
          <button
            onClick={() => onNavigate('prev')}
            className="rounded-md p-1.5 text-slate-600 transition-colors hover:bg-white hover:text-slate-800 dark:text-slate-400 dark:hover:bg-slate-700 dark:hover:text-slate-200"
            aria-label="Previous"
          >
            <svg
              className="h-5 w-5"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={2}
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
            </svg>
          </button>
          <button
            onClick={() => onNavigate('next')}
            className="rounded-md p-1.5 text-slate-600 transition-colors hover:bg-white hover:text-slate-800 dark:text-slate-400 dark:hover:bg-slate-700 dark:hover:text-slate-200"
            aria-label="Next"
          >
            <svg
              className="h-5 w-5"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={2}
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
            </svg>
          </button>
        </div>

        <h2 className="ml-2 text-lg font-semibold text-slate-800 sm:text-xl dark:text-slate-100">
          {formatTitle()}
        </h2>
      </div>

      {/* View Toggle */}
      <div className="flex items-center rounded-xl bg-slate-100 p-1 dark:bg-slate-800">
        {views.map((view) => (
          <button
            key={view.id}
            onClick={() => onViewChange(view.id)}
            className={`rounded-lg px-4 py-2 text-sm font-medium transition-all duration-200 ${
              viewMode === view.id
                ? 'bg-white text-teal-700 shadow-sm dark:bg-slate-700 dark:text-teal-400'
                : 'text-slate-600 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200'
            } `}
          >
            {view.label}
          </button>
        ))}
      </div>
    </div>
  );
}
