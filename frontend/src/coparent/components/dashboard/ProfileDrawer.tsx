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

  const updateNotification = (key: keyof ParentProfileUpdate['notificationPreferences']) => {
    setFormState((prev) => ({
      ...prev,
      notificationPreferences: {
        ...prev.notificationPreferences,
        [key]: !prev.notificationPreferences[key],
      },
    }));
  };

  const handleSave = () => {
    onSaveProfile?.(parent.id, formState);
  };

  return (
    <div className={`fixed inset-0 z-40 ${isOpen ? 'pointer-events-auto' : 'pointer-events-none'}`}>
      <div
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
            <label className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">
              Name
            </label>
            <input
              value={formState.fullName}
              onChange={(event) =>
                setFormState((prev) => ({ ...prev, fullName: event.target.value }))
              }
              className="w-full rounded-2xl border border-slate-200/80 bg-slate-50 px-4 py-3 text-sm text-slate-900 focus:border-teal-400 focus:outline-none dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100"
            />
          </div>

          <div className="space-y-3">
            <label className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">
              Email
            </label>
            <input
              value={formState.email}
              onChange={(event) => setFormState((prev) => ({ ...prev, email: event.target.value }))}
              className="w-full rounded-2xl border border-slate-200/80 bg-slate-50 px-4 py-3 text-sm text-slate-900 focus:border-teal-400 focus:outline-none dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100"
            />
          </div>

          <div className="space-y-3">
            <label className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500">
              Phone
            </label>
            <input
              value={formState.phone}
              onChange={(event) => setFormState((prev) => ({ ...prev, phone: event.target.value }))}
              className="w-full rounded-2xl border border-slate-200/80 bg-slate-50 px-4 py-3 text-sm text-slate-900 focus:border-teal-400 focus:outline-none dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100"
            />
          </div>

          <div className="space-y-3 rounded-3xl border border-slate-200/70 bg-white/80 p-4 dark:border-slate-800/70 dark:bg-slate-900/60">
            <div>
              <p className="text-sm font-semibold text-slate-900 dark:text-white">Notifications</p>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Choose how you want to stay updated.
              </p>
            </div>
            <div className="space-y-2">
              {(['email', 'sms', 'push'] as const).map((channel) => (
                <button
                  key={channel}
                  onClick={() => updateNotification(channel)}
                  className="flex w-full items-center justify-between rounded-2xl border border-slate-200/70 bg-white px-4 py-3 text-sm text-slate-700 transition hover:border-teal-200 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-200"
                >
                  <span className="capitalize">{channel}</span>
                  <span
                    className={`rounded-full px-3 py-1 text-xs font-medium ${
                      formState.notificationPreferences[channel]
                        ? 'bg-teal-50 text-teal-700 dark:bg-teal-900/40 dark:text-teal-200'
                        : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300'
                    }`}
                  >
                    {formState.notificationPreferences[channel] ? 'On' : 'Off'}
                  </span>
                </button>
              ))}
            </div>
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
