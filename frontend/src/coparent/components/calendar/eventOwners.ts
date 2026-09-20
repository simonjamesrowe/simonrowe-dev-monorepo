import type { Event, Parent } from '../../types/calendar';

export const getEventOwnerLabel = (event: Event, parents: Record<string, Parent>) => {
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
