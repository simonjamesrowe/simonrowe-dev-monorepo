import { useAuth0 } from '@auth0/auth0-react';
import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { setTokenGetter } from '../../lib/api/client';

import { useApiClient } from './useApiClient';

vi.mock('@auth0/auth0-react', () => ({
  useAuth0: vi.fn(),
}));

vi.mock('../../lib/api/client', () => ({
  setTokenGetter: vi.fn(),
}));

const mockedUseAuth0 = vi.mocked(useAuth0);
const mockedSetTokenGetter = vi.mocked(setTokenGetter);

describe('useApiClient', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('sets token getter when authenticated', () => {
    const tokenGetter = vi.fn().mockResolvedValue('token-123');

    mockedUseAuth0.mockReturnValue({
      isAuthenticated: true,
      getAccessTokenSilently: tokenGetter,
    } as unknown as ReturnType<typeof useAuth0>);

    const { result } = renderHook(() => useApiClient());

    expect(result.current.isAuthenticated).toBe(true);
    expect(mockedSetTokenGetter).toHaveBeenCalledWith(tokenGetter);
  });

  it('does not set token getter when unauthenticated', () => {
    mockedUseAuth0.mockReturnValue({
      isAuthenticated: false,
      getAccessTokenSilently: vi.fn(),
    } as unknown as ReturnType<typeof useAuth0>);

    const { result } = renderHook(() => useApiClient());

    expect(result.current.isAuthenticated).toBe(false);
    expect(mockedSetTokenGetter).not.toHaveBeenCalled();
  });
});
