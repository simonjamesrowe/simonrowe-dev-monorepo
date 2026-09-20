/**
 * Component to show update notification when new version is available
 */

import { useServiceWorker } from '../../lib/pwa/useServiceWorker';

export function UpdateNotification() {
  const { updateAvailable, skipWaiting } = useServiceWorker();

  if (!updateAvailable) {
    return null;
  }

  return (
    <div className="fixed bottom-4 left-4 right-4 z-50 md:left-auto md:right-4 md:w-96">
      <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-lg dark:border-slate-700 dark:bg-slate-800">
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-teal-100 dark:bg-teal-900/30">
            <svg
              className="h-5 w-5 text-teal-600 dark:text-teal-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
              />
            </svg>
          </div>

          <div className="flex-1">
            <h3 className="mb-1 font-semibold text-slate-900 dark:text-white">Update Available</h3>
            <p className="mb-3 text-sm text-slate-600 dark:text-slate-400">
              A new version of CoParent is available. Update now to get the latest features and
              improvements.
            </p>

            <div className="flex gap-2">
              <button
                onClick={skipWaiting}
                className="rounded-lg bg-teal-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-teal-700"
              >
                Update Now
              </button>
              <button
                onClick={() => {}}
                className="rounded-lg bg-slate-100 px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600"
              >
                Later
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
