import { useAuth0 } from '@auth0/auth0-react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type * as ReactRouterDom from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import LoginPage from './LoginPage';

const navigateMock = vi.fn();
const loginWithRedirectMock = vi.fn();

vi.mock('@auth0/auth0-react', () => ({
  useAuth0: vi.fn(),
}));

vi.mock('../lib/auth/clearAuth0Cache', () => ({
  clearAuth0Cache: vi.fn(),
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof ReactRouterDom>('react-router-dom');
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

const mockedUseAuth0 = vi.mocked(useAuth0);

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedUseAuth0.mockReturnValue({
      loginWithRedirect: loginWithRedirectMock,
      isAuthenticated: false,
      isLoading: false,
    } as unknown as ReturnType<typeof useAuth0>);
  });

  it('renders sign in and create account buttons', () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('button', { name: 'Sign In' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create Account' })).toBeInTheDocument();
  });

  it('calls loginWithRedirect on sign in click', async () => {
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'Sign In' }));
    expect(loginWithRedirectMock).toHaveBeenCalledWith();
  });

  it('calls loginWithRedirect with signup hint on create account click', async () => {
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'Create Account' }));
    expect(loginWithRedirectMock).toHaveBeenCalledWith({
      authorizationParams: {
        screen_hint: 'signup',
      },
    });
  });
});
