import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { vi } from 'vitest'

import type { MealsApi } from './api'
import { MealsApiContext } from './data'

/** A MealsApi whose every call is a mock, for components that talk to the API. */
export function fakeApi(overrides: Partial<MealsApi> = {}): MealsApi {
  return {
    recipes: vi.fn().mockResolvedValue([]),
    ingredients: vi.fn().mockResolvedValue([]),
    templates: vi.fn().mockResolvedValue([]),
    ratings: vi.fn().mockResolvedValue([]),
    rate: vi.fn().mockResolvedValue({}),
    deleteRating: vi.fn().mockResolvedValue(undefined),
    image: vi.fn().mockResolvedValue(new Blob(['x'])),
    importBundle: vi.fn(),
    ...overrides,
  }
}

export function withApi(api: MealsApi, children: ReactNode, path = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return (
    <QueryClientProvider client={client}>
      <MealsApiContext.Provider value={api}>
        <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
      </MealsApiContext.Provider>
    </QueryClientProvider>
  )
}
