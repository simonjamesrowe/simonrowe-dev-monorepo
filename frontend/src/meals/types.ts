export type Unit = 'g' | 'ml' | 'each'
export type Category = 'breakfast' | 'main' | 'snack' | 'drink' | 'dessert'

export interface IngredientLine {
  text: string
  name: string
  quantity: number | null
  unit: Unit | null
}

export interface Recipe {
  id: string
  name: string
  source: { type: 'BOOK' | 'LINK' | 'PHOTO'; book: string | null; pages: number[]; url: string | null }
  section: string | null
  category: Category
  vegetarian: boolean
  vegan: boolean
  servings: number
  perServing: { kcal: number; protein: number; carbs: number; fat: number }
  macrosEstimated: boolean
  ingredients: IngredientLine[]
  method: string[]
  tips: string[]
  swaps: string[]
  freezable: boolean | null
  mainProtein: string | null
  photo: string | null
}

export interface Ingredient {
  name: string
  unit: Unit | null
  tier: 'tracked' | 'staple'
  aisle: string
  assumed: boolean
}

export interface Template {
  id: string
  label: string
  source: string
  kcal: number
  week: number
  recipeIds: string[]
}

export interface Rating {
  id: string
  recipeId: string
  at: string
  score: number
  again: boolean
  tags: string[]
  notes: string
}

export interface NewRating {
  recipeId: string
  score: number
  again: boolean
  tags: string[]
  notes: string
}

export interface ImportReport {
  recipesAdded: number
  recipesExisting: number
  ingredientsAdded: number
  templatesAdded: number
  photosStored: number
  skipped: string[]
}

export type Tier = 'tracked' | 'staple'
export type StapleStatus = 'have' | 'low' | 'out'
export type Shop = 'Ocado' | 'Amazon' | 'Deliveroo'
export type MealSlot = 'breakfast' | 'main' | 'snack'

export interface InventoryItem {
  id: string
  name: string
  tier: Tier
  quantity: number | null
  unit: Unit | null
  status: StapleStatus | null
  useBy: string | null
  updatedAt: string
}

export interface InventoryChange {
  name: string
  action: 'add' | 'use' | 'set' | 'status' | 'remove'
  quantity?: number | null
  unit?: Unit | null
  status?: StapleStatus | null
  useBy?: string | null
  source?: string | null
}

export interface InventoryEvent {
  id: string
  at: string
  kind: string
  summary: string
}

export interface Proposal {
  changes: InventoryChange[]
  newIngredients: string[]
}

export type Cover = 'HAVE' | 'SHORT' | 'MISSING' | 'UNKNOWN'

export interface Coverage {
  ratio: number
  missing: string[]
  shortOf: string[]
  unknown: string[]
  lines: Record<string, Cover>
}

export interface Suggestion {
  recipeId: string
  score: number
  coverage: Coverage
  reasons: string[]
  expiring: string[]
}

export interface Deduction {
  name: string
  tier: Tier
  amount: number | null
  unit: Unit | null
  ranOut: boolean
}

export interface UpNextItem {
  recipeId: string
  portions: number
  addedAt: string
}

export interface ShoppingLine {
  name: string
  aisle: string
  tier: Tier
  toBuy: number | null
  unit: Unit | null
  why: string
  recipes: string[]
}

export interface ShoppingItem {
  line: ShoppingLine
  searchTerm: string
  basket: Shop | null
}

export interface ShoppingList {
  items: ShoppingItem[]
  upNextCount: number
}

export interface NewIngredient {
  name: string
  unit: Unit | null
  tier: Tier
  aisle: string
}

/** A recipe as the editor holds and sends it. */
export interface RecipeInput {
  name: string
  category: Category
  section: string | null
  servings: number
  perServing: { kcal: number; protein: number; carbs: number; fat: number }
  macrosEstimated: boolean
  vegetarian: boolean
  vegan: boolean
  freezable: boolean | null
  mainProtein: string | null
  ingredients: IngredientLine[]
  method: string[]
  tips: string[]
  swaps: string[]
  sourceType: 'BOOK' | 'LINK' | 'PHOTO' | null
  sourceUrl: string | null
  photo: string | null
  newIngredients: NewIngredient[]
}

export interface McpToken {
  id: string
  label: string
  hint: string
  createdAt: string
  lastUsedAt: string | null
  revokedAt: string | null
}

export interface CookedRequest {
  recipeId: string
  portions: number
  score: number
  again: boolean
  tags: string[]
  notes: string
  deductions: Deduction[]
}
