import { useAuth0 } from '@auth0/auth0-react';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { useAcceptInvitation } from '../hooks/api/useInvitations';

const AcceptInvitePage = () => {
  const [token] = useState(() => {
    const fragmentToken = new URLSearchParams(window.location.hash.slice(1)).get('token');
    if (fragmentToken) {
      window.history.replaceState(null, '', window.location.pathname);
    }
    return fragmentToken;
  });
  const navigate = useNavigate();
  const { isAuthenticated, isLoading, loginWithRedirect } = useAuth0();
  const acceptInvitation = useAcceptInvitation();
  const [acceptanceState, setAcceptanceState] = useState<
    'idle' | 'accepting' | 'success' | 'error'
  >('idle');
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [familyName, setFamilyName] = useState<string>('');

  useEffect(() => {
    if (!token) {
      setAcceptanceState('error');
      setErrorMessage('No invitation token provided');
      return;
    }

    if (isLoading) {
      return;
    }

    if (!isAuthenticated) {
      loginWithRedirect({
        appState: {
          returnTo: `/invitations/accept#token=${encodeURIComponent(token)}`,
        },
      });
      return;
    }

    // User is authenticated, accept the invitation
    if (acceptanceState === 'idle') {
      setAcceptanceState('accepting');
      acceptInvitation.mutate(token, {
        onSuccess: (data) => {
          setAcceptanceState('success');
          setFamilyName(data.family.name);
        },
        onError: (error) => {
          setAcceptanceState('error');
          const message = error instanceof Error ? error.message : 'Failed to accept invitation';
          // Parse API error response if available
          if (error && typeof error === 'object' && 'response' in error) {
            const response = (error as { response?: { data?: { message?: string } } }).response;
            if (response?.data?.message) {
              setErrorMessage(response.data.message);
              return;
            }
          }
          setErrorMessage(message);
        },
      });
    }
  }, [token, isAuthenticated, isLoading, loginWithRedirect, acceptInvitation, acceptanceState]);

  const handleGoToDashboard = () => {
    navigate('/dashboard', { replace: true });
  };

  const handleGoToLogin = () => {
    navigate('/login', { replace: true });
  };

  // Loading state
  if (isLoading || acceptanceState === 'idle' || acceptanceState === 'accepting') {
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
                d="M21.75 6.75v10.5a2.25 2.25 0 0 1-2.25 2.25h-15a2.25 2.25 0 0 1-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25m19.5 0v.243a2.25 2.25 0 0 1-1.07 1.916l-7.5 4.615a2.25 2.25 0 0 1-2.36 0L3.32 8.91a2.25 2.25 0 0 1-1.07-1.916V6.75"
              />
            </svg>
          </div>
          <p className="text-slate-500 dark:text-slate-400">
            {acceptanceState === 'accepting' ? 'Accepting invitation...' : 'Loading...'}
          </p>
        </div>
      </div>
    );
  }

  // Success state
  if (acceptanceState === 'success') {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 via-white to-teal-50/30 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
        <div className="mx-auto max-w-md p-8 text-center">
          <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-teal-100 dark:bg-teal-900/20">
            <svg
              className="h-8 w-8 text-teal-600 dark:text-teal-400"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={1.5}
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="m4.5 12.75 6 6 9-13.5" />
            </svg>
          </div>
          <h2 className="mb-2 text-xl font-bold text-slate-900 dark:text-white">
            Invitation Accepted!
          </h2>
          <p className="mb-6 text-slate-500 dark:text-slate-400">
            You have successfully joined{' '}
            <span className="font-semibold text-slate-700 dark:text-slate-200">{familyName}</span>.
          </p>
          <button
            onClick={handleGoToDashboard}
            className="inline-flex items-center justify-center gap-2 rounded-xl bg-teal-600 px-6 py-3 font-semibold text-white shadow-lg shadow-teal-500/25 transition-all hover:bg-teal-700"
          >
            Go to Dashboard
          </button>
        </div>
      </div>
    );
  }

  // Error state
  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 via-white to-teal-50/30 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
      <div className="mx-auto max-w-md p-8 text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-rose-100 dark:bg-rose-900/20">
          <svg
            className="h-8 w-8 text-rose-600 dark:text-rose-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z"
            />
          </svg>
        </div>
        <h2 className="mb-2 text-xl font-bold text-slate-900 dark:text-white">
          Unable to Accept Invitation
        </h2>
        <p className="mb-6 text-slate-500 dark:text-slate-400">{errorMessage}</p>
        <div className="flex flex-col justify-center gap-3 sm:flex-row">
          <button
            onClick={handleGoToLogin}
            className="inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-6 py-3 font-semibold text-slate-700 shadow-sm transition-all hover:border-slate-300 hover:text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200"
          >
            Return to Login
          </button>
          {isAuthenticated && (
            <button
              onClick={handleGoToDashboard}
              className="inline-flex items-center justify-center gap-2 rounded-xl bg-teal-600 px-6 py-3 font-semibold text-white shadow-lg shadow-teal-500/25 transition-all hover:bg-teal-700"
            >
              Go to Dashboard
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default AcceptInvitePage;
