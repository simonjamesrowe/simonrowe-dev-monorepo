import { Users } from 'lucide-react';

import { childrenOf, type DocChild } from './documentText';

/**
 * Who a document is for, as the coloured initial(s) other pages use for a child, or the family
 * mark for a whole-family document. Decoration: the name is always written beside it.
 */
export function ChildDots({ ids, children }: { ids: string[]; children: DocChild[] }) {
  const kids = childrenOf(ids, children);
  if (!kids.length) {
    return (
      <span className="document-dot document-dot--family" aria-hidden="true">
        <Users size={11} />
      </span>
    );
  }
  return (
    <span className="document-dots" aria-hidden="true">
      {kids.map((child) => (
        <span key={child.id} className={`document-dot document-dot--tone-${child.tone}`}>
          {child.firstName.charAt(0)}
        </span>
      ))}
    </span>
  );
}
