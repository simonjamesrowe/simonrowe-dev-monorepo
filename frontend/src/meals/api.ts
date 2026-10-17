import type { ImportReport, Ingredient, NewRating, Rating, Recipe, Template } from './types'

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
    templates: () => json<Template[]>('/templates'),
    ratings: () => json<Rating[]>('/ratings'),
    rate: (rating: NewRating) => send<Rating>('/ratings', 'POST', rating),
    deleteRating: async (id: string) => { await request(`/ratings/${encodeURIComponent(id)}`, { method: 'DELETE' }) },
    image: async (name: string) => (await request(`/images/${encodeURIComponent(name)}`)).blob(),
    importBundle: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return json<ImportReport>('/admin/bundles', { method: 'POST', body: form })
    },
  }
}

export type MealsApi = ReturnType<typeof createMealsApi>
