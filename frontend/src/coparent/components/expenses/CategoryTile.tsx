import type { ExpenseCategory } from '../../types/expenses';

import { EXPENSE_CATEGORIES } from './categories';

export function CategoryTile({ category, size = 'md' }: { category: ExpenseCategory; size?: 'sm' | 'md' }) {
  const info = EXPENSE_CATEGORIES[category] ?? EXPENSE_CATEGORIES.other;
  const Icon = info.icon;
  return (
    <span className={`expense-tile expense-tile--${info.tone} expense-tile--${size}`} aria-hidden="true">
      <Icon size={size === 'sm' ? 16 : 22} />
    </span>
  );
}
