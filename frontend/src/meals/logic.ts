import type { Category, IngredientLine, Rating, Recipe, Unit } from './types'

const DAY = 86_400_000

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

export type MealSlot = 'breakfast' | 'main' | 'snack'

export const MEAL_SLOTS: { key: MealSlot; label: string; categories: Category[] }[] = [
  { key: 'breakfast', label: 'Breakfast', categories: ['breakfast'] },
  { key: 'main', label: 'Lunch & dinner', categories: ['main'] },
  { key: 'snack', label: 'Snack or sweet', categories: ['snack', 'dessert', 'drink'] },
]

export function defaultSlot(now: Date): MealSlot {
  return now.getHours() < 11 ? 'breakfast' : 'main'
}

// A stable per-day shuffle, so "try something new" changes daily but not on every render.
function dailyOrder(id: string, now: Date): number {
  const seed = `${now.toISOString().slice(0, 10)}:${id}`
  let hash = 0
  for (let i = 0; i < seed.length; i++) hash = (Math.imul(hash, 31) + seed.charCodeAt(i)) >>> 0
  return hash
}

export interface HomeSections {
  favourites: Recipe[]
  untried: Recipe[]
  recent: Recipe[]
}

/**
 * Suggestions before the kitchen exists: the ones you rated highly and haven't had lately,
 * a daily selection you've never tried (leaning on the protein of the ones you liked), and
 * what you've cooked recently. Never suggests a recipe you said not to make again.
 */
export function homeSections(recipes: Recipe[], ratings: Rating[], slot: MealSlot, now: Date): HomeSections {
  const categories = MEAL_SLOTS.find((s) => s.key === slot)!.categories
  const inSlot = recipes.filter((r) => categories.includes(r.category))
  const latest = new Map<string, Rating>()
  for (const r of [...ratings].sort((a, b) => b.at.localeCompare(a.at))) {
    if (!latest.has(r.recipeId)) latest.set(r.recipeId, r)
  }
  const daysSince = (id: string) => {
    const last = latest.get(id)
    return last ? (now.getTime() - Date.parse(last.at)) / DAY : Infinity
  }
  const likedProteins = new Set(ratings.filter((r) => r.score >= 4)
    .map((r) => recipes.find((x) => x.id === r.recipeId)?.mainProtein).filter(Boolean))

  const favourites = inSlot
    .filter((r) => latest.get(r.id)?.again !== false)
    .map((r) => ({ r, avg: averageScore(r.id, ratings) }))
    .filter((x) => x.avg != null && x.avg >= 4 && daysSince(x.r.id) >= 5)
    .sort((a, b) => b.avg! - a.avg! || daysSince(b.r.id) - daysSince(a.r.id))
    .map((x) => x.r)
    .slice(0, 8)

  const untried = inSlot
    .filter((r) => !latest.has(r.id))
    .sort((a, b) => {
      const liked = Number(likedProteins.has(b.mainProtein)) - Number(likedProteins.has(a.mainProtein))
      return liked || dailyOrder(a.id, now) - dailyOrder(b.id, now)
    })
    .slice(0, 8)

  const recent = recipes
    .filter((r) => daysSince(r.id) < 14)
    .sort((a, b) => daysSince(a.id) - daysSince(b.id))
    .slice(0, 8)

  return { favourites, untried, recent }
}
