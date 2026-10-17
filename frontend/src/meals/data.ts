import { createContext, useContext, useMemo } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { MealsApi } from './api'
import type { Ingredient, MealSlot, NewRating, Recipe, Template } from './types'

export const MealsApiContext = createContext<MealsApi | null>(null)

export function useMealsApi(): MealsApi {
  const api = useContext(MealsApiContext)
  if (!api) throw new Error('useMealsApi used outside MealsApiContext')
  return api
}

export interface Library {
  recipes: Recipe[]
  byId: Map<string, Recipe>
  ingredients: Map<string, Ingredient>
  templates: Template[]
}

/** The whole library in one go: it is small, and every screen needs ingredient lines. */
export function useLibrary() {
  const api = useMealsApi()
  const recipes = useQuery({ queryKey: ['meals', 'recipes'], queryFn: api.recipes, staleTime: 5 * 60_000 })
  const ingredients = useQuery({ queryKey: ['meals', 'ingredients'], queryFn: api.ingredients, staleTime: 5 * 60_000 })
  const templates = useQuery({ queryKey: ['meals', 'templates'], queryFn: api.templates, staleTime: 5 * 60_000 })
  const library = useMemo<Library | undefined>(() => {
    if (!recipes.data || !ingredients.data || !templates.data) return undefined
    return {
      recipes: recipes.data,
      byId: new Map(recipes.data.map((r) => [r.id, r])),
      ingredients: new Map(ingredients.data.map((i) => [i.name, i])),
      templates: templates.data,
    }
  }, [recipes.data, ingredients.data, templates.data])
  return {
    library,
    isLoading: recipes.isLoading || ingredients.isLoading || templates.isLoading,
    error: recipes.error ?? ingredients.error ?? templates.error,
  }
}

/**
 * Everything personal sits under one key, so any change to the kitchen, Up next or ratings
 * refreshes coverage, suggestions and the shopping list together, without re-fetching the
 * library itself.
 */
export const MINE = ['meals', 'me'] as const

export function useRatings() {
  const api = useMealsApi()
  return useQuery({ queryKey: [...MINE, 'ratings'], queryFn: api.ratings })
}

export function useKitchen() {
  const api = useMealsApi()
  return useQuery({ queryKey: [...MINE, 'kitchen'], queryFn: api.kitchen })
}

export function useHistory() {
  const api = useMealsApi()
  return useQuery({ queryKey: [...MINE, 'history'], queryFn: api.history })
}

export function useCoverage() {
  const api = useMealsApi()
  return useQuery({ queryKey: [...MINE, 'coverage'], queryFn: api.coverage })
}

export function useSuggestions(slot: MealSlot) {
  const api = useMealsApi()
  return useQuery({ queryKey: [...MINE, 'suggestions', slot], queryFn: () => api.suggestions(slot) })
}

export function useUpNext() {
  const api = useMealsApi()
  return useQuery({ queryKey: [...MINE, 'up-next'], queryFn: api.upNext })
}

export function useShopping() {
  const api = useMealsApi()
  return useQuery({ queryKey: [...MINE, 'shopping'], queryFn: api.shopping })
}

export function useTokens() {
  const api = useMealsApi()
  return useQuery({ queryKey: [...MINE, 'tokens'], queryFn: api.tokens })
}

/** A mutation that refreshes everything personal once it succeeds. */
export function useMine<A, R>(fn: (api: MealsApi, arg: A) => Promise<R>) {
  const api = useMealsApi()
  const client = useQueryClient()
  return useMutation({
    mutationFn: (arg: A) => fn(api, arg),
    onSuccess: () => client.invalidateQueries({ queryKey: MINE }),
  })
}

export function useRate() {
  return useMine((api, rating: NewRating) => api.rate(rating))
}

export function useDeleteRating() {
  return useMine((api, id: string) => api.deleteRating(id))
}
