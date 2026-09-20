import { useAuth0 } from '@auth0/auth0-react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi, beforeEach } from 'vitest';

import { ProtectedRoute } from './ProtectedRoute';

vi.mock('@auth0/auth0-react', () => ({
  useAuth0: vi.fn(),
}));

const mockedUseAuth0 = vi.mocked(useAuth0);

describe('ProtectedRoute', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders children when authenticated', () => {
    mockedUseAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
    } as unknown as ReturnType<typeof useAuth0>);

    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <div>Protected content</div>
              </ProtectedRoute>
            }
          />
          <Route path="/login" element={<div>Login page</div>} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });

  it('redirects to login when unauthenticated', () => {
    mockedUseAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
    } as unknown as ReturnType<typeof useAuth0>);

    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <div>Protected content</div>
              </ProtectedRoute>
            }
          />
          <Route path="/login" element={<div>Login page</div>} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText('Login page')).toBeInTheDocument();
  });

  it('shows loading state while auth is resolving', () => {
    mockedUseAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: true,
    } as unknown as ReturnType<typeof useAuth0>);

    render(
      <MemoryRouter>
        <ProtectedRoute>
          <div>Protected content</div>
        </ProtectedRoute>
      </MemoryRouter>,
    );

    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });
});
