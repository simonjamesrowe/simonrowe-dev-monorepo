import { Clock } from 'lucide-react';

export interface IdleTimeoutWarningProps {
  isOpen: boolean;
  remainingSeconds: number;
  onStayLoggedIn: () => void;
  onLogout: () => void;
}

export function IdleTimeoutWarning({
  isOpen,
  remainingSeconds,
  onStayLoggedIn,
  onLogout,
}: IdleTimeoutWarningProps) {
  if (!isOpen) return null;

  const clampedSeconds = Math.max(0, remainingSeconds);

  return (
    <>
      <div className="animate-in fade-in fixed inset-0 z-40 bg-slate-900/60 backdrop-blur-sm duration-200" />
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="idle-timeout-title"
          aria-describedby="idle-timeout-description"
          className="animate-in zoom-in-95 slide-in-from-bottom-4 w-full max-w-lg rounded-2xl border border-amber-200/70 bg-white shadow-2xl duration-300 dark:border-amber-500/30 dark:bg-slate-900"
        >
          <div className="relative border-b border-amber-200/70 px-6 pb-5 pt-6 dark:border-amber-500/30">
            <div className="absolute left-6 right-6 top-0 h-1 rounded-full bg-gradient-to-r from-amber-400 via-amber-500 to-teal-400" />
            <div className="flex items-start gap-4">
              <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-amber-500/10 text-amber-600 dark:bg-amber-500/20 dark:text-amber-300">
                <Clock className="h-6 w-6" aria-hidden="true" />
              </div>
              <div>
                <h2
                  id="idle-timeout-title"
                  className="text-xl font-semibold text-slate-900 dark:text-slate-100"
                >
                  Session expiring soon
                </h2>
                <p
                  id="idle-timeout-description"
                  className="mt-1 text-sm text-slate-600 dark:text-slate-400"
                >
                  You have been inactive. You will be logged out automatically unless you stay
                  logged in.
                </p>
              </div>
            </div>
          </div>

          <div className="px-6 py-6">
            <div className="rounded-xl border border-amber-200/80 bg-amber-50 px-4 py-4 text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-100">
              <p className="text-xs font-semibold uppercase tracking-wide text-amber-700 dark:text-amber-200">
                Time remaining
              </p>
              <p className="mt-1 text-3xl font-bold">
                {clampedSeconds} second{clampedSeconds === 1 ? '' : 's'}
              </p>
            </div>

            <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:justify-end">
              <button
                type="button"
                onClick={onLogout}
                className="inline-flex items-center justify-center rounded-xl border border-amber-200 px-5 py-2.5 text-sm font-semibold text-amber-800 transition-colors hover:bg-amber-100 dark:border-amber-500/40 dark:text-amber-200 dark:hover:bg-amber-500/20"
              >
                Log Out Now
              </button>
              <button
                type="button"
                onClick={onStayLoggedIn}
                className="inline-flex items-center justify-center rounded-xl bg-teal-600 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-teal-500/20 transition-all duration-200 hover:-translate-y-0.5 hover:bg-teal-700 hover:shadow-xl hover:shadow-teal-500/30 active:translate-y-0"
              >
                Stay Logged In
              </button>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
