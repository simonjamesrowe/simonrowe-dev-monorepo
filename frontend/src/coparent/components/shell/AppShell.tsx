'use client';

import { ListPlus, Menu, X } from 'lucide-react';
import { useState } from 'react';

import { QuickAddDrawer } from '../assistant';

import { MainNav } from './MainNav';
import { UserMenu } from './UserMenu';

export interface NavigationItem {
  label: string;
  href: string;
  icon?: React.ReactNode;
  isActive?: boolean;
}

export interface AppShellProps {
  children: React.ReactNode;
  navigationItems: NavigationItem[];
  user?: {
    name: string;
    avatarUrl?: string;
  };
  onNavigate?: (href: string) => void;
  onLogout?: () => void;
  showIdleCountdown?: boolean;
  idleCountdownSeconds?: number;
  assistantEnabled?: boolean;
}

function formatCountdown(seconds: number): string {
  const clamped = Math.max(0, Math.floor(seconds));
  const minutes = Math.floor(clamped / 60);
  const remaining = clamped % 60;
  return `${minutes}:${remaining.toString().padStart(2, '0')}`;
}

export function AppShell({
  children,
  navigationItems,
  user,
  onNavigate,
  onLogout,
  showIdleCountdown,
  idleCountdownSeconds,
  assistantEnabled = false,
}: AppShellProps) {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [quickAddOpen, setQuickAddOpen] = useState(false);
  const shouldShowCountdown =
    showIdleCountdown && typeof idleCountdownSeconds === 'number' && idleCountdownSeconds >= 0;
  const countdownLabel = shouldShowCountdown ? formatCountdown(idleCountdownSeconds) : null;

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-900">
      {/* Mobile header */}
      <header className="fixed left-0 right-0 top-0 z-40 flex h-16 items-center justify-between border-b border-slate-200 bg-white px-4 lg:hidden dark:border-slate-700 dark:bg-slate-800">
        <div className="flex items-center gap-3">
          <img
            src="/coparent-pwa-192x192.png"
            alt=""
            aria-hidden="true"
            className="block h-10 w-10 rounded-xl"
          />
          <div className="flex items-center gap-2">
            <span className="text-xl font-bold text-slate-900 dark:text-white">CoParent</span>
            {shouldShowCountdown && countdownLabel && (
              <span className="rounded-full border border-amber-200/80 bg-amber-50 px-2 py-0.5 text-xs font-semibold text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
                Auto logout {countdownLabel}
              </span>
            )}
          </div>
        </div>
        <div className="assistant-launcher__mobile-controls">
          {assistantEnabled && (
            <button type="button" className="assistant-launcher assistant-launcher--mobile" onClick={() => setQuickAddOpen(true)}>
              <ListPlus size={18} /> <span>Quick add</span>
            </button>
          )}
          <button
            onClick={() => setSidebarOpen(!sidebarOpen)}
            className="rounded-lg p-2 transition-colors hover:bg-slate-100 dark:hover:bg-slate-700"
            aria-label="Toggle menu"
          >
            {sidebarOpen ? (
              <X className="h-6 w-6 text-slate-600 dark:text-slate-300" />
            ) : (
              <Menu className="h-6 w-6 text-slate-600 dark:text-slate-300" />
            )}
          </button>
        </div>
      </header>

      {/* Mobile overlay */}
      {sidebarOpen && (
        <button
          type="button"
          aria-label="Close navigation menu"
          className="fixed inset-0 z-30 bg-black/50 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Sidebar */}
      <aside
        className={`fixed left-0 top-0 z-40 flex h-full w-[260px] flex-col border-r border-slate-200 bg-white transition-transform duration-300 ease-in-out lg:visible lg:translate-x-0 dark:border-slate-700 dark:bg-slate-800 ${sidebarOpen ? 'visible translate-x-0' : 'invisible -translate-x-full'} `}
      >
        {/* Logo */}
        <div className="border-b border-slate-200 px-4 py-6 dark:border-slate-700">
          <div className="flex items-center gap-3">
            <img
              src="/coparent-pwa-192x192.png"
              alt=""
              aria-hidden="true"
              className="block h-10 w-10 rounded-xl"
            />
            <span className="text-xl font-bold text-slate-900 dark:text-white">CoParent</span>
          </div>
          {shouldShowCountdown && countdownLabel && (
            <div className="mt-3 inline-flex items-center gap-2 rounded-xl border border-amber-200/80 bg-amber-50 px-3 py-1 text-xs font-semibold text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
              <span className="h-2 w-2 rounded-full bg-amber-500" aria-hidden="true" />
              Auto logout in {countdownLabel}
            </div>
          )}
        </div>

        {/* Navigation */}
        <MainNav
          items={navigationItems}
          onNavigate={(href) => {
            onNavigate?.(href);
            setSidebarOpen(false);
          }}
        />

        {assistantEnabled && (
          <button type="button" className="assistant-launcher assistant-launcher--desktop" onClick={() => setQuickAddOpen(true)}>
            <ListPlus size={18} />
            <span><strong>Quick add</strong><small>Turn a note into actions</small></span>
          </button>
        )}

        {/* User Menu */}
        <UserMenu user={user} onLogout={onLogout} />
      </aside>

      {/* Main content */}
      <main className="min-h-screen pt-16 lg:ml-[260px] lg:pt-0">{children}</main>
      {assistantEnabled && quickAddOpen && (
        <QuickAddDrawer open={quickAddOpen} onClose={() => setQuickAddOpen(false)} />
      )}
    </div>
  );
}
