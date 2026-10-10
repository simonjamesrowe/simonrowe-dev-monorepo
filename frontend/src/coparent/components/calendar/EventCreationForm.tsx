import { CalendarCheck, Check } from 'lucide-react';
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

import { describeEvent } from './eventSummary';
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

const REPEAT_OPTIONS: { value: RecurringPattern['frequency'] | 'none'; label: string }[] = [
  { value: 'none', label: 'Does not repeat' },
  { value: 'daily', label: 'Every day' },
  { value: 'weekly', label: 'Every week' },
];

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
        // getDay() counts from Sunday and WEEKDAYS starts on Monday, so shift by one: without
        // it a series started on a Saturday defaulted to Sundays.
        const dayIndex = (new Date(`${startDate}T12:00:00`).getDay() + 6) % 7;
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

    const inputClass =
      'w-full rounded-xl border border-slate-200 bg-white/70 px-4 py-2.5 text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100';
    const labelClass = 'mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300';
    const panelClass =
      'rounded-2xl border border-slate-200/60 bg-white p-6 shadow-lg shadow-slate-200/40 dark:border-slate-700/60 dark:bg-slate-900/70 dark:shadow-slate-900/60';
    const custodyParent = parents.find((parent) => parent.id === custodyParentId);
    const summary = describeEvent({
      title: isCustody
        ? `${title.trim() || 'Custody'}${custodyParent ? ` with ${custodyParent.name}` : ''}`
        : title.trim(),
      childNames: children
        .filter((child) => selectedChildIds.includes(child.id))
        .map((child) => child.name),
      startDate: previewEvent.startDate,
      endDate: previewEvent.endDate,
      startTime: previewEvent.startTime,
      endTime: previewEvent.endTime,
      allDay: previewEvent.allDay,
      frequency: recurrence?.frequency,
      days: recurrence?.days,
    });

    return (
      <div className="event-form">
        {/* The whole event in one line, worded the way Quick add words it, so it can be checked
            at a glance before saving. */}
        <p className="event-form__summary" aria-live="polite" data-testid="event-summary">
          <CalendarCheck size={18} aria-hidden="true" />
          <span>{title.trim() || isCustody ? summary : `Add a title · ${summary}`}</span>
        </p>

        <div className="cp-form-cols">
          <section className={`${panelClass} cp-form-col`} aria-labelledby="event-what-heading">
            <h2
              id="event-what-heading"
              className="text-lg font-semibold text-slate-800 dark:text-slate-100"
            >
              What it is
            </h2>
            <div>
              <label htmlFor="event-title" className={labelClass}>
                Title
              </label>
              <input
                id="event-title"
                type="text"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                placeholder="e.g. Emma Soccer Practice"
                className={inputClass}
              />
            </div>

            <div>
              <p className={labelClass}>Event type</p>
              <div className="grid gap-2 sm:grid-cols-2">
                {TYPE_OPTIONS.map((option) => {
                  const selected = normalizedType === option.value;
                  return (
                    <button
                      key={option.value}
                      type="button"
                      aria-pressed={selected}
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

            <div>
              <label htmlFor="event-custom-type" className={labelClass}>
                Or your own type
              </label>
              <input
                id="event-custom-type"
                type="text"
                value={type}
                onChange={(event) => handleChangeType(event.target.value)}
                placeholder="e.g. Therapy, Travel, Birthday"
                list="event-type-options"
                className={inputClass}
              />
              <datalist id="event-type-options">
                {TYPE_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value} />
                ))}
              </datalist>
            </div>

            <div>
              <p className={labelClass}>Children</p>
              <div className="flex flex-wrap gap-2">
                {children.map((child) => {
                  const selected = selectedChildIds.includes(child.id);
                  return (
                    <button
                      key={child.id}
                      type="button"
                      aria-pressed={selected}
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

            <div>
              <label htmlFor="event-location" className={labelClass}>
                Location
              </label>
              <input
                id="event-location"
                type="text"
                value={location}
                onChange={(event) => setLocation(event.target.value)}
                placeholder="Add location or address"
                className={inputClass}
              />
            </div>

            <div>
              <label htmlFor="event-notes" className={labelClass}>
                Notes
              </label>
              <textarea
                id="event-notes"
                value={notes}
                onChange={(event) => setNotes(event.target.value)}
                rows={4}
                placeholder="Add reminders, what to bring, or additional details"
                className={inputClass}
              />
            </div>
          </section>

          <section
            className={`${panelClass} cp-form-col`}
            aria-labelledby="event-when-heading"
            data-vaul-no-drag
          >
            <h2
              id="event-when-heading"
              className="text-lg font-semibold text-slate-800 dark:text-slate-100"
            >
              When and who
            </h2>
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <label htmlFor="event-start-date" className={labelClass}>
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
                  className={inputClass}
                />
              </div>
              <div>
                <label htmlFor="event-end-date" className={labelClass}>
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
                  className={inputClass}
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

            <label
              htmlFor="event-all-day"
              className="flex items-center gap-3 rounded-xl border border-slate-200 px-4 py-2.5 dark:border-slate-700"
            >
              <input
                id="event-all-day"
                type="checkbox"
                checked={isCustody ? true : allDay}
                onChange={(event) => setAllDay(event.target.checked)}
                disabled={isCustody}
                className="h-4 w-4 rounded border-slate-300 text-teal-600"
              />
              <span>
                <span className="block text-sm font-medium text-slate-700 dark:text-slate-200">
                  All day
                </span>
                <span className="block text-xs text-slate-500 dark:text-slate-400">
                  {isCustody ? 'Custody always runs all day.' : 'No start or end time.'}
                </span>
              </span>
            </label>

            {!allDay && !isCustody && (
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor="event-start-time" className={labelClass}>
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
                    className={inputClass}
                  />
                </div>
                <div>
                  <label htmlFor="event-end-time" className={labelClass}>
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
                    className={inputClass}
                  />
                </div>
              </div>
            )}

            <div>
              <p className={labelClass}>Repeats</p>
              <div className="flex flex-wrap gap-2">
                {REPEAT_OPTIONS.map((option) => {
                  const selected =
                    (option.value === 'none' && !recurrence) || recurrence?.frequency === option.value;
                  return (
                    <button
                      key={option.value}
                      type="button"
                      aria-pressed={selected}
                      onClick={() => handleRecurrence(option.value)}
                      className={`rounded-full border px-3 py-1.5 text-xs font-medium transition ${
                        selected
                          ? 'border-teal-500 bg-teal-50 text-teal-700 dark:bg-teal-900/30 dark:text-teal-200'
                          : 'border-slate-200 text-slate-500 dark:border-slate-700 dark:text-slate-400'
                      }`}
                    >
                      {option.label}
                    </button>
                  );
                })}
              </div>

              {recurrence?.frequency === 'weekly' && (
                <div className="mt-3 flex flex-wrap gap-2">
                  {WEEKDAYS.map((day) => (
                    <button
                      key={day.value}
                      type="button"
                      aria-pressed={recurrence.days?.includes(day.value) ?? false}
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

            {/* One question about parents, never two lists of the same names: custody has one
                parent, any other event can involve either or both. */}
            <div>
              <p className={labelClass}>{isCustody ? 'Who has the children?' : 'Who is going?'}</p>
              <div className="grid gap-2 sm:grid-cols-2">
                {parents.map((parent) => {
                  const selected = isCustody
                    ? custodyParentId === parent.id
                    : selectedParentIds.includes(parent.id);
                  return (
                    <button
                      key={parent.id}
                      type="button"
                      aria-pressed={selected}
                      onClick={() =>
                        isCustody ? setCustodyParentId(parent.id) : handleToggleParent(parent.id)
                      }
                      className={`flex items-center gap-3 rounded-xl border px-4 py-2.5 text-left transition ${
                        selected
                          ? 'border-teal-500 bg-teal-50 text-teal-700 dark:bg-teal-900/30 dark:text-teal-100'
                          : 'border-slate-200 text-slate-600 hover:border-slate-300 dark:border-slate-700 dark:text-slate-300'
                      }`}
                    >
                      <span
                        aria-hidden="true"
                        className={`h-3 w-3 rounded-full ${parent.color === 'violet' ? 'bg-violet-500' : 'bg-sky-500'}`}
                      />
                      <span className="flex-1 text-sm font-medium">
                        {parent.name}
                        {parent.id === currentParentId ? ' (you)' : ''}
                      </span>
                      {selected && <Check size={16} aria-hidden="true" />}
                    </button>
                  );
                })}
              </div>
              <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
                {isCustody
                  ? 'The children stay with this parent for the whole block.'
                  : 'Choose one of you or both. You can both always see it.'}
              </p>
            </div>
          </section>
        </div>
      </div>
    );
  },
);
