import { createContext, useCallback, useContext, useMemo, useState } from 'react';

type ToastVariant = 'success' | 'error' | 'info';

export type ToastMessage = {
  id: string;
  title: string;
  description?: string;
  variant: ToastVariant;
};

type ToastContextValue = {
  showToast: (toast: Omit<ToastMessage, 'id'>) => void;
};

const ToastContext = createContext<ToastContextValue | null>(null);

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return ctx;
}

const toneStyles: Record<
  ToastVariant,
  { border: string; bg: string; iconBg: string; icon: string }
> = {
  success: {
    border: 'border-teal-200/70 dark:border-teal-900/60',
    bg: 'bg-white dark:bg-slate-900',
    iconBg: 'bg-teal-100 dark:bg-teal-900/30',
    icon: 'text-teal-600 dark:text-teal-400',
  },
  error: {
    border: 'border-rose-200/70 dark:border-rose-900/60',
    bg: 'bg-white dark:bg-slate-900',
    iconBg: 'bg-rose-100 dark:bg-rose-900/30',
    icon: 'text-rose-600 dark:text-rose-400',
  },
  info: {
    border: 'border-slate-200/70 dark:border-slate-700/60',
    bg: 'bg-white dark:bg-slate-900',
    iconBg: 'bg-slate-100 dark:bg-slate-800',
    icon: 'text-slate-600 dark:text-slate-300',
  },
};

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const showToast = useCallback((toast: Omit<ToastMessage, 'id'>) => {
    const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    setToasts((prev) => [{ id, ...toast }, ...prev].slice(0, 3));

    window.setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 3500);
  }, []);

  const value = useMemo(() => ({ showToast }), [showToast]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="fixed bottom-4 left-4 right-4 z-50 flex flex-col gap-2 md:left-auto md:right-4 md:w-96">
        {toasts.map((toast) => {
          const tone = toneStyles[toast.variant];
          return (
            <div
              key={toast.id}
              className={`rounded-lg border ${tone.border} ${tone.bg} p-4 shadow-lg`}
              role="status"
              aria-live="polite"
            >
              <div className="flex items-start gap-3">
                <div
                  className={`flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full ${tone.iconBg}`}
                >
                  <svg
                    className={`h-5 w-5 ${tone.icon}`}
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    {toast.variant === 'success' ? (
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z"
                      />
                    ) : toast.variant === 'error' ? (
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z"
                      />
                    ) : (
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M11.25 11.25 12 11.25m0 0 .75 0M12 11.25V16.5m0-9A9 9 0 1 0 21 12a9 9 0 0 0-9-9Z"
                      />
                    )}
                  </svg>
                </div>

                <div className="min-w-0 flex-1">
                  <p className="font-semibold text-slate-900 dark:text-white">{toast.title}</p>
                  {toast.description && (
                    <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
                      {toast.description}
                    </p>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}
