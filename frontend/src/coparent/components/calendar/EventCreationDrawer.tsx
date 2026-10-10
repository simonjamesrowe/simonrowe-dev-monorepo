import { ArrowLeftRight, CalendarX, RotateCcw, Trash2 } from 'lucide-react';
import { useState, useRef, useEffect } from 'react';
import { Drawer } from 'vaul';

import { apiErrorMessage } from '../../lib/api/errorMessage';
import type { Event, Parent, Child } from '../../types/calendar';
import { CloseButton } from '../ui/CloseButton';

import type { EventCreationFormRef } from './EventCreationForm';
import { EventCreationForm } from './EventCreationForm';

export interface EventCreationDrawerProps {
  open: boolean;
  onClose: () => void;
  initialDate: string;
  parents: Parent[];
  children: Child[];
  event?: Event;
  mode?: 'create' | 'edit';
  /** The occurrence that was clicked, when the event being edited repeats. */
  occurrenceDate?: string;
  /** Skips (or, with `skip` false, restores) one occurrence of the event being edited. */
  onSkipOccurrence?: (date: string, skip: boolean) => Promise<void>;
  currentParentId: string;
  onSubmit: (eventData: Omit<Event, 'id'>) => Promise<void>;
  /** Deletes the event being edited (the whole series, for a repeating event). */
  onDelete?: () => Promise<void>;
  /**
   * Asks the other parent to agree a change (for a repeating event, to the opened occurrence).
   * Unlike an edit, it changes nothing until they approve.
   */
  onRequestChange?: () => void;
}

const formatOccurrence = (date: string) =>
  new Date(`${date}T12:00:00`).toLocaleDateString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });

