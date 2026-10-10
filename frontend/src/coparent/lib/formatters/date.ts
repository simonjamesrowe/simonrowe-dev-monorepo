/**
 * How CoParent writes a date, so every screen says it the same way:
 *
 * - short, for lists, summaries and forms: "Sat 10 Oct", with the year only when it is not this
 *   year ("Fri 10 Oct 2025");
 * - long, for headings and dates of birth: "Saturday 10 October 2026".
 *
 * Quick add (components/assistant) still has its own formats; it will move to these later.
 */

type DateInput = string | Date | null | undefined;

const SHORT = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
const LONG = new Intl.DateTimeFormat('en-GB', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
});
const TIME = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });

/**
 * A Date from "2026-10-10", an ISO date-time or a Date. A bare date is read at local noon, so no
 * time zone can move it to the day before.
 */
export function toDate(value: DateInput): Date | null {
  if (!value) return null;
  const date =
    value instanceof Date
      ? value
      : /^\d{4}-\d{2}-\d{2}$/.test(value)
        ? new Date(`${value}T12:00:00`)
        : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** Builds the text from the parts, so no locale's commas end up in it. */
function joinParts(formatter: Intl.DateTimeFormat, date: Date): string {
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    formatter.formatToParts(date).find((entry) => entry.type === type)?.value ?? '';
  return [part('weekday'), part('day'), part('month'), part('year')].filter(Boolean).join(' ');
}

/** "Sat 10 Oct", or "Fri 10 Oct 2025" outside the current year. Unreadable text comes back as is. */
export function formatShortDate(value: DateInput, now: Date = new Date()): string {
  const date = toDate(value);
  if (!date) return value ? String(value) : '';
  const text = joinParts(SHORT, date);
  return date.getFullYear() === now.getFullYear() ? text : `${text} ${date.getFullYear()}`;
}

/** "Saturday 10 October 2026". Unreadable text comes back as is. */
export function formatLongDate(value: DateInput): string {
  const date = toDate(value);
  if (!date) return value ? String(value) : '';
  return joinParts(LONG, date);
}

/** "14:05", on the 24-hour clock the calendar uses. */
export function formatTime(value: DateInput): string {
  const date = toDate(value);
  return date ? TIME.format(date) : '';
}

/** "Sat 10 Oct, 14:05", for when something happened. */
export function formatDateTime(value: DateInput, now: Date = new Date()): string {
  const date = toDate(value);
  if (!date) return value ? String(value) : '';
  return `${formatShortDate(date, now)}, ${formatTime(date)}`;
}

// TEMP-LEGACY: removed once family setup and onboarding use the formatters above.
export const formatDate = (value?: string | Date | null) => formatLongDate(value);
