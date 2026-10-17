import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { vi } from 'vitest'

import type { MealsApi } from './api'
import { MealsApiContext } from './data'

const DEFAULTS: Record<string, unknown> = {
  image: new Blob(['x']),
  shopping: { items: [], upNextCount: 0 },
  coverage: {},
  recipeCoverage: { ratio: 1, missing: [], shortOf: [], unknown: [], lines: {} },
  rate: {},
  cooked: {},
}

/**
 * A MealsApi whose every call is a mock resolving to an empty answer (an empty list unless a
 * default above says otherwise), with any call overridable.
 */
export function fakeApi(overrides: Partial<MealsApi> = {}): MealsApi {
  const mocks: Record<string, unknown> = { ...overrides }
  return new Proxy(mocks, {
    get(target, key: string) {
      if (!(key in target)) target[key] = vi.fn().mockResolvedValue(key in DEFAULTS ? DEFAULTS[key] : [])
      return target[key]
    },
  }) as unknown as MealsApi
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
