import { useAuth0 } from '@auth0/auth0-react';
import { useEffect, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';

import { useCurrentUser } from '../../hooks/api/useParents';
import { clearAuth0Cache } from '../../lib/auth/clearAuth0Cache';

export function AuthCallback() {
  const { isAuthenticated, isLoading, error } = useAuth0();
  const navigate = useNavigate();
  const location = useLocation();
  const [shouldFetchUser, setShouldFetchUser] = useState(false);

  // Only fetch user data after authentication is confirmed
  const {
    data: currentUser,
    isLoading: isLoadingUser,
    isFetching: isFetchingUser,
    isError: isUserError,
    refetch: refetchUser,
  } = useCurrentUser(shouldFetchUser);

  useEffect(() => {
    if (!isLoading) {
      if (error) {
        console.error('Auth0 callback error:', error);
      } else if (isAuthenticated) {
        // Trigger user data fetch
        setShouldFetchUser(true);
      }
    }
  }, [isAuthenticated, isLoading, error]);

  useEffect(() => {
    // Route user once we have their data
    if (shouldFetchUser && !isLoadingUser && currentUser) {
      const state = location.state as { from?: { pathname: string } } | null;
      const returnTo = state?.from?.pathname;

      // Check if user has accepted an invitation (has a family but no profile was created during onboarding)
      const hasAcceptedInvitation =
        currentUser.profiles.length > 0 &&
        currentUser.profiles.some((p) => p.familyId && p.role === 'co-parent');

      if (currentUser.isNewUser && !hasAcceptedInvitation) {
        // New user creating their own family - send to onboarding
        navigate('/onboarding', { replace: true });
      } else {
        // Existing user or invited user who accepted - send to dashboard
        navigate(returnTo || '/dashboard', { replace: true });
      }
    }
  }, [shouldFetchUser, isLoadingUser, currentUser, navigate, location.state]);

  if (error) {
    return (
      <CallbackError
        title="Authentication Error"
        message={error.message || 'An error occurred during authentication'}
        secondaryLabel="Return to Login"
        onSecondary={() => navigate('/login', { replace: true })}
      />
    );
  }

  if (shouldFetchUser && isUserError) {
    return (
      <CallbackError
        title="We couldn't finish signing you in"
        message="Your login succeeded, but we couldn't load your CoParent profile. You can retry without signing in again."
        primaryLabel={isFetchingUser ? 'Trying again...' : 'Try again'}
        primaryDisabled={isFetchingUser}
        onPrimary={() => void refetchUser()}
        secondaryLabel="Start a fresh login"
        onSecondary={() => {
          clearAuth0Cache();
          window.location.assign('/login');
        }}
      />
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 via-white to-teal-50/30 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
      <div className="text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 animate-pulse items-center justify-center rounded-2xl bg-gradient-to-br from-teal-500 to-teal-600 shadow-xl shadow-teal-500/30">
          <svg
            className="h-8 w-8 text-white"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Zm8.25 2.25a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
            />
          </svg>
        </div>
        <p className="text-slate-500 dark:text-slate-400">Completing authentication...</p>
      </div>
    </div>
  );
}

interface CallbackErrorProps {
  title: string;
  message: string;
  primaryLabel?: string;
  primaryDisabled?: boolean;
  onPrimary?: () => void;
  secondaryLabel: string;
  onSecondary: () => void;
}

function CallbackError({
  title,
  message,
  primaryLabel,
  primaryDisabled = false,
  onPrimary,
  secondaryLabel,
  onSecondary,
}: CallbackErrorProps) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 via-white to-teal-50/30 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
      <div className="mx-auto max-w-md p-8 text-center" role="alert">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-rose-100 dark:bg-rose-900/20">
          <svg
            className="h-8 w-8 text-rose-600 dark:text-rose-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
            aria-hidden="true"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z"
            />
          </svg>
        </div>
        <h2 className="mb-2 text-xl font-bold text-slate-900 dark:text-white">{title}</h2>
        <p className="mb-6 text-slate-500 dark:text-slate-400">{message}</p>
        <div className="flex flex-col justify-center gap-3 sm:flex-row">
          {primaryLabel && onPrimary && (
            <button
              type="button"
              onClick={onPrimary}
              disabled={primaryDisabled}
              className="inline-flex items-center justify-center gap-2 rounded-xl bg-teal-600 px-6 py-3 font-semibold text-white shadow-lg shadow-teal-500/25 transition-all hover:bg-teal-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {primaryLabel}
            </button>
          )}
          <button
            type="button"
            onClick={onSecondary}
            className="inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-6 py-3 font-semibold text-slate-700 shadow-sm transition-all hover:border-slate-300 hover:text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200"
          >
            {secondaryLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