export function EventCreationDrawer({
  open,
  onClose,
  initialDate,
  parents,
  children,
  event,
  mode = 'create',
  occurrenceDate,
  onSkipOccurrence,
  currentParentId,
  onSubmit,
  onDelete,
  onRequestChange,
}: EventCreationDrawerProps) {
  const [isValid, setIsValid] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [pendingDate, setPendingDate] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const skippedDates = event?.recurring?.excludedDates ?? [];
  const occurrenceSkipped = occurrenceDate ? skippedDates.includes(occurrenceDate) : false;

  const changeOccurrence = async (date: string, skip: boolean) => {
    if (!onSkipOccurrence || pendingDate) return;
    setPendingDate(date);
    try {
      await onSkipOccurrence(date, skip);
      if (skip && date === occurrenceDate) onClose();
    } finally {
      setPendingDate(null);
    }
  };
  const formRef = useRef<EventCreationFormRef>(null);

  useEffect(() => {
    if (mode !== 'edit' || !event) return;
    const hasTitle = Boolean(event.title?.trim());
    const hasType = Boolean(event.type?.trim());
    const hasStart = Boolean(event.startDate);
    setIsValid(hasTitle && hasType && hasStart);
  }, [mode, event]);

  const handleSubmit = async () => {
    if (!isValid || isSubmitting || !formRef.current) return;

    setIsSubmitting(true);
    setError(null);
    try {
      await formRef.current.submit();
    } catch (failure) {
      setError(apiErrorMessage(failure, 'The event could not be saved. Try again.'));
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleFormSubmit = async (eventData: Omit<Event, 'id'>) => {
    await onSubmit(eventData);
    onClose();
  };

  const handleDelete = async () => {
    if (!onDelete || isSubmitting) return;
    if (!confirmingDelete) {
      setConfirmingDelete(true);
      return;
    }
    setIsSubmitting(true);
    setError(null);
    try {
      await onDelete();
      onClose();
    } catch (failure) {
      setError(apiErrorMessage(failure, 'The event could not be deleted. Try again.'));
    } finally {
      setIsSubmitting(false);
      setConfirmingDelete(false);
    }
  };

  if (!open) return null;

  return (
    <Drawer.Root open={open} onOpenChange={(open) => !open && onClose()} direction="right">
      <Drawer.Portal>
        {/* The utility styles are scoped to .coparent-app and Vaul portals to <body>, outside
            it; without this wrapper the drawer renders unstyled below the page. */}
        <div className="coparent-app">
          <Drawer.Overlay className="fixed inset-0 z-40 bg-black/40" />
          <Drawer.Content className="fixed bottom-0 right-0 top-0 z-50 w-full max-w-none bg-white shadow-xl outline-none sm:w-5/6 md:w-4/5 lg:w-3/4 dark:bg-slate-900">
            <Drawer.Title className="sr-only">
              {mode === 'edit' ? 'Edit event' : 'Add event'}
            </Drawer.Title>
            <Drawer.Description className="sr-only">
              {mode === 'edit'
                ? 'Update a calendar event for your family'
                : 'Create a new calendar event for your family'}
            </Drawer.Description>
            <div className="flex h-full flex-col">
              {/* Fixed Header */}
              <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4 dark:border-slate-700">
                <h2 className="text-xl font-semibold text-slate-900 dark:text-slate-100">
                  {mode === 'edit' ? 'Edit event' : 'Add event'}
                </h2>
                <CloseButton onClick={onClose} />
              </div>

              {/* Scrollable Body */}
              <div className="flex-1 overflow-y-auto">
                <div className="p-6">
                  {mode === 'edit' && event?.recurring && onSkipOccurrence && (
                    <div className="mb-6 space-y-3 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-900/20 dark:text-amber-100">
                      <p>
                        This event repeats. Saving below changes every occurrence
                        {occurrenceDate && !occurrenceSkipped ? (
                          <>
                            ; to cancel just <strong>{formatOccurrence(occurrenceDate)}</strong>,
                            skip that date instead.
                          </>
                        ) : (
                          '.'
                        )}
                      </p>
                      {occurrenceDate && !occurrenceSkipped && (
                        <button
                          type="button"
                          onClick={() => changeOccurrence(occurrenceDate, true)}
                          disabled={Boolean(pendingDate)}
                          className="cp-button cp-button--secondary cp-button--sm"
                        >
                          <CalendarX size={16} aria-hidden="true" />
                          Skip {formatOccurrence(occurrenceDate)} only
                        </button>
                      )}
                      {skippedDates.length > 0 && (
                        <div>
                          <p className="font-medium">Skipped dates</p>
                          <ul
                          className="mt-2 flex flex-wrap gap-2"
                          style={{ listStyle: 'none', paddingLeft: 0 }}
                        >
                            {skippedDates.map((date) => (
                              <li
                                key={date}
                                className="inline-flex items-center gap-2 rounded-full border border-amber-200 bg-white px-3 py-1 dark:border-amber-800 dark:bg-slate-900"
                              >
                                <span style={{ textDecoration: 'line-through' }}>
                                {formatOccurrence(date)}
                              </span>
                                <button
                                  type="button"
                                  onClick={() => changeOccurrence(date, false)}
                                  disabled={Boolean(pendingDate)}
                                  aria-label={`Restore ${formatOccurrence(date)}`}
                                  className="inline-flex items-center gap-1 font-medium text-teal-700 hover:underline disabled:text-slate-400 dark:text-teal-300"
                                >
                                  <RotateCcw size={14} aria-hidden="true" />
                                  Restore
                                </button>
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}
                    </div>
                  )}
                  <EventCreationForm
                    parents={parents}
                    children={children}
                    currentParentId={currentParentId}
                    initialDate={initialDate}
                    initialEvent={event}
                    onSubmit={handleFormSubmit}
                    onCancel={onClose}
                    onValidationChange={setIsValid}
                    ref={formRef}
                  />
                </div>
              </div>

              {/* Fixed Footer */}
              <div className="border-t border-slate-200 px-6 py-4 dark:border-slate-700">
                {error && (
                  <p
                    role="alert"
                    className="mb-3 rounded-xl border border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/30 dark:text-red-300"
                  >
                    {error}
                  </p>
                )}
                <div className="flex items-center justify-end gap-3">
                  {mode === 'edit' && onRequestChange && (
                    <button
                      type="button"
                      onClick={onRequestChange}
                      disabled={isSubmitting}
                      className="cp-button cp-button--secondary"
                    >
                      <ArrowLeftRight size={16} aria-hidden="true" />
                      Request a change
                    </button>
                  )}
                  {mode === 'edit' && onDelete && (
                    <button
                      type="button"
                      onClick={handleDelete}
                      onBlur={() => setConfirmingDelete(false)}
                      disabled={isSubmitting}
                      style={{ marginRight: 'auto' }}
                      className={`cp-button ${confirmingDelete ? 'cp-button--danger-solid' : 'cp-button--danger'}`}
                    >
                      <Trash2 size={16} aria-hidden="true" />
                      {confirmingDelete
                        ? event?.recurring
                          ? 'Delete every occurrence?'
                          : 'Confirm delete'
                        : 'Delete'}
                    </button>
                  )}
                  <button type="button" onClick={onClose} className="cp-button cp-button--secondary">
                    Cancel
                  </button>
                  <button
                    type="button"
                    onClick={handleSubmit}
                    disabled={!isValid || isSubmitting}
                    className="cp-button cp-button--primary"
                  >
                    {isSubmitting ? 'Saving…' : mode === 'edit' ? 'Save changes' : 'Add event'}
                  </button>
                </div>
              </div>
            </div>
          </Drawer.Content>
        </div>
      </Drawer.Portal>
    </Drawer.Root>
  );
}
