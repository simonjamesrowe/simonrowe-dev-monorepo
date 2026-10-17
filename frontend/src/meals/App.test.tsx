import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useAuth0 } from '@auth0/auth0-react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { App, ADMIN_ROLE, MEALS_ROLE, ROLES_CLAIM } from './App'
import { useLibrary, useRatings } from './data'
import { recipe } from './fixtures'

vi.mock('@auth0/auth0-react', () => ({ useAuth0: vi.fn() }))
vi.mock('./data', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./data')>()),
  useLibrary: vi.fn(),
  useRatings: vi.fn(),
}))

const mockedAuth = vi.mocked(useAuth0)

function auth(overrides: Record<string, unknown>) {
  mockedAuth.mockReturnValue({
    isLoading: false,
    isAuthenticated: true,
    error: undefined,
    user: { [ROLES_CLAIM]: [ADMIN_ROLE] },
    loginWithRedirect: vi.fn(),
    logout: vi.fn(),
    getAccessTokenSilently: vi.fn().mockResolvedValue('t'),
    ...overrides,
  } as unknown as ReturnType<typeof useAuth0>)
}

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[path]}><App /></MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('meals app gate', () => {
  beforeEach(() => {
    vi.stubGlobal('scrollTo', vi.fn())
    const chilli = recipe()
    vi.mocked(useLibrary).mockReturnValue({
      library: { recipes: [chilli], byId: new Map([[chilli.id, chilli]]), ingredients: new Map(), templates: [] },
      isLoading: false,
      error: null,
    })
    vi.mocked(useRatings).mockReturnValue({ data: [], error: null } as unknown as ReturnType<typeof useRatings>)
  })

  it('sends a signed-out visitor straight to sign-in, once, remembering where they were going', () => {
    const loginWithRedirect = vi.fn()
    auth({ isAuthenticated: false, user: undefined, loginWithRedirect })
    const { rerender } = renderAt('/recipes')
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/recipes']}><App /></MemoryRouter>
      </QueryClientProvider>,
    )

    expect(loginWithRedirect).toHaveBeenCalledTimes(1)
    expect(loginWithRedirect).toHaveBeenCalledWith({ appState: { returnTo: '/recipes' } })
  })

  it('offers a button instead of looping when sign-in failed', async () => {
    const loginWithRedirect = vi.fn()
    auth({ isAuthenticated: false, user: undefined, error: new Error('access_denied'), loginWithRedirect })
    renderAt('/')

    expect(screen.getByText('Sign-in failed: access_denied')).toBeInTheDocument()
    expect(loginWithRedirect).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(loginWithRedirect).toHaveBeenCalledTimes(1)
  })

  it('tells a signed-in visitor without the role that the app is private', () => {
    auth({ user: { [ROLES_CLAIM]: ['VIEWER'] } })
    renderAt('/')

    expect(screen.getByText('This is a private app.')).toBeInTheDocument()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  })

  it('opens the library for the owner', () => {
    auth({})
    renderAt('/recipes')

    expect(screen.getByRole('heading', { name: 'Recipes' })).toBeInTheDocument()
    expect(screen.getByText('Test Chilli')).toBeInTheDocument()
  })

  it('opens the app for the meals role, without the bundle import', async () => {
    auth({ user: { [ROLES_CLAIM]: [MEALS_ROLE] } })
    renderAt('/more')

    expect(screen.getByRole('navigation')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'More' })).toBeInTheDocument()
    expect(screen.queryByText('Import recipe bundles')).not.toBeInTheDocument()
  })

  it('shows the owner the bundle import', () => {
    auth({})
    renderAt('/more')

    expect(screen.getByText('Import recipe bundles')).toBeInTheDocument()
  })

  it('opens a recipe and scales it', async () => {
    auth({})
    renderAt('/recipes/test-chilli')

    expect(screen.getByRole('heading', { name: 'Test Chilli' })).toBeInTheDocument()
    expect(screen.getByText('500g lean beef mince')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'More portions' }))
    await userEvent.click(screen.getByRole('button', { name: 'More portions' }))
    expect(screen.getByText('750g beef mince')).toBeInTheDocument()
  })

  it('says so when a recipe does not exist', () => {
    auth({})
    renderAt('/recipes/nope')

    expect(screen.getByText('No such recipe')).toBeInTheDocument()
  })
})
