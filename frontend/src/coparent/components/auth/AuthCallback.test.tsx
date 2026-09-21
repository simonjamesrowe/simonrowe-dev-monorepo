import { useAuth0 } from '@auth0/auth0-react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
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
const refetchMock = vi.fn();

describe('AuthCallback', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedUseAuth0.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      error: undefined,
    } as unknown as ReturnType<typeof useAuth0>);
  });

  it('shows a recoverable error when the profile request fails', async () => {
    mockedUseCurrentUser.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('Request failed'),
      refetch: refetchMock,
    } as unknown as ReturnType<typeof useCurrentUser>);

    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/auth/callback']}>
        <AuthCallback />
      </MemoryRouter>,
    );

    expect(
      await screen.findByText("We couldn't load your CoParent profile."),
    ).toBeInTheDocument();
    expect(screen.queryByText('Completing authentication...')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(refetchMock).toHaveBeenCalledOnce();
  });
});
