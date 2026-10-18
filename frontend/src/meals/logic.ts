import type { Category, IngredientLine, InventoryChange, MealSlot, Rating, Recipe, Unit } from './types'

export type { MealSlot }

/**
 * Who the cookbook recipes are by. The app names the author as a recipe's source and never the
 * books themselves.
 */
export const COOKBOOK_AUTHOR = 'Dave Fell'

/** Friendly names for recipe sites, by registrable host. Anything else shows its host name. */
const SITES: Record<string, string> = {
  'bbcgoodfood.com': 'BBC Good Food',
  'bbc.co.uk': 'BBC Food',
  'jamieoliver.com': 'Jamie Oliver',
  'nigella.com': 'Nigella',
  'deliciousmagazine.co.uk': 'delicious.',
  'theguardian.com': 'The Guardian',
  'nytimes.com': 'NYT Cooking',
  'seriouseats.com': 'Serious Eats',
  'bonappetit.com': 'Bon Appétit',
  'allrecipes.com': 'Allrecipes',
  'recipetineats.com': 'RecipeTin Eats',
  'olivemagazine.com': 'Olive',
  'waitrose.com': 'Waitrose',
  'tesco.com': 'Tesco Real Food',
}

export interface RecipeSourceLabel {
  label: string
  url: string | null
}

/** Where a recipe came from, as a reader would say it: an author, a site, or your own photo. */
export function sourceOf(recipe: Recipe): RecipeSourceLabel | null {
  const { type, url } = recipe.source
  if (type === 'BOOK') return { label: COOKBOOK_AUTHOR, url: null }
  if (type === 'PHOTO') return { label: 'Your photo', url: null }
  if (!url) return null
  let host: string
  try {
    const parsed = new URL(url)
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return null
    host = parsed.hostname.toLowerCase().replace(/^www\./, '')
  } catch {
    return null
  }
  const known = Object.keys(SITES).find((site) => host === site || host.endsWith(`.${site}`))
  return { label: known ? SITES[known] : host, url }
}

export const TAGS = ['filling', 'spicy', 'quick', 'faff', 'great for prep', 'too sweet', 'bland', 'kids liked it']


/**
 * Splits a search into words, each with the forms worth looking for: as typed, and without a
 * plural ending, so "tomatoes" finds "tomato" and "onions" finds "onion".
 */
export function searchTerms(query: string): string[][] {
  return query.toLowerCase().split(/\s+/).filter(Boolean).map((word) => {
    const forms = [word]
    if (word.length > 3 && word.endsWith('es')) forms.push(word.slice(0, -2))
    if (word.length > 3 && word.endsWith('s')) forms.push(word.slice(0, -1))
    return forms
  })
}

/**
 * Whether a recipe matches every word of a search, looking at its name, section and main protein,
 * and at each ingredient both as the catalogue names it and as the recipe prints it ("broccoli
 * florets" is found by "broccoli" even when the catalogue calls it something else).
 */
export function recipeMatches(recipe: Recipe, terms: string[][]): boolean {
  if (terms.length === 0) return true
  const haystack = [
    recipe.name, recipe.section ?? '', recipe.mainProtein ?? '',
    ...recipe.ingredients.flatMap((l) => [l.name, l.text]),
  ].join('\n').toLowerCase()
  return terms.every((forms) => forms.some((form) => haystack.includes(form)))
}

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
