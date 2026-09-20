'use client';

import { LogOut } from 'lucide-react';

interface UserMenuProps {
  user?: {
    name: string;
    avatarUrl?: string;
  };
  onLogout?: () => void;
}

function getInitials(name: string): string {
  return name
    .split(' ')
    .map((part) => part[0])
    .join('')
    .toUpperCase()
    .slice(0, 2);
}

export function UserMenu({ user, onLogout }: UserMenuProps) {
  if (!user) return null;

  const initials = getInitials(user.name);

  return (
    <div className="border-t border-slate-200 px-4 py-4 dark:border-slate-700">
      <div className="flex items-center gap-3 rounded-lg bg-slate-50 p-3 dark:bg-slate-700/50">
        {/* Avatar */}
        {user.avatarUrl ? (
          <img
            src={user.avatarUrl}
            alt={user.name}
            className="h-10 w-10 rounded-full object-cover"
          />
        ) : (
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-gradient-to-br from-teal-500 to-rose-500 text-sm font-semibold text-white">
            {initials}
          </div>
        )}

        {/* User info */}
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold text-slate-900 dark:text-white">
            {user.name}
          </div>
          <div className="text-xs text-slate-500 dark:text-slate-400">Parent</div>
        </div>

        {/* Logout button */}
        {onLogout && (
          <button
            onClick={onLogout}
            className="rounded-lg p-2 transition-colors hover:bg-slate-200 dark:hover:bg-slate-600"
            aria-label="Log out"
          >
            <LogOut className="h-4 w-4 text-slate-500 dark:text-slate-400" />
          </button>
        )}
      </div>
    </div>
  );
}
