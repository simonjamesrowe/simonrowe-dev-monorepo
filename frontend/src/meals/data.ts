import { createContext, useContext, useMemo } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { MealsApi } from './api'
import type { Favourite, Ingredient, MealSlot, NewRating, Recipe } from './types'

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
}

/** The whole library in one go: it is small, and every screen needs ingredient lines. */
export function useLibrary() {
  const api = useMealsApi()
  const recipes = useQuery({ queryKey: ['meals', 'recipes'], queryFn: api.recipes, staleTime: 5 * 60_000 })
  const ingredients = useQuery({ queryKey: ['meals', 'ingredients'], queryFn: api.ingredients, staleTime: 5 * 60_000 })
  const library = useMemo<Library | undefined>(() => {
    if (!recipes.data || !ingredients.data) return undefined
    return {
      recipes: recipes.data,
      byId: new Map(recipes.data.map((r) => [r.id, r])),
      ingredients: new Map(ingredients.data.map((i) => [i.name, i])),
    }
  }, [recipes.data, ingredients.data])
  return {
    library,
    isLoading: recipes.isLoading || ingredients.isLoading,
    error: recipes.error ?? ingredients.error,
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

const FAVOURITES = [...MINE, 'favourites'] as const

/** The signed-in person's hearted recipes, newest first. */
export function useFavourites() {
  const api = useMealsApi()
  // Every card asks for this; only this person's hearting changes it, and that refetches it.
  const query = useQuery({ queryKey: FAVOURITES, queryFn: api.favourites, staleTime: 5 * 60_000 })
  const ids = useMemo(() => new Set((query.data ?? []).map((f) => f.recipeId)), [query.data])
  return { ...query, ids }
}

/**
 * Hearts or un-hearts a recipe. The heart changes at once and is put back if the server refuses;
 * only the favourites are refetched, since nothing else depends on them.
 */
export function useToggleFavourite() {
  const api = useMealsApi()
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ recipeId, on }: { recipeId: string; on: boolean }) =>
      on ? api.favourite(recipeId).then(() => undefined) : api.unfavourite(recipeId),
    onMutate: async ({ recipeId, on }) => {
      await client.cancelQueries({ queryKey: FAVOURITES })
      const before = client.getQueryData<Favourite[]>(FAVOURITES)
      const rest = (before ?? []).filter((f) => f.recipeId !== recipeId)
      client.setQueryData<Favourite[]>(FAVOURITES, on
        ? [{ id: recipeId, recipeId, addedAt: new Date().toISOString() }, ...rest]
        : rest)
      return { before }
    },
    onError: (_error, _vars, context) => client.setQueryData(FAVOURITES, context?.before),
    onSettled: () => client.invalidateQueries({ queryKey: FAVOURITES }),
  })
}

/**
 * Whether the kitchen holds anything fresh. Until it does, "what can I cook" has nothing to go
 * on: every fresh ingredient reads as missing and every staple as "check", so a recipe needing
 * one or two fresh things would look one or two things short of an empty fridge.
 */
export function useKitchenHasFood() {
  const kitchen = useKitchen()
  const hasFood = (kitchen.data ?? []).some((i) => i.tier === 'tracked' && (i.quantity ?? 0) > 0)
  return { hasFood, isLoading: kitchen.isLoading, isSuccess: kitchen.isSuccess }
}
