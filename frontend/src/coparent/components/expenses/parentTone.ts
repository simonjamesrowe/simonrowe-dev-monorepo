import type { Parent } from '../../lib/api/client';

export type ParentTone = 'violet' | 'sky';

/**
 * The colour that marks a parent across the app: violet for the primary parent, sky for the
 * co-parent. The same fallback the calendar uses, since no parent row sets a colour today.
 */
export function parentTone(parent: Pick<Parent, 'color' | 'role'> | undefined): ParentTone {
  if (parent?.color === 'violet' || parent?.color === 'sky') return parent.color;
  return parent?.role === 'primary' ? 'violet' : 'sky';
}

/** A parent's first name, for sentences such as "Sam owes you". */
export function firstName(parent: Pick<Parent, 'fullName'> | undefined, fallback = 'Your co-parent'): string {
  const name = parent?.fullName?.trim();
  return name ? name.split(/\s+/)[0] : fallback;
}
