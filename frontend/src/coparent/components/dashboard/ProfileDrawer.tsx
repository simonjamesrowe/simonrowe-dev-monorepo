import { useEffect, useState } from 'react';

import type { ParentProfileUpdate, ProfileDrawerProps } from '../../types/dashboard';

const panelBase =
  'h-full w-full max-w-md border-l border-slate-200/70 bg-white shadow-2xl shadow-slate-900/20 dark:border-slate-800/70 dark:bg-slate-950';

export function ProfileDrawer({
  parent,
  family,
  isOpen,
  onClose,
  onSaveProfile,
}: ProfileDrawerProps) {
  const [formState, setFormState] = useState<ParentProfileUpdate>({
    fullName: parent.fullName,
    email: parent.email,
    phone: parent.phone,
    notificationPreferences: { ...parent.notificationPreferences },
  });

  useEffect(() => {
    setFormState({
      fullName: parent.fullName,
      email: parent.email,
      phone: parent.phone,
      notificationPreferences: { ...parent.notificationPreferences },
    });
  }, [parent]);

  const handleSave = () => {
    onSaveProfile?.(parent.id, formState);
  };

  return (
    <div className={`fixed inset-0 z-40 ${isOpen ? 'pointer-events-auto' : 'pointer-events-none'}`}>
      <button
        type="button"
        aria-label="Close profile settings"
        className={`absolute inset-0 bg-slate-950/30 transition ${isOpen ? 'opacity-100' : 'opacity-0'}`}
        onClick={onClose}
      />
      <aside
        className={`absolute right-0 top-0 flex ${panelBase} flex-col transition ${isOpen ? 'translate-x-0' : 'translate-x-full'}`}
      >
        <div className="flex items-start justify-between border-b border-slate-200/70 px-6 py-5 dark:border-slate-800/70">
          <div>
            <p className="text-xs uppercase tracking-[0.3em] text-slate-400">Profile settings</p>
            <h2 className="mt-2 text-lg font-semibold text-slate-900 dark:text-white">
              {parent.fullName}
            </h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">{family.name}</p>
          </div>
          <button
            onClick={onClose}
            className="rounded-full border border-slate-200/70 px-3 py-1 text-xs font-medium text-slate-600 hover:border-slate-300 hover:text-slate-900 dark:border-slate-700 dark:text-slate-300"
          >
            Close
          </button>
        </div>

        <div className="flex-1 space-y-6 overflow-y-auto px-6 py-5">
          <div className="space-y-3">
            <label htmlFor="profile-name" className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">
              Name
            </label>
            <input
              id="profile-name"
              value={formState.fullName}
              onChange={(event) =>
                setFormState((prev) => ({ ...prev, fullName: event.target.value }))
              }
              className="w-full rounded-2xl border border-slate-200/80 bg-slate-50 px-4 py-3 text-sm text-slate-900 focus:border-teal-400 focus:outline-none dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100"
            />
          </div>

          {/* Only the name is stored. Email always comes from the sign-in provider, and phone
              and notification settings have no storage behind them, so offering them here
              would accept changes and silently discard them. */}
          <div className="space-y-3">
            <label htmlFor="profile-email" className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">
              Email
            </label>
            <input
              id="profile-email"
              value={formState.email}
              readOnly
              aria-describedby="profile-email-hint"
              className="w-full rounded-2xl border border-slate-200/80 bg-slate-100 px-4 py-3 text-sm text-slate-500 focus:outline-none dark:border-slate-800 dark:bg-slate-800 dark:text-slate-400"
            />
            <p id="profile-email-hint" className="text-xs text-slate-500 dark:text-slate-400">
              Your email comes from the account you sign in with.
            </p>
          </div>
        </div>

        <div className="border-t border-slate-200/70 px-6 py-5 dark:border-slate-800/70">
          <button
            onClick={handleSave}
            className="w-full rounded-full bg-teal-600 px-4 py-3 text-sm font-semibold text-white shadow-lg shadow-teal-500/30 transition hover:-translate-y-0.5 hover:bg-teal-700 dark:bg-teal-500 dark:text-slate-950"
          >
            Save changes
          </button>
        </div>
      </aside>
    </div>
  );
}
