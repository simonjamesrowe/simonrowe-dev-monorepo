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
