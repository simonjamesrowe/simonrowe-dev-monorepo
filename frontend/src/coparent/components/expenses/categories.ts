import {
  Baby,
  GraduationCap,
  Plane,
  Receipt,
  Shirt,
  Stethoscope,
  Trophy,
  UtensilsCrossed,
  type LucideIcon,
} from 'lucide-react';

import type { ExpenseCategory } from '../../types/expenses';

export interface CategoryInfo {
  label: string;
  icon: LucideIcon;
  tone: 'amber' | 'rose' | 'sky' | 'violet' | 'emerald' | 'cyan' | 'orange' | 'slate';
}

export const EXPENSE_CATEGORIES: Record<ExpenseCategory, CategoryInfo> = {
  education: { label: 'Education', icon: GraduationCap, tone: 'amber' },
  clothing: { label: 'Clothing', icon: Shirt, tone: 'rose' },
  medical: { label: 'Medical', icon: Stethoscope, tone: 'sky' },
  activities: { label: 'Activities', icon: Trophy, tone: 'violet' },
  childcare: { label: 'Childcare', icon: Baby, tone: 'emerald' },
  travel: { label: 'Travel', icon: Plane, tone: 'cyan' },
  food: { label: 'Food', icon: UtensilsCrossed, tone: 'orange' },
  other: { label: 'Other', icon: Receipt, tone: 'slate' },
};
