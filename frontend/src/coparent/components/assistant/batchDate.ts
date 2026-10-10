const DAY = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
const DAY_AND_YEAR = new Intl.DateTimeFormat('en-GB', {
  weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
});

/**
 * When a batch was made, in the app's short style: "Sat 10 Oct", with the year only when it is
 * not this year. Read in the parent's own time zone, since that is the day they wrote the note.
 */
export function formatBatchDate(value: string, now: Date = new Date()): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  if (date.getFullYear() === now.getFullYear()) return DAY.format(date);
  // en-GB puts a comma after the weekday once a year is added; the app never shows one.
  return DAY_AND_YEAR.formatToParts(date)
    .filter((part) => part.type !== 'literal')
    .map((part) => part.value)
    .join(' ');
}
