import type { MouseEvent, PointerEvent } from 'react';
import {
  useMemo,
  useState,
  useEffect,
  useCallback,
  forwardRef,
  useImperativeHandle,
  useRef,
} from 'react';

import type { Child, Event, Parent, RecurringPattern } from '../../types/calendar';

import { EventPill } from './EventPill';
import { DEFAULT_EVENT_TYPES } from './eventTypeColors';

export interface EventCreationFormProps {
  parents: Parent[];
  children: Child[];
  currentParentId: string;
  initialDate?: string;
  initialEvent?: Partial<Event>;
  onSubmit?: (data: Omit<Event, 'id'>) => void | Promise<void>;
  onCancel?: () => void;
  onValidationChange?: (isValid: boolean) => void;
}

export interface EventCreationFormRef {
  /** Resolves once the save settles, so the caller can report a failure and block resubmits. */
  submit: () => Promise<void>;
}

const TYPE_OPTIONS = DEFAULT_EVENT_TYPES;

const WEEKDAYS = [
  { value: 'monday', label: 'M' },
  { value: 'tuesday', label: 'Tu' },
  { value: 'wednesday', label: 'W' },
  { value: 'thursday', label: 'Th' },
  { value: 'friday', label: 'F' },
  { value: 'saturday', label: 'Sa' },
  { value: 'sunday', label: 'Su' },
];

