import type { Category, IngredientLine, InventoryChange, MealSlot, Rating, Unit } from './types'

export type { MealSlot }

export const BOOKS: Record<string, string> = {
  mmmb1: 'Macro Meals, Micro Budget',
  mmmb2: 'Macro Meals, Micro Budget Vol. 2',
  mpmb: 'Macro Prep, Micro Budget',
}

export const TAGS = ['filling', 'spicy', 'quick', 'faff', 'great for prep', 'too sweet', 'bland', 'kids liked it']


export function formatQty(quantity: number | null | undefined, unit: Unit | null | undefined): string {
  if (quantity == null || unit == null) return ''
  if (unit === 'each') return String(+quantity.toFixed(1))
  if (quantity >= 1000) return `${+(quantity / 1000).toFixed(2)}${unit === 'g' ? 'kg' : 'L'}`
  return `${Math.round(quantity)}${unit}`
}

/** A line as printed when making the book's portions, otherwise rewritten to the new amount. */
export function scaledLine(line: IngredientLine, scale: number): string {
  if (scale === 1 || line.quantity == null) return line.text
  return `${formatQty(line.quantity * scale, line.unit)} ${line.name}`
}

export function ratingsFor(recipeId: string, ratings: Rating[]): Rating[] {
  return ratings.filter((r) => r.recipeId === recipeId).sort((a, b) => b.at.localeCompare(a.at))
}

export function averageScore(recipeId: string, ratings: Rating[]): number | null {
  const mine = ratings.filter((r) => r.recipeId === recipeId)
  if (!mine.length) return null
  return mine.reduce((sum, r) => sum + r.score, 0) / mine.length
}

export const MEAL_SLOTS: { key: MealSlot; label: string; categories: Category[] }[] = [
  { key: 'breakfast', label: 'Breakfast', categories: ['breakfast'] },
  { key: 'main', label: 'Lunch & dinner', categories: ['main'] },
  { key: 'snack', label: 'Snack or sweet', categories: ['snack', 'dessert', 'drink'] },
]

export function defaultSlot(now: Date): MealSlot {
  return now.getHours() < 11 ? 'breakfast' : 'main'
}

const DAY_MS = 86_400_000

export function daysUntil(date: string | null | undefined, today = new Date()): number | null {
  if (!date) return null
  const start = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((Date.parse(date) - start) / DAY_MS)
}

export function describeUseBy(date: string | null | undefined, today = new Date()): { text: string; tone: 'past' | 'now' | 'soon' | '' } | null {
  const d = daysUntil(date, today)
  if (d == null) return null
  if (d < 0) return { text: `${-d}d past`, tone: 'past' }
  if (d === 0) return { text: 'today', tone: 'now' }
  if (d === 1) return { text: 'tomorrow', tone: 'now' }
  return { text: `${d} days`, tone: d <= 3 ? 'soon' : '' }
}

export function describeChange(c: InventoryChange): string {
  const q = c.unit === 'each' && c.quantity != null ? String(+c.quantity.toFixed(1)) : formatQty(c.quantity, c.unit)
  switch (c.action) {
    case 'add': return `+ ${q ? `${q} ` : ''}${c.name}`
    case 'use': return `− ${q ? `${q} ` : ''}${c.name}`
    case 'set': return `${c.name} → ${q || '0'}`
    case 'remove': return `removed ${c.name}`
    default: return `${c.name}: ${c.status ?? 'have'}`
  }
}
