import type {
  CookedRequest, Coverage, Deduction, ImportReport, Ingredient, InventoryChange, InventoryEvent,
  InventoryItem, McpToken, MealSlot, NewRating, Proposal, Rating, Recipe, RecipeInput, Shop,
  ShoppingList, Suggestion, UpNextItem, Favourite,
} from './types'

export type TokenGetter = () => Promise<string>

export class MealsApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
}

/**
 * The meals API, same-origin under /api/meals. Every call carries the bearer token: there is no
 * anonymous route, photos included, which is why a photo is fetched as a blob rather than put
 * in an <img src>.
 */
export function createMealsApi(getToken: TokenGetter) {
  async function request(path: string, init: RequestInit = {}): Promise<Response> {
    const token = await getToken()
    const headers = new Headers(init.headers)
    headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(`/api/meals${path}`, { ...init, headers })
    if (!response.ok) {
      let message = `${response.status} ${response.statusText}`
      try {
        const body = await response.json() as { message?: string; error?: string }
        message = body.message ?? body.error ?? message
      } catch {
        // not JSON: keep the status line
      }
      throw new MealsApiError(response.status, message)
    }
    return response
  }

  async function json<T>(path: string, init?: RequestInit): Promise<T> {
    return (await request(path, init)).json() as Promise<T>
  }

  function send<T>(path: string, method: string, body: unknown): Promise<T> {
    return json<T>(path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  }

  return {
    recipes: () => json<Recipe[]>('/recipes'),
    ingredients: () => json<Ingredient[]>('/ingredients'),
    ratings: () => json<Rating[]>('/ratings'),
    rate: (rating: NewRating) => send<Rating>('/ratings', 'POST', rating),
    deleteRating: async (id: string) => { await request(`/ratings/${encodeURIComponent(id)}`, { method: 'DELETE' }) },
    image: async (name: string) => (await request(`/images/${encodeURIComponent(name)}`)).blob(),
    importBundle: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return json<ImportReport>('/admin/bundles', { method: 'POST', body: form })
    },

    kitchen: () => json<InventoryItem[]>('/kitchen'),
    history: () => json<InventoryEvent[]>('/kitchen/history'),
    applyChanges: (changes: InventoryChange[], kind = 'manual') =>
      send<InventoryItem[]>('/kitchen/changes', 'POST', { changes, kind }),
    removeItem: async (name: string) => {
      await request(`/kitchen/${encodeURIComponent(name)}`, { method: 'DELETE' })
    },
    readKitchen: (text: string, mode: 'note' | 'order', image?: Blob | null) => {
      const form = new FormData()
      form.append('text', text)
      form.append('mode', mode)
      if (image) form.append('image', image, 'photo.jpg')
      return json<Proposal>('/kitchen/read', { method: 'POST', body: form })
    },
    suggestions: (slot: MealSlot) => json<Suggestion[]>(`/suggestions?slot=${slot}&limit=40`),
    coverage: () => json<Record<string, Coverage>>('/coverage'),
    recipeCoverage: (id: string, portions: number) =>
      json<Coverage>(`/recipes/${encodeURIComponent(id)}/coverage?portions=${portions}`),
    deductions: (id: string, portions: number) =>
      json<Deduction[]>(`/recipes/${encodeURIComponent(id)}/deductions?portions=${portions}`),
    cooked: (body: CookedRequest) => send<Rating>('/cooked', 'POST', body),

    upNext: () => json<UpNextItem[]>('/up-next'),
    queue: (recipeId: string, portions?: number) =>
      send<UpNextItem>(`/up-next/${encodeURIComponent(recipeId)}`, 'PUT', portions ? { portions } : {}),
    unqueue: async (recipeId: string) => {
      await request(`/up-next/${encodeURIComponent(recipeId)}`, { method: 'DELETE' })
    },

    favourites: () => json<Favourite[]>('/favourites'),
    favourite: (recipeId: string) => send<Favourite>(`/favourites/${encodeURIComponent(recipeId)}`, 'PUT', {}),
    unfavourite: async (recipeId: string) => {
      await request(`/favourites/${encodeURIComponent(recipeId)}`, { method: 'DELETE' })
    },

    shopping: () => json<ShoppingList>('/shopping'),
    basket: async (name: string, shop: Shop | null) => {
      await request(`/shopping/basket/${encodeURIComponent(name)}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ shop }),
      })
    },
    addExtra: async (name: string) => {
      await request('/shopping/extras', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }),
      })
    },
    removeExtra: async (name: string) => {
      await request(`/shopping/extras/${encodeURIComponent(name)}`, { method: 'DELETE' })
    },
    delivered: () => json<InventoryItem[]>('/shopping/delivered', { method: 'POST' }),
    clearBasket: async () => { await request('/shopping/basket', { method: 'DELETE' }) },

    readRecipeLink: (url: string) => send<RecipeInput>('/recipes/read/link', 'POST', { url }),
    readRecipeText: (text: string) => send<RecipeInput>('/recipes/read/text', 'POST', { text }),
    readRecipePhoto: (image: Blob) => {
      const form = new FormData()
      form.append('image', image, 'recipe.jpg')
      return json<RecipeInput>('/recipes/read/photo', { method: 'POST', body: form })
    },
    uploadPhoto: (image: Blob) => {
      const form = new FormData()
      form.append('image', image, 'photo.jpg')
      return json<{ name: string }>('/images', { method: 'POST', body: form })
    },
    generatePhoto: (name: string, ingredients: string[], signal?: AbortSignal) =>
      json<{ name: string }>('/images/generate', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, ingredients }), signal,
      }),
    createRecipe: (input: RecipeInput) => send<Recipe>('/recipes', 'POST', input),
    updateRecipe: (id: string, input: RecipeInput) =>
      send<Recipe>(`/recipes/${encodeURIComponent(id)}`, 'PUT', input),

    tokens: () => json<McpToken[]>('/tokens'),
    mintToken: (label: string) => send<{ token: McpToken; value: string }>('/tokens', 'POST', { label }),
    revokeToken: async (id: string) => {
      await request(`/tokens/${encodeURIComponent(id)}`, { method: 'DELETE' })
    },
  }
}

export type MealsApi = ReturnType<typeof createMealsApi>