export const EventCreationForm = forwardRef<EventCreationFormRef, EventCreationFormProps>(
  function EventCreationForm(
    {
      parents,
      children,
      currentParentId,
      initialDate,
      initialEvent,
      onSubmit,
      onValidationChange,
    },
    ref,
  ) {
    const dateToYmd = (date: Date) => {
      const year = date.getFullYear();
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      return `${year}-${month}-${day}`;
    };
    const today = initialDate || dateToYmd(new Date());
    const [title, setTitle] = useState('');
    const [type, setType] = useState<Event['type']>('activity');
    const [startDate, setStartDate] = useState(today);
    const [endDate, setEndDate] = useState(today);
    const [startTime, setStartTime] = useState('16:00');
    const [endTime, setEndTime] = useState('17:30');
    const [allDay, setAllDay] = useState(false);
    const [location, setLocation] = useState('');
    const [notes, setNotes] = useState('');
    const [selectedChildIds, setSelectedChildIds] = useState<string[]>(
      children.map((child) => child.id),
    );
    const [custodyParentId, setCustodyParentId] = useState(currentParentId);
    const [selectedParentIds, setSelectedParentIds] = useState<string[]>([]);
    const [recurrence, setRecurrence] = useState<RecurringPattern | null>(null);
    const stopDrawerDrag = useCallback((event: PointerEvent | MouseEvent) => {
      event.stopPropagation();
    }, []);
    const resolvedType = type.trim() || 'activity';
    const normalizedType = resolvedType.toLowerCase();
    const isCustody = normalizedType === 'custody';
    const normalizeDate = (value: string) => (value.includes('T') ? value.slice(0, 10) : value);
    const normalizedStartDate = normalizeDate(startDate);
    // A repeating event's end date is when the series stops; leaving it empty means it keeps
    // repeating. Only a one-off event falls back to ending on the day it starts.
    const normalizedEndDate = endDate
      ? normalizeDate(endDate)
      : recurrence
        ? undefined
        : normalizedStartDate;
    // The backend refuses an event with no child, so an empty selection is not a valid form.
    const isValid =
      title.trim().length > 0 &&
      normalizedStartDate.length > 0 &&
      resolvedType.length > 0 &&
      selectedChildIds.length > 0;

    // Children arrive after the form mounts, so the "every child" default is applied once they
    // do, never over a selection the user has already made or an event being edited.
    const childrenDefaulted = useRef(false);
    useEffect(() => {
      if (childrenDefaulted.current || initialEvent || children.length === 0) return;
      childrenDefaulted.current = true;
      setSelectedChildIds((current) =>
        current.length > 0 ? current : children.map((child) => child.id),
      );
    }, [children, initialEvent]);

    useEffect(() => {
      if (!startDate || !endDate) return;
      if (new Date(normalizeDate(endDate)) < new Date(normalizeDate(startDate))) {
        setEndDate(startDate);
      }
    }, [startDate, endDate]);

    const lastInitializedId = useRef<string | null>(null);

    useEffect(() => {
      if (!initialEvent) return;
      const nextId = initialEvent.id ?? null;
      if (lastInitializedId.current === nextId) return;

      lastInitializedId.current = nextId;
      setTitle(initialEvent.title ?? '');
      setType(initialEvent.type ?? 'activity');
      setStartDate(initialEvent.startDate ?? today);
      setEndDate(
        initialEvent.endDate ?? (initialEvent.recurring ? '' : (initialEvent.startDate ?? today)),
      );
      setStartTime(initialEvent.startTime ?? '16:00');
      setEndTime(initialEvent.endTime ?? '17:30');
      setAllDay(initialEvent.allDay ?? false);
      setLocation(initialEvent.location ?? '');
      setNotes(initialEvent.notes ?? '');
      setSelectedChildIds(initialEvent.childIds ?? children.map((child) => child.id));
      setCustodyParentId(initialEvent.parentId ?? currentParentId);
      setSelectedParentIds(
        initialEvent.parentIds && initialEvent.parentIds.length > 0
          ? initialEvent.parentIds
          : initialEvent.parentId
            ? [initialEvent.parentId]
            : [],
      );
      setRecurrence(initialEvent.recurring ?? null);
    }, [initialEvent, children, currentParentId, today]);

    useEffect(() => {
      if (isCustody) {
        setSelectedParentIds([]);
      } else if (selectedParentIds.length === 0 && currentParentId) {
        setSelectedParentIds([currentParentId]);
      }
    }, [isCustody, currentParentId]);

    const previewEvent: Event = useMemo(() => {
      return {
        id: 'preview',
        type: resolvedType,
        title: title.trim() || 'New Event',
        startDate: normalizedStartDate,
        endDate: normalizedEndDate,
        startTime: isCustody || allDay ? undefined : startTime,
        endTime: isCustody || allDay ? undefined : endTime,
        allDay: isCustody ? true : allDay,
        parentId: isCustody ? custodyParentId : null,
        parentIds: isCustody ? [] : selectedParentIds,
        childIds: selectedChildIds,
        location: location || undefined,
        notes: notes.trim() || null,
        recurring: recurrence,
      };
    }, [
      resolvedType,
      title,
      normalizedStartDate,
      normalizedEndDate,
      startTime,
      endTime,
      allDay,
      custodyParentId,
      selectedParentIds,
      selectedChildIds,
      location,
      notes,
      recurrence,
      isCustody,
    ]);

    const handleToggleChild = (childId: string) => {
      setSelectedChildIds((prev) =>
        prev.includes(childId) ? prev.filter((id) => id !== childId) : [...prev, childId],
      );
    };

    const handleToggleParent = (parentId: string) => {
      if (isCustody) return;
      setSelectedParentIds((prev) =>
        prev.includes(parentId) ? prev.filter((id) => id !== parentId) : [...prev, parentId],
      );
    };

    const handleChangeType = (nextType: string) => {
      setType(nextType);
      if (nextType.trim().toLowerCase() === 'custody') {
        setAllDay(true);
      }
    };

    const handleRecurrence = (frequency: RecurringPattern['frequency'] | 'none') => {
      if (frequency === 'none') {
        setRecurrence(null);
        if (!endDate) setEndDate(startDate);
        return;
      }
      // Starting a series whose end is still the start date would repeat exactly once.
      if (!recurrence && !isCustody && endDate === startDate) setEndDate('');
      if (frequency === 'weekly') {
        const dayIndex = new Date(`${startDate}T12:00:00`).getDay();
        const defaultDay = WEEKDAYS[dayIndex]?.value || 'monday';
        setRecurrence({ frequency: 'weekly', days: [defaultDay] });
        return;
      }
      setRecurrence({ frequency });
    };

    const handleToggleRecurrenceDay = (day: string) => {
      if (!recurrence || recurrence.frequency !== 'weekly') return;
      const days = recurrence.days || [];
      const nextDays = days.includes(day) ? days.filter((d) => d !== day) : [...days, day];
      setRecurrence({ ...recurrence, days: nextDays });
    };

    const handleSubmit = useCallback(async () => {
      if (!isValid) return;
      await onSubmit?.({
        type: previewEvent.type,
        title: previewEvent.title,
        startDate: previewEvent.startDate,
        endDate: previewEvent.endDate,
        startTime: previewEvent.startTime,
        endTime: previewEvent.endTime,
        allDay: previewEvent.allDay,
        parentId: previewEvent.parentId,
        // The update API replaces the whole event, so a field left out here is erased.
        parentIds: previewEvent.parentIds,
        childIds: previewEvent.childIds,
        location: previewEvent.location,
        notes: previewEvent.notes,
        // Skipped dates are changed one at a time through their own endpoint, so the latest
        // saved list is sent back rather than whatever this form loaded when it opened.
        recurring: previewEvent.recurring
          ? {
              ...previewEvent.recurring,
              excludedDates: initialEvent?.recurring?.excludedDates ?? [],
            }
          : null,
      });
    }, [isValid, onSubmit, previewEvent, initialEvent]);

    // Notify parent of validation state changes
    useEffect(() => {
      onValidationChange?.(isValid);
    }, [isValid, onValidationChange]);

    // Expose submit method via ref
    useImperativeHandle(
      ref,
      () => ({
        submit: handleSubmit,
      }),
      [handleSubmit],
    );

    return (
      <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
        <div className="flex-1 space-y-6">
          <div className="rounded-2xl border border-slate-200/60 bg-white p-6 shadow-lg shadow-slate-200/40 dark:border-slate-700/60 dark:bg-slate-900/70 dark:shadow-slate-900/60">
            <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
              Event essentials
            </h2>
            <div className="mt-4 grid gap-4">
              <div>
                <label htmlFor="event-title" className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                  Title
                </label>
                <input
                  id="event-title"
                  type="text"
                  value={title}
                  onChange={(event) => setTitle(event.target.value)}
                  placeholder="e.g. Emma Soccer Practice"
                  className="w-full rounded-xl border border-slate-200 bg-white/70 px-4 py-2.5 text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                />
              </div>

              <div>
                <p className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                  Event type
                </p>
                <div className="grid gap-2 sm:grid-cols-2">
                  {TYPE_OPTIONS.map((option) => {
                    const selected = normalizedType === option.value;
                    return (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => handleChangeType(option.value)}
                        className={`rounded-xl border px-4 py-3 text-left transition ${
                          selected
                            ? 'border-teal-500 bg-teal-50 shadow-sm dark:bg-teal-900/30'
                            : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
                        }`}
                      >
                        <p className="text-sm font-semibold text-slate-800 dark:text-slate-100">
                          {option.label}
                        </p>
                        <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                          {option.description}
                        </p>
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="grid gap-4 sm:grid-cols-[1.4fr_1fr]">
                <div>
                  <label htmlFor="event-custom-type" className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                    Custom type
                  </label>
                  <div className="flex gap-2">
                    <input
                      id="event-custom-type"
                      type="text"
                      value={type}
                      onChange={(event) => {
                        const nextType = event.target.value;
                        setType(nextType);
                        if (nextType.trim().toLowerCase() === 'custody') {
                          setAllDay(true);
                        }
                      }}
                      placeholder="e.g. Therapy, Travel, Birthday"
                      list="event-type-options"
                      className="flex-1 rounded-xl border border-slate-200 bg-white/70 px-4 py-2.5 text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                    />
                  </div>
                  <datalist id="event-type-options">
                    {TYPE_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value} />
                    ))}
                  </datalist>
                  <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
                    Pick a suggested type above or type your own.
                  </p>
                </div>

                <div>
                  <label htmlFor="event-all-day" className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                    All day
                  </label>
                  <div className="flex items-center gap-3 rounded-xl border border-slate-200 px-4 py-2.5 dark:border-slate-700">
                    <input
                      id="event-all-day"
                      type="checkbox"
                      checked={isCustody ? true : allDay}
                      onChange={(event) => setAllDay(event.target.checked)}
                      disabled={isCustody}
                      className="h-4 w-4 rounded border-slate-300 text-teal-600"
                    />
                    <div>
                      <p className="text-sm font-medium text-slate-700 dark:text-slate-200">
                        Runs all day
                      </p>
                      <p className="text-xs text-slate-500 dark:text-slate-400">
                        {isCustody
                          ? 'Custody blocks are all-day by default.'
                          : 'Hide time slots and show as all-day.'}
                      </p>
                    </div>
                  </div>
                </div>
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <p className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                    Parent in charge
                  </p>
                  <div className="space-y-2">
                    {parents.map((parent) => {
                      const selected = custodyParentId === parent.id;
                      return (
                        <button
                          key={parent.id}
                          type="button"
                          onClick={() => setCustodyParentId(parent.id)}
                          disabled={!isCustody}
                          className={`flex w-full items-center justify-between rounded-xl border px-4 py-2.5 text-left transition ${
                            selected
                              ? 'border-teal-500 bg-teal-50 text-teal-700 dark:bg-teal-900/30 dark:text-teal-100'
                              : 'border-slate-200 text-slate-600 hover:border-slate-300 dark:border-slate-700 dark:text-slate-300'
                          } ${!isCustody ? 'cursor-not-allowed opacity-50' : ''} `}
                        >
                          <span className="text-sm font-medium">{parent.name}</span>
                          {selected && (
                            <span className="text-xs uppercase tracking-wide">Primary</span>
                          )}
                        </button>
                      );
                    })}
                  </div>
                </div>

                <div>
                  <p className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                    Additional parents
                  </p>
                  <div className="space-y-2">
                    {parents.map((parent) => {
                      const selected = selectedParentIds.includes(parent.id);
                      return (
                        <button
                          key={parent.id}
                          type="button"
                          onClick={() => handleToggleParent(parent.id)}
                          disabled={isCustody}
                          className={`flex w-full items-center justify-between rounded-xl border px-4 py-2.5 text-left transition ${
                            selected
                              ? 'border-indigo-500 bg-indigo-50 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-100'
                              : 'border-slate-200 text-slate-600 hover:border-slate-300 dark:border-slate-700 dark:text-slate-300'
                          } ${isCustody ? 'cursor-not-allowed opacity-50' : ''} `}
                        >
                          <span className="text-sm font-medium">{parent.name}</span>
                          {selected && (
                            <span className="text-xs uppercase tracking-wide">Included</span>
                          )}
                        </button>
                      );
                    })}
                  </div>
                  <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
                    Choose multiple parents for shared responsibility.
                  </p>
                </div>
              </div>
            </div>
          </div>

          <div
            className="rounded-2xl border border-slate-200/60 bg-white p-6 shadow-lg shadow-slate-200/40 dark:border-slate-700/60 dark:bg-slate-900/70 dark:shadow-slate-900/60"
            data-vaul-no-drag
          >
            <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">Schedule</h2>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <div>
                <label htmlFor="event-start-date" className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                  Start date
                </label>
                <input
                  id="event-start-date"
                  type="date"
                  value={startDate}
                  onChange={(event) => setStartDate(event.target.value)}
                  data-vaul-no-drag
                  onPointerDownCapture={stopDrawerDrag}
                  onMouseDownCapture={stopDrawerDrag}
                  className="w-full rounded-xl border border-slate-200 bg-white/70 px-4 py-2.5 text-slate-800 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                />
              </div>
              <div>
                <label htmlFor="event-end-date" className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                  {recurrence ? 'Repeat until' : 'End date'}
                </label>
                <input
                  id="event-end-date"
                  type="date"
                  value={endDate}
                  onChange={(event) => setEndDate(event.target.value)}
                  data-vaul-no-drag
                  onPointerDownCapture={stopDrawerDrag}
                  onMouseDownCapture={stopDrawerDrag}
                  className="w-full rounded-xl border border-slate-200 bg-white/70 px-4 py-2.5 text-slate-800 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                />
                {recurrence && (
                  <p className="mt-2 flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                    {endDate ? (
                      <button
                        type="button"
                        onClick={() => setEndDate('')}
                        className="font-medium text-teal-700 hover:underline dark:text-teal-300"
                      >
                        Remove end date
                      </button>
                    ) : (
                      'No end date — keeps repeating.'
                    )}
                  </p>
                )}
              </div>
            </div>

            {!allDay && !isCustody && (
              <div className="mt-4 grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor="event-start-time" className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                    Start time
                  </label>
                  <input
                    id="event-start-time"
                    type="time"
                    value={startTime}
                    onChange={(event) => setStartTime(event.target.value)}
                    data-vaul-no-drag
                    onPointerDownCapture={stopDrawerDrag}
                    onMouseDownCapture={stopDrawerDrag}
                    className="w-full rounded-xl border border-slate-200 bg-white/70 px-4 py-2.5 text-slate-800 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                  />
                </div>
                <div>
                  <label htmlFor="event-end-time" className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                    End time
                  </label>
                  <input
                    id="event-end-time"
                    type="time"
                    value={endTime}
                    onChange={(event) => setEndTime(event.target.value)}
                    data-vaul-no-drag
                    onPointerDownCapture={stopDrawerDrag}
                    onMouseDownCapture={stopDrawerDrag}
                    className="w-full rounded-xl border border-slate-200 bg-white/70 px-4 py-2.5 text-slate-800 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                  />
                </div>
              </div>
            )}

            <div className="mt-5">
              <p className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                Repeats
              </p>
              <div className="flex flex-wrap gap-2">
                {['none', 'daily', 'weekly'].map((option) => (
                  <button
                    key={option}
                    type="button"
                    onClick={() =>
                      handleRecurrence(option as RecurringPattern['frequency'] | 'none')
                    }
                    className={`rounded-full border px-3 py-1.5 text-xs font-medium transition ${
                      (option === 'none' && !recurrence) || recurrence?.frequency === option
                        ? 'border-teal-500 bg-teal-50 text-teal-700 dark:bg-teal-900/30 dark:text-teal-200'
                        : 'border-slate-200 text-slate-500 dark:border-slate-700 dark:text-slate-400'
                    }`}
                  >
                    {option === 'none' ? 'Does not repeat' : `Every ${option}`}
                  </button>
                ))}
              </div>

              {recurrence?.frequency === 'weekly' && (
                <div className="mt-3 flex gap-2">
                  {WEEKDAYS.map((day) => (
                    <button
                      key={day.value}
                      type="button"
                      onClick={() => handleToggleRecurrenceDay(day.value)}
                      className={`h-9 w-9 rounded-full border text-xs font-semibold transition ${
                        recurrence.days?.includes(day.value)
                          ? 'border-teal-500 bg-teal-500 text-white'
                          : 'border-slate-200 text-slate-500 dark:border-slate-700 dark:text-slate-400'
                      }`}
                    >
                      {day.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200/60 bg-white p-6 shadow-lg shadow-slate-200/40 dark:border-slate-700/60 dark:bg-slate-900/70 dark:shadow-slate-900/60">
            <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
              People & context
            </h2>
            <div className="mt-4 grid gap-4">
              <div>
                <p className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                  Children
                </p>
                <div className="flex flex-wrap gap-2">
                  {children.map((child) => {
                    const selected = selectedChildIds.includes(child.id);
                    return (
                      <button
                        key={child.id}
                        type="button"
                        onClick={() => handleToggleChild(child.id)}
                        className={`rounded-full border px-3 py-1.5 text-sm transition ${
                          selected
                            ? 'border-teal-500 bg-teal-50 text-teal-700 dark:bg-teal-900/30 dark:text-teal-200'
                            : 'border-slate-200 text-slate-500 dark:border-slate-700 dark:text-slate-400'
                        }`}
                      >
                        {child.name}
                      </button>
                    );
                  })}
                </div>
              </div>

              {isCustody && (
                <div>
                  <p className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                    Custody with
                  </p>
                  <div className="grid gap-2 sm:grid-cols-2">
                    {parents.map((parent) => {
                      const selected = custodyParentId === parent.id;
                      return (
                        <button
                          key={parent.id}
                          type="button"
                          onClick={() => setCustodyParentId(parent.id)}
                          className={`flex items-center gap-3 rounded-xl border px-4 py-3 text-left transition ${
                            selected
                              ? 'border-teal-500 bg-teal-50 dark:bg-teal-900/30'
                              : 'border-slate-200 dark:border-slate-700'
                          }`}
                        >
                          <div
                            className={`h-3 w-3 rounded-full ${parent.color === 'violet' ? 'bg-violet-500' : 'bg-sky-500'}`}
                          />
                          <div>
                            <p className="text-sm font-semibold text-slate-800 dark:text-slate-100">
                              {parent.name}
                            </p>
                            <p className="text-xs text-slate-500 dark:text-slate-400">
                              Primary custody owner
                            </p>
                          </div>
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor="event-location" className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                    Location
                  </label>
                  <input
                    id="event-location"
                    type="text"
                    value={location}
                    onChange={(event) => setLocation(event.target.value)}
                    placeholder="Add location or address"
                    className="w-full rounded-xl border border-slate-200 bg-white/70 px-4 py-2.5 text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                  />
                </div>
                <div>
                  <p className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                    Shared visibility
                  </p>
                  <div className="flex items-center gap-3 rounded-xl border border-slate-200 px-4 py-2.5 dark:border-slate-700">
                    <div className="h-2.5 w-2.5 rounded-full bg-teal-500" />
                    <p className="text-sm text-slate-600 dark:text-slate-300">
                      Visible to both parents and linked children
                    </p>
                  </div>
                </div>
              </div>

              <div>
                <label htmlFor="event-notes" className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                  Notes
                </label>
                <textarea
                  id="event-notes"
                  value={notes}
                  onChange={(event) => setNotes(event.target.value)}
                  rows={4}
                  placeholder="Add reminders, what to bring, or additional details"
                  className="w-full rounded-xl border border-slate-200 bg-white/70 px-4 py-2.5 text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                />
              </div>
            </div>
          </div>
        </div>

        <div className="space-y-4 lg:w-[320px]">
          <div className="rounded-2xl border border-slate-200/60 bg-white p-5 shadow-lg shadow-slate-200/40 dark:border-slate-700/60 dark:bg-slate-900/70 dark:shadow-slate-900/60">
            <h3 className="text-sm uppercase tracking-[0.25em] text-slate-400 dark:text-slate-500">
              Preview
            </h3>
            <div className="mt-4">
              <div className="rounded-xl border border-slate-200/70 bg-slate-50 p-3 dark:border-slate-700/60 dark:bg-slate-800/60">
                <EventPill event={previewEvent} showTime={!previewEvent.allDay} />
                <div className="mt-3 space-y-2 text-xs text-slate-500 dark:text-slate-400">
                  <p>
                    <span className="font-semibold text-slate-600 dark:text-slate-300">Type:</span>{' '}
                    {resolvedType}
                  </p>
                  <p>
                    <span className="font-semibold text-slate-600 dark:text-slate-300">When:</span>{' '}
                    {previewEvent.startDate}
                    {previewEvent.endDate && previewEvent.endDate !== previewEvent.startDate
                      ? ` → ${previewEvent.endDate}`
                      : ''}
                  </p>
                  {previewEvent.startTime && (
                    <p>
                      <span className="font-semibold text-slate-600 dark:text-slate-300">
                        Time:
                      </span>{' '}
                      {previewEvent.startTime}–{previewEvent.endTime}
                    </p>
                  )}
                  {previewEvent.location && (
                    <p>
                      <span className="font-semibold text-slate-600 dark:text-slate-300">
                        Location:
                      </span>{' '}
                      {previewEvent.location}
                    </p>
                  )}
                </div>
              </div>
            </div>
            <div className="mt-4 rounded-xl border border-rose-200/70 bg-rose-50 p-3 dark:border-rose-800/50 dark:bg-rose-900/20">
              <p className="text-xs font-semibold text-rose-700 dark:text-rose-200">
                Shared clarity
              </p>
              <p className="mt-1 text-xs text-rose-600/80 dark:text-rose-200/70">
                Events with clear types reduce follow-up messages and make approvals faster.
              </p>
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200/60 bg-white p-5 shadow-lg shadow-slate-200/40 dark:border-slate-700/60 dark:bg-slate-900/70 dark:shadow-slate-900/60">
            <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300">
              Quick guidance
            </h3>
            <div className="mt-3 space-y-3 text-xs text-slate-500 dark:text-slate-400">
              <div className="flex gap-2">
                <span className="mt-0.5 h-2 w-2 rounded-full bg-teal-500" />
                <p>Pick a type that makes sense for both parents at a glance.</p>
              </div>
              <div className="flex gap-2">
                <span className="mt-0.5 h-2 w-2 rounded-full bg-rose-500" />
                <p>Recurring events keep schedules consistent week-to-week.</p>
              </div>
              <div className="flex gap-2">
                <span className="mt-0.5 h-2 w-2 rounded-full bg-slate-400" />
                <p>Custody entries are all-day blocks and default to the primary parent.</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  },
);
