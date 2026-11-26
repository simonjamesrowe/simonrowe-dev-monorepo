import type { SchoolEventDetails } from '../../types/schoolSuggestions';

const DAY = new Intl.DateTimeFormat('en-GB', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  timeZone: 'UTC',
});

function day(value: string) {
  return DAY.format(new Date(`${value.slice(0, 10)}T00:00:00Z`));
}

/** "Fri 27 Nov, 09:15–10:45" or "Mon 1 Dec to Fri 5 Dec, all day". */
export function describeWhen(details: SchoolEventDetails) {
  const dates =
    details.endDate && details.endDate.slice(0, 10) !== details.startDate.slice(0, 10)
      ? `${day(details.startDate)} to ${day(details.endDate)}`
      : day(details.startDate);
  if (!details.startTime) return `${dates}, all day`;
  return details.endTime
    ? `${dates}, ${details.startTime}–${details.endTime}`
    : `${dates}, ${details.startTime}`;
}
