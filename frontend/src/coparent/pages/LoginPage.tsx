import { useAuth0 } from '@auth0/auth0-react';
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

import { clearAuth0Cache } from '../lib/auth/clearAuth0Cache';

const LoginPage = () => {
  const { loginWithRedirect, isAuthenticated, isLoading } = useAuth0();
  const navigate = useNavigate();

  useEffect(() => {
    if (!isLoading && isAuthenticated) {
      navigate('/dashboard', { replace: true });
    }
  }, [isAuthenticated, isLoading, navigate]);

  const handleLogin = () => {
    loginWithRedirect();
  };

  const handleSignup = () => {
    loginWithRedirect({
      authorizationParams: {
        screen_hint: 'signup',
      },
    });
  };

  const handleClearLoginCache = () => {
    clearAuth0Cache();
    window.location.reload();
  };

  if (isLoading) {
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
          <p className="text-slate-500 dark:text-slate-400">Loading...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-white to-teal-50/30 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
      {/* Decorative background pattern */}
      <div
        className="pointer-events-none fixed inset-0 opacity-[0.015] dark:opacity-[0.025]"
        style={{
          backgroundImage: 'radial-gradient(circle at 2px 2px, currentColor 1px, transparent 0)',
          backgroundSize: '32px 32px',
        }}
      />

      {/* Floating decorative shapes */}
      <div className="pointer-events-none fixed right-10 top-20 h-64 w-64 rounded-full bg-teal-400/10 blur-3xl dark:bg-teal-500/5" />
      <div className="pointer-events-none fixed bottom-20 left-10 h-96 w-96 rounded-full bg-rose-400/10 blur-3xl dark:bg-rose-500/5" />

      <div className="relative flex min-h-screen flex-col items-center justify-center px-4">
        <div className="w-full max-w-md">
          {/* Logo/Header */}
          <div className="mb-10 text-center">
            <div className="mb-6 inline-flex h-20 w-20 items-center justify-center rounded-3xl bg-gradient-to-br from-teal-500 to-teal-600 shadow-2xl shadow-teal-500/30">
              <svg
                className="h-10 w-10 text-white"
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
            <h1 className="text-4xl font-bold tracking-tight text-slate-900 dark:text-white">
              CoParent
            </h1>
            <p className="mt-3 text-lg text-slate-500 dark:text-slate-400">
              Co-parenting made simpler, together
            </p>
          </div>

          {/* Login Card */}
          <div className="rounded-3xl border border-slate-200/60 bg-white/80 p-8 shadow-2xl shadow-slate-200/40 backdrop-blur-xl sm:p-10 dark:border-slate-700/60 dark:bg-slate-900/60 dark:shadow-slate-950/50">
            <div className="space-y-4">
              <button
                onClick={handleLogin}
                className="inline-flex w-full items-center justify-center gap-3 rounded-xl bg-teal-600 px-6 py-4 text-lg font-semibold text-white shadow-lg shadow-teal-500/25 transition-all duration-200 hover:-translate-y-0.5 hover:bg-teal-700 hover:shadow-xl hover:shadow-teal-500/30 active:translate-y-0"
              >
                <svg
                  className="h-5 w-5"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M15.75 9V5.25A2.25 2.25 0 0 0 13.5 3h-6a2.25 2.25 0 0 0-2.25 2.25v13.5A2.25 2.25 0 0 0 7.5 21h6a2.25 2.25 0 0 0 2.25-2.25V15m3 0 3-3m0 0-3-3m3 3H9"
                  />
                </svg>
                Sign In
              </button>

              <button
                type="button"
                onClick={handleClearLoginCache}
                className="w-full rounded-xl border border-slate-200/60 bg-white/70 px-6 py-3 text-sm font-semibold text-slate-700 shadow-sm transition hover:border-slate-300 hover:text-slate-900 dark:border-slate-700/60 dark:bg-slate-900/40 dark:text-slate-200"
              >
                Clear login cache
              </button>

              <div className="relative">
                <div className="absolute inset-0 flex items-center">
                  <div className="w-full border-t border-slate-200 dark:border-slate-700" />
                </div>
                <div className="relative flex justify-center text-sm">
                  <span className="bg-white px-4 text-slate-500 dark:bg-slate-900 dark:text-slate-400">
                    or
                  </span>
                </div>
              </div>

              <button
                onClick={handleSignup}
                className="inline-flex w-full items-center justify-center gap-3 rounded-xl border-2 border-slate-200 bg-white px-6 py-4 text-lg font-semibold text-slate-900 transition-all duration-200 hover:-translate-y-0.5 hover:bg-slate-50 active:translate-y-0 dark:border-slate-600 dark:bg-slate-800 dark:text-white dark:hover:bg-slate-700"
              >
                <svg
                  className="h-5 w-5"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M18 7.5v3m0 0v3m0-3h3m-3 0h-3m-2.25-4.125a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0ZM3 19.235v-.11a6.375 6.375 0 0 1 12.75 0v.109A12.318 12.318 0 0 1 9.374 21c-2.331 0-4.512-.645-6.374-1.766Z"
                  />
                </svg>
                Create Account
              </button>
            </div>

            <div className="mt-8 border-t border-slate-200/60 pt-6 dark:border-slate-700/60">
              <p className="text-center text-sm text-slate-500 dark:text-slate-400">
                By continuing, you agree to our{' '}
                <a href="/terms" className="text-teal-600 hover:underline dark:text-teal-400">
                  Terms of Service
                </a>{' '}
                and{' '}
                <a href="/privacy" className="text-teal-600 hover:underline dark:text-teal-400">
                  Privacy Policy
                </a>
              </p>
            </div>
          </div>

          {/* Features List */}
          <div className="mt-12 grid grid-cols-3 gap-4 text-center">
            <div className="rounded-2xl bg-white/50 p-4 backdrop-blur-sm dark:bg-slate-800/30">
              <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-xl bg-teal-100 dark:bg-teal-900/30">
                <svg
                  className="h-5 w-5 text-teal-600 dark:text-teal-400"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={1.5}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M6.75 3v2.25M17.25 3v2.25M3 18.75V7.5a2.25 2.25 0 0 1 2.25-2.25h13.5A2.25 2.25 0 0 1 21 7.5v11.25m-18 0A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75m-18 0v-7.5A2.25 2.25 0 0 1 5.25 9h13.5A2.25 2.25 0 0 1 21 11.25v7.5"
                  />
                </svg>
              </div>
              <p className="text-xs font-medium text-slate-700 dark:text-slate-300">
                Shared Calendars
              </p>
            </div>
            <div className="rounded-2xl bg-white/50 p-4 backdrop-blur-sm dark:bg-slate-800/30">
              <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-xl bg-violet-100 dark:bg-violet-900/30">
                <svg
                  className="h-5 w-5 text-violet-600 dark:text-violet-400"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={1.5}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M8.625 12a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0H8.25m4.125 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0H12m4.125 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 0 1-2.555-.337A5.972 5.972 0 0 1 5.41 20.97a5.969 5.969 0 0 1-.474-.065 4.48 4.48 0 0 0 .978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25Z"
                  />
                </svg>
              </div>
              <p className="text-xs font-medium text-slate-700 dark:text-slate-300">Messaging</p>
            </div>
            <div className="rounded-2xl bg-white/50 p-4 backdrop-blur-sm dark:bg-slate-800/30">
              <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-xl bg-amber-100 dark:bg-amber-900/30">
                <svg
                  className="h-5 w-5 text-amber-600 dark:text-amber-400"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={1.5}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M2.25 18.75a60.07 60.07 0 0 1 15.797 2.101c.727.198 1.453-.342 1.453-1.096V18.75M3.75 4.5v.75A.75.75 0 0 1 3 6h-.75m0 0v-.375c0-.621.504-1.125 1.125-1.125H20.25M2.25 6v9m18-10.5v.75c0 .414.336.75.75.75h.75m-1.5-1.5h.375c.621 0 1.125.504 1.125 1.125v9.75c0 .621-.504 1.125-1.125 1.125h-.375m1.5-1.5H21a.75.75 0 0 0-.75.75v.75m0 0H3.75m0 0h-.375a1.125 1.125 0 0 1-1.125-1.125V15m1.5 1.5v-.75A.75.75 0 0 0 3 15h-.75M15 10.5a3 3 0 1 1-6 0 3 3 0 0 1 6 0Zm3 0h.008v.008H18V10.5Zm-12 0h.008v.008H6V10.5Z"
                  />
                </svg>
              </div>
              <p className="text-xs font-medium text-slate-700 dark:text-slate-300">
                Expense Tracking
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
