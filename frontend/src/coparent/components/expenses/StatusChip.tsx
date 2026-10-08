import type { ExpenseStatus } from './expenseStatus';

export function StatusChip({ status }: { status: Pick<ExpenseStatus, 'label' | 'tone'> }) {
  return <span className={`expense-chip expense-chip--${status.tone}`}>{status.label}</span>;
}
