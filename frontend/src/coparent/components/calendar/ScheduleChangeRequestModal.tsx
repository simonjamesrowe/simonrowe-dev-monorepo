import { useState, useMemo } from 'react';

import type { Event, Parent, ProposedChange } from '../../types/calendar';

import { Icon } from './Icon';

export interface ScheduleChangeRequestModalProps {
  /** Whether the modal is open */
  isOpen: boolean;
  /** The custody event being modified */
  originalEvent: Event | null;
  /** Map of parent IDs to parent data */
  parents: Record<string, Parent>;
  /** ID of the current logged-in parent (the requester) */
  currentParentId: string;
  /** Called when the modal should close */
  onClose?: () => void;
  /** Called when the user submits the request */
  onSubmit?: (data: { proposedChange: ProposedChange; reason: string }) => void;
}

function formatDate(dateStr: string): string {
  const date = new Date(dateStr + 'T12:00:00');
  return date.toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  });
}

function getDaysBetween(start: string, end: string): number {
  const startDate = new Date(start + 'T12:00:00');
  const endDate = new Date(end + 'T12:00:00');
  return Math.ceil((endDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24));
}

export function ScheduleChangeRequestModal({
  isOpen,
  originalEvent,
  parents,
  currentParentId,
  onClose,
  onSubmit,
}: ScheduleChangeRequestModalProps) {
  const [changeType, setChangeType] = useState<ProposedChange['type']>('swap');
  const [newStartDate, setNewStartDate] = useState('');
  const [newEndDate, setNewEndDate] = useState('');
  const [reason, setReason] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Reset form when modal opens with new event
  useMemo(() => {
    if (originalEvent) {
      setNewStartDate(originalEvent.startDate);
      setNewEndDate(originalEvent.endDate || originalEvent.startDate);
      setChangeType('swap');
      setReason('');
    }
  }, [originalEvent?.id]);

  if (!isOpen || !originalEvent) return null;

  const otherParent = Object.values(parents).find((p) => p.id !== currentParentId);
  const eventOwner = originalEvent.parentId ? parents[originalEvent.parentId] : null;

  const originalDays = getDaysBetween(
    originalEvent.startDate,
    originalEvent.endDate || originalEvent.startDate,
  );
  const newDays = newStartDate && newEndDate ? getDaysBetween(newStartDate, newEndDate) : 0;
  const daysDiff = newDays - originalDays;

  const handleSubmit = () => {
    if (!newStartDate || !newEndDate || !reason.trim()) return;

    setIsSubmitting(true);

    onSubmit?.({
      proposedChange: {
        type: changeType,
        originalStartDate: originalEvent.startDate,
        originalEndDate: originalEvent.endDate,
        newStartDate,
        newEndDate,
      },
      reason: reason.trim(),
    });

    // Simulate submission delay
    setTimeout(() => {
      setIsSubmitting(false);
      onClose?.();
    }, 500);
  };

  const isFormValid = newStartDate && newEndDate && reason.trim().length >= 10;

  return (
    <>
      {/* Backdrop */}
      <div
        className="animate-in fade-in fixed inset-0 z-40 bg-slate-900/60 backdrop-blur-sm duration-200"
        onClick={onClose}
      />

      {/* Modal */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="animate-in zoom-in-95 slide-in-from-bottom-4 w-full max-w-lg rounded-2xl border border-slate-200/60 bg-white shadow-2xl duration-300 dark:border-slate-700/60 dark:bg-slate-800"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="relative border-b border-slate-200 px-6 pb-4 pt-6 dark:border-slate-700">
            {/* Decorative gradient accent */}
            <div className="absolute left-6 right-6 top-0 h-1 rounded-full bg-gradient-to-r from-teal-500 via-teal-400 to-rose-400" />

            <div className="flex items-start justify-between">
              <div>
                <h2 className="text-xl font-semibold text-slate-800 dark:text-slate-100">
                  Request Schedule Change
                </h2>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                  Request approval from {otherParent?.name || 'the other parent'}
                </p>
              </div>
              <button
                onClick={onClose}
                className="-m-2 rounded-lg p-2 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-700 dark:hover:text-slate-300"
              >
                <svg
                  className="h-5 w-5"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
          </div>

          {/* Body */}
          <div className="max-h-[60vh] space-y-5 overflow-y-auto px-6 py-5">
            {/* Original Schedule Card */}
            <div className="relative">
              <div className="absolute -left-3 bottom-0 top-0 w-1 rounded-full bg-slate-300 dark:bg-slate-600" />
              <div className="rounded-xl border border-slate-200/60 bg-slate-50 p-4 dark:border-slate-600/60 dark:bg-slate-700/50">
                <div className="mb-3 flex items-center gap-2">
                  <div
                    className={`h-2.5 w-2.5 rounded-full ${
                      eventOwner?.color === 'violet' ? 'bg-violet-500' : 'bg-sky-500'
                    }`}
                  />
                  <span className="text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">
                    Current Schedule
                  </span>
                </div>
                <div className="flex items-center justify-between">
                  <div>
                    <p className="font-medium text-slate-800 dark:text-slate-100">
                      {formatDate(originalEvent.startDate)}
                    </p>
                    <p className="text-sm text-slate-500 dark:text-slate-400">
                      to {formatDate(originalEvent.endDate || originalEvent.startDate)}
                    </p>
                  </div>
                  <div className="text-right">
                    <p className="text-2xl font-bold text-slate-800 dark:text-slate-100">
                      {originalDays}
                    </p>
                    <p className="text-xs text-slate-500 dark:text-slate-400">
                      {originalDays === 1 ? 'day' : 'days'}
                    </p>
                  </div>
                </div>
              </div>
            </div>

            {/* Arrow indicator */}
            <div className="flex justify-center">
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-teal-100 dark:bg-teal-900/30">
                <svg
                  className="h-5 w-5 text-teal-600 dark:text-teal-400"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M19 14l-7 7m0 0l-7-7m7 7V3"
                  />
                </svg>
              </div>
            </div>

            {/* Change Type Selection */}
            <div>
              <label className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                Type of Change
              </label>
              <div className="grid grid-cols-2 gap-2">
                {[
                  { value: 'swap', label: 'Swap Days', icon: '↔️', desc: 'Trade days' },
                  { value: 'extend', label: 'Adjust Time', icon: '📅', desc: 'Change dates' },
                ].map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => setChangeType(option.value as ProposedChange['type'])}
                    className={`relative rounded-xl border-2 p-3 text-left transition-all duration-200 ${
                      changeType === option.value
                        ? 'border-teal-500 bg-teal-50 dark:bg-teal-900/20'
                        : 'border-slate-200 hover:border-slate-300 dark:border-slate-600 dark:hover:border-slate-500'
                    }`}
                  >
                    <Icon
                      name={option.icon}
                      size={20}
                      className={
                        changeType === option.value
                          ? 'text-teal-600 dark:text-teal-400'
                          : 'text-slate-600 dark:text-slate-400'
                      }
                    />
                    <p
                      className={`mt-1 text-sm font-medium ${
                        changeType === option.value
                          ? 'text-teal-700 dark:text-teal-300'
                          : 'text-slate-700 dark:text-slate-300'
                      }`}
                    >
                      {option.label}
                    </p>
                    {changeType === option.value && (
                      <div className="absolute right-2 top-2">
                        <svg
                          className="h-4 w-4 text-teal-500"
                          fill="currentColor"
                          viewBox="0 0 20 20"
                        >
                          <path
                            fillRule="evenodd"
                            d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                            clipRule="evenodd"
                          />
                        </svg>
                      </div>
                    )}
                  </button>
                ))}
              </div>
            </div>

            {/* Proposed New Dates */}
            <div className="relative">
              <div className="absolute -left-3 bottom-0 top-0 w-1 rounded-full bg-teal-500" />
              <div className="rounded-xl border border-teal-200/60 bg-teal-50 p-4 dark:border-teal-700/60 dark:bg-teal-900/20">
                <div className="mb-3 flex items-center gap-2">
                  <svg
                    className="h-4 w-4 text-teal-600 dark:text-teal-400"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={2}
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
                    />
                  </svg>
                  <span className="text-xs font-medium uppercase tracking-wide text-teal-700 dark:text-teal-300">
                    Proposed Schedule
                  </span>
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="mb-1.5 block text-xs text-teal-600 dark:text-teal-400">
                      Start Date
                    </label>
                    <input
                      type="date"
                      value={newStartDate}
                      onChange={(e) => setNewStartDate(e.target.value)}
                      className="w-full rounded-lg border border-teal-200 bg-white px-3 py-2 text-sm text-slate-800 outline-none transition-shadow focus:border-transparent focus:ring-2 focus:ring-teal-500 dark:border-teal-700 dark:bg-slate-800 dark:text-slate-100"
                    />
                  </div>
                  <div>
                    <label className="mb-1.5 block text-xs text-teal-600 dark:text-teal-400">
                      End Date
                    </label>
                    <input
                      type="date"
                      value={newEndDate}
                      onChange={(e) => setNewEndDate(e.target.value)}
                      className="w-full rounded-lg border border-teal-200 bg-white px-3 py-2 text-sm text-slate-800 outline-none transition-shadow focus:border-transparent focus:ring-2 focus:ring-teal-500 dark:border-teal-700 dark:bg-slate-800 dark:text-slate-100"
                    />
                  </div>
                </div>

                {/* Days difference indicator */}
                {newDays > 0 && (
                  <div className="mt-3 flex items-center justify-between text-sm">
                    <span className="text-teal-600 dark:text-teal-400">
                      {formatDate(newStartDate)} → {formatDate(newEndDate)}
                    </span>
                    <span
                      className={`font-semibold ${
                        daysDiff === 0
                          ? 'text-teal-600 dark:text-teal-400'
                          : daysDiff > 0
                            ? 'text-emerald-600 dark:text-emerald-400'
                            : 'text-rose-600 dark:text-rose-400'
                      }`}
                    >
                      {newDays} {newDays === 1 ? 'day' : 'days'}
                      {daysDiff !== 0 && (
                        <span className="ml-1 text-xs">
                          ({daysDiff > 0 ? '+' : ''}
                          {daysDiff})
                        </span>
                      )}
                    </span>
                  </div>
                )}
              </div>
            </div>

            {/* Reason Field */}
            <div>
              <label className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">
                Reason for Request
              </label>
              <textarea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="Explain why you're requesting this change. Be specific so the other parent can make an informed decision..."
                rows={4}
                className="w-full resize-none rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-800 outline-none transition-shadow placeholder:text-slate-400 focus:border-transparent focus:ring-2 focus:ring-teal-500 dark:border-slate-600 dark:bg-slate-700/50 dark:text-slate-100 dark:placeholder:text-slate-500"
              />
              <p
                className={`mt-1.5 text-xs ${
                  reason.length < 10
                    ? 'text-slate-400 dark:text-slate-500'
                    : 'text-teal-600 dark:text-teal-400'
                }`}
              >
                {reason.length < 10
                  ? `${10 - reason.length} more characters needed`
                  : '✓ Reason provided'}
              </p>
            </div>

            {/* Info callout */}
            <div className="flex gap-3 rounded-xl border border-amber-200/60 bg-amber-50 p-3 dark:border-amber-700/40 dark:bg-amber-900/20">
              <svg
                className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-500"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={2}
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
              <p className="text-xs text-amber-700 dark:text-amber-300">
                {otherParent?.name || 'The other parent'} will be notified and must approve this
                change before it takes effect.
              </p>
            </div>
          </div>

          {/* Footer */}
          <div className="rounded-b-2xl border-t border-slate-200 bg-slate-50 px-6 py-4 dark:border-slate-700 dark:bg-slate-800/50">
            <div className="flex items-center justify-end gap-3">
              <button
                onClick={onClose}
                className="px-4 py-2.5 text-sm font-medium text-slate-600 transition-colors hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200"
              >
                Cancel
              </button>
              <button
                onClick={handleSubmit}
                disabled={!isFormValid || isSubmitting}
                className={`inline-flex items-center gap-2 rounded-xl px-5 py-2.5 text-sm font-medium transition-all duration-200 ${
                  isFormValid && !isSubmitting
                    ? 'bg-teal-600 text-white shadow-lg shadow-teal-500/20 hover:-translate-y-0.5 hover:bg-teal-700 hover:shadow-xl hover:shadow-teal-500/30 active:translate-y-0'
                    : 'cursor-not-allowed bg-slate-200 text-slate-400 dark:bg-slate-700 dark:text-slate-500'
                }`}
              >
                {isSubmitting ? (
                  <>
                    <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                      <circle
                        className="opacity-25"
                        cx="12"
                        cy="12"
                        r="10"
                        stroke="currentColor"
                        strokeWidth="4"
                      />
                      <path
                        className="opacity-75"
                        fill="currentColor"
                        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                      />
                    </svg>
                    Sending...
                  </>
                ) : (
                  <>
                    <svg
                      className="h-4 w-4"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={2}
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8"
                      />
                    </svg>
                    Send Request
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
