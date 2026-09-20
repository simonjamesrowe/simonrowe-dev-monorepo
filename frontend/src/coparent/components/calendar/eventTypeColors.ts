export const DEFAULT_EVENT_TYPES = [
  { value: 'activity', label: 'Activity', description: 'Sports, lessons, clubs, playdates' },
  { value: 'medical', label: 'Medical', description: 'Doctor visits, therapy, dental' },
  { value: 'school', label: 'School', description: 'Conferences, events, deadlines' },
  { value: 'holiday', label: 'Holiday', description: 'Holidays, birthdays, family trips' },
  { value: 'custody', label: 'Custody', description: 'Overnight schedule blocks' },
];

export function getEventTypeColor(type: string): string {
  const normalized = type.trim().toLowerCase();
  switch (normalized) {
    case 'custody':
      return 'indigo';
    case 'activity':
      return 'teal';
    case 'medical':
      return 'red';
    case 'school':
      return 'amber';
    case 'holiday':
      return 'emerald';
    default:
      return 'slate';
  }
}
