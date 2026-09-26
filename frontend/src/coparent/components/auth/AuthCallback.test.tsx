import { useAuth0 } from '@auth0/auth0-react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useCurrentUser } from '../../hooks/api/useParents';

import { AuthCallback } from './AuthCallback';

vi.mock('@auth0/auth0-react', () => ({
  useAuth0: vi.fn(),
}));

vi.mock('../../hooks/api/useParents', () => ({
  useCurrentUser: vi.fn(),
}));

const mockedUseAuth0 = vi.mocked(useAuth0);
const mockedUseCurrentUser = vi.mocked(useCurrentUser);

describe('AuthCallback', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedUseAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      error: undefined,
    } as unknown as ReturnType<typeof useAuth0>);
  });

  it('shows a recoverable error when the current-user request fails', async () => {
    const user = userEvent.setup();
    const refetch = vi.fn();
    mockedUseCurrentUser.mockReturnValue({
      data: undefined,
      isLoading: false,
      isFetching: false,
      isError: true,
      error: new Error('Request failed with status code 401'),
      refetch,
    } as unknown as ReturnType<typeof useCurrentUser>);

    render(
      <MemoryRouter initialEntries={['/auth/callback']}>
        <AuthCallback />
      </MemoryRouter>,
    );

    expect(
      await screen.findByRole('heading', { name: "We couldn't finish signing you in" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/couldn't load your CoParent profile/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(refetch).toHaveBeenCalledOnce();
  });

  it('returns to login after an Auth0 callback error', async () => {
    const user = userEvent.setup();
    mockedUseAuth0.mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      error: new Error('Invalid state'),
    } as unknown as ReturnType<typeof useAuth0>);
    mockedUseCurrentUser.mockReturnValue({
      data: undefined,
      isLoading: false,
      isFetching: false,
      isError: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useCurrentUser>);

    render(
      <MemoryRouter initialEntries={['/auth/callback']}>
        <Routes>
          <Route path="/auth/callback" element={<AuthCallback />} />
          <Route path="/login" element={<p>Login page</p>} />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(await screen.findByRole('button', { name: 'Return to Login' }));
    expect(screen.getByText('Login page')).toBeInTheDocument();
  });
});
