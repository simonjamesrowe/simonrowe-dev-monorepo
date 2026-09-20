/**
 * Component to show offline status indicator
 */

import { useOnlineStatus } from '../../lib/pwa/useOnlineStatus';

export function OfflineIndicator() {
  const isOnline = useOnlineStatus();

  if (isOnline) {
    return null;
  }

  return (
    <div className="fixed left-4 right-4 top-16 z-40 md:left-auto md:right-4 md:w-80 lg:top-4">
      <div className="rounded-lg border border-rose-200 bg-rose-50 p-3 dark:border-rose-800 dark:bg-rose-900/20">
        <div className="flex items-center gap-2">
          <div className="flex-shrink-0">
            <svg
              className="h-5 w-5 text-rose-600 dark:text-rose-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M18.364 5.636a9 9 0 010 12.728m0 0l-2.829-2.829m2.829 2.829L21 21M15.536 8.464a5 5 0 010 7.072m0 0l-2.829-2.829m-4.243 2.829a4.978 4.978 0 01-1.414-2.83m-1.414 5.658a9 9 0 01-2.167-9.238m7.824 2.167a1 1 0 111.414 1.414m-1.414-1.414L3 3m8.293 8.293l1.414 1.414"
              />
            </svg>
          </div>
          <div className="flex-1">
            <p className="text-sm font-medium text-rose-900 dark:text-rose-200">You're offline</p>
            <p className="mt-0.5 text-xs text-rose-700 dark:text-rose-300">
              Some features may be limited
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
