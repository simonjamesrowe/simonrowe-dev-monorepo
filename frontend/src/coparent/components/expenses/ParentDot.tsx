import type { ParentTone } from './parentTone';

export function ParentDot({ tone }: { tone: ParentTone | null }) {
  return <span className={`expense-dot expense-dot--${tone ?? 'none'}`} aria-hidden="true" />;
}
