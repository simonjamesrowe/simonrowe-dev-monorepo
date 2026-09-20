'use client';

import { Settings } from 'lucide-react';

import type { NavigationItem } from './AppShell';

interface MainNavProps {
  items: NavigationItem[];
  onNavigate?: (href: string) => void;
}

export function MainNav({ items, onNavigate }: MainNavProps) {
  // Separate settings from main items
  const mainItems = items.filter((item) => item.label.toLowerCase() !== 'settings');
  const settingsItem = items.find((item) => item.label.toLowerCase() === 'settings');

  return (
    <nav className="flex flex-1 flex-col px-4 py-6">
      {/* Main section label */}
      <div className="mb-2 px-3 text-[11px] font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        Main
      </div>

      {/* Main navigation items */}
      <div className="space-y-1">
        {mainItems.map((item) => (
          <button
            key={item.href}
            aria-label={item.label}
            onClick={() => onNavigate?.(item.href)}
            className={`flex w-full items-center gap-3 rounded-lg px-3 py-3 text-sm font-medium transition-all duration-200 ${
              item.isActive
                ? 'bg-teal-50 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400'
                : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700/50'
            } `}
          >
            {item.icon && (
              <span className="flex h-5 w-5 items-center justify-center">{item.icon}</span>
            )}
            {item.label}
          </button>
        ))}
      </div>

      {/* Spacer */}
      <div className="flex-1" />

      {/* Settings section */}
      {settingsItem && (
        <div className="border-t border-slate-200 pt-4 dark:border-slate-700">
          <button
            aria-label={settingsItem.label}
            onClick={() => onNavigate?.(settingsItem.href)}
            className={`flex w-full items-center gap-3 rounded-lg px-3 py-3 text-sm font-medium transition-all duration-200 ${
              settingsItem.isActive
                ? 'bg-teal-50 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400'
                : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700/50'
            } `}
          >
            {settingsItem.icon || <Settings className="h-5 w-5" />}
            {settingsItem.label}
          </button>
        </div>
      )}
    </nav>
  );
}
