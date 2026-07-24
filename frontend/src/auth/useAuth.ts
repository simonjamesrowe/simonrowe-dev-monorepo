import { useAuth0 } from '@auth0/auth0-react'

export function useAuth() {
  const {
    isAuthenticated,
    isLoading,
    user,
    loginWithRedirect,
    loginWithPopup,
    logout,
    getAccessTokenSilently,
  } = useAuth0()

  return {
    isAuthenticated,
    isLoading,
    user,
    login: () => loginWithRedirect(),
    loginWithPopup: () => loginWithPopup(),
    logout: () => logout({ logoutParams: { returnTo: window.location.origin } }),
    getAccessToken: getAccessTokenSilently,
  }
}
