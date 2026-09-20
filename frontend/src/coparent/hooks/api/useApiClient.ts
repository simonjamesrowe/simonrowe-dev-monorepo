import { useAuth0 } from '@auth0/auth0-react';
import { useEffect } from 'react';

import { setTokenGetter } from '../../lib/api/client';

export function useApiClient() {
  const { getAccessTokenSilently, isAuthenticated } = useAuth0();

  useEffect(() => {
    if (isAuthenticated) {
      setTokenGetter(getAccessTokenSilently);
    }
  }, [isAuthenticated, getAccessTokenSilently]);

  return { isAuthenticated };
}
