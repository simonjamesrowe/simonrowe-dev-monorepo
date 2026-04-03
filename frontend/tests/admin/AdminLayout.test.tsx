import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { AdminLayout } from '../../src/components/admin/AdminLayout'

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import { useAuth } from '../../src/auth/useAuth'

const mockUseAuth = vi.mocked(useAuth)

describe('AdminLayout', () => {
  beforeEach(() => {
    mockUseAuth.mockReset()
  })

  it('shows loading state when isLoading is true', () => {
    mockUseAuth.mockReturnValue({
      isAuthenticated: false,
      isLoading: true,
      user: undefined,
      login: vi.fn(),
      logout: vi.fn(),
      getAccessToken: vi.fn(),
    })

    render(
      <MemoryRouter initialEntries={['/admin']}>
        <AdminLayout />
      </MemoryRouter>,
    )

    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })

  it('redirects to login when not authenticated', () => {
    const loginFn = vi.fn()
    mockUseAuth.mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: undefined,
      login: loginFn,
      logout: vi.fn(),
      getAccessToken: vi.fn(),
    })

    render(
      <MemoryRouter initialEntries={['/admin']}>
        <AdminLayout />
      </MemoryRouter>,
    )

    expect(loginFn).toHaveBeenCalled()
    expect(screen.getByText('Redirecting to login...')).toBeInTheDocument()
  })

  it('shows sidebar navigation when authenticated', () => {
    mockUseAuth.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: { email: 'admin@example.com' },
      login: vi.fn(),
      logout: vi.fn(),
      getAccessToken: vi.fn(),
    })

    render(
      <MemoryRouter initialEntries={['/admin']}>
        <AdminLayout />
      </MemoryRouter>,
    )

    expect(screen.getByRole('navigation')).toBeInTheDocument()
  })

  it('shows System Admin header when authenticated', () => {
    mockUseAuth.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: { email: 'admin@example.com' },
      login: vi.fn(),
      logout: vi.fn(),
      getAccessToken: vi.fn(),
    })

    render(
      <MemoryRouter initialEntries={['/admin']}>
        <AdminLayout />
      </MemoryRouter>,
    )

    expect(screen.getByText('System Admin')).toBeInTheDocument()
  })

  it('renders all nav links when authenticated', () => {
    mockUseAuth.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: { email: 'admin@example.com' },
      login: vi.fn(),
      logout: vi.fn(),
      getAccessToken: vi.fn(),
    })

    render(
      <MemoryRouter initialEntries={['/admin']}>
        <AdminLayout />
      </MemoryRouter>,
    )

    const expectedLabels = [
      'Dashboard',
      'Content',
      'Profile',
      'Skills',
      'Jobs',
      'Tags',
      'Media',
    ]

    expectedLabels.forEach((label) => {
      expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
    })
  })
})
