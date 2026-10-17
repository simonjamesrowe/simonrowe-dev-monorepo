import { createContext, useContext, useMemo } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { MealsApi } from './api'
import type { Ingredient, NewRating, Recipe, Template } from './types'

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

export function useRatings() {
  const api = useMealsApi()
  return useQuery({ queryKey: ['meals', 'ratings'], queryFn: api.ratings })
}

export function useRate() {
  const api = useMealsApi()
  const client = useQueryClient()
  return useMutation({
    mutationFn: (rating: NewRating) => api.rate(rating),
    onSuccess: () => client.invalidateQueries({ queryKey: ['meals', 'ratings'] }),
  })
}

export function useDeleteRating() {
  const api = useMealsApi()
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.deleteRating(id),
    onSuccess: () => client.invalidateQueries({ queryKey: ['meals', 'ratings'] }),
  })
}
