import type { Rating, Recipe } from './types'

/** Invented recipes for tests: the real library is copyrighted and never in this repository. */
export function recipe(overrides: Partial<Recipe> = {}): Recipe {
  return {
    id: 'test-chilli',
    name: 'Test Chilli',
    source: { type: 'BOOK', book: 'mmmb1', pages: [12], url: null },
    section: 'Mains',
    category: 'main',
    vegetarian: false,
    vegan: false,
    servings: 4,
    perServing: { kcal: 450, protein: 40, carbs: 35, fat: 15 },
    macrosEstimated: false,
    ingredients: [
      { text: '500g lean beef mince', name: 'beef mince', quantity: 500, unit: 'g' },
      { text: '2 onions', name: 'onion', quantity: 2, unit: 'each' },
      { text: 'Salt to taste', name: 'salt', quantity: null, unit: null },
    ],
    method: ['Brown the mince.', 'Add the onions.', 'Simmer for 20 minutes.'],
    tips: ['Freeze the spares.'],
    swaps: [],
    freezable: true,
    mainProtein: 'beef',
    photo: null,
    ...overrides,
  }
}

export function rating(overrides: Partial<Rating> = {}): Rating {
  return {
    id: 'r1',
    recipeId: 'test-chilli',
    at: '2026-10-01T18:00:00Z',
    score: 5,
    again: true,
    tags: [],
    notes: '',
    ...overrides,
  }
}
