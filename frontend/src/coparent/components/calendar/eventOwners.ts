import type { Event, Parent } from '../../types/calendar';

/**
 * Whose event this is, for a family with more than one parent on the calendar. With only one,
 * every event would carry the same name, which says nothing and on a phone took the whole pill.
 */
export const getEventOwnerLabel = (event: Event, parents: Record<string, Parent>) => {
  if (Object.keys(parents).length < 2) return undefined;

  const ids =
    event.parentIds && event.parentIds.length > 0
      ? event.parentIds
      : event.parentId
        ? [event.parentId]
        : [];

  if (ids.length === 0) return undefined;

  const names = ids.map((id) => parents[id]?.name).filter((name): name is string => Boolean(name));

  return names.length > 0 ? names.join(', ') : undefined;
};
