import { useMemo } from 'react';

import type {
  DashboardProps,
  Event,
  Expense,
  PermissionRequest,
  QuickAction,
  WidgetCard,
} from '../../types/dashboard';

const widgetSizeStyles: Record<WidgetCard['size'], string> = {
  sm: 'md:col-span-2',
  md: 'md:col-span-3',
  lg: 'md:col-span-4 md:row-span-2',
};

const trendStyles: Record<WidgetCard['trend'], string> = {
  up: 'text-teal-600 dark:text-teal-300',
  down: 'text-rose-600 dark:text-rose-300',
  flat: 'text-slate-500 dark:text-slate-400',
};

const cardBase =
  'rounded-3xl border border-slate-200/70 bg-white shadow-sm dark:border-slate-800/70 dark:bg-slate-900';

const heroBase =
  'rounded-3xl border border-slate-200/70 bg-gradient-to-br from-white via-slate-50 to-slate-100 p-6 shadow-[0_25px_70px_-45px_rgba(15,23,42,0.45)] dark:border-slate-800/70 dark:from-slate-950 dark:via-slate-900 dark:to-slate-900';

const formatDate = (value: string) =>
  new Date(value).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });

const formatTime = (value: string) =>
  new Date(value).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });

const formatCurrency = (value: number, currency = 'GBP') =>
  new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency,
    maximumFractionDigits: 0,
  }).format(value);

const getStatusTone = (status: PermissionRequest['status'] | Expense['status']) => {
  if (status === 'approved' || status === 'reimbursed')
    return 'text-teal-700 bg-teal-50 dark:bg-teal-900/40 dark:text-teal-200';
  if (status === 'denied') return 'text-rose-700 bg-rose-50 dark:bg-rose-900/40 dark:text-rose-200';
  return 'text-amber-700 bg-amber-50 dark:bg-amber-900/40 dark:text-amber-200';
};

const eventTone: Record<Event['type'], string> = {
  custody: 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900',
  activity: 'bg-teal-600 text-white dark:bg-teal-500 dark:text-slate-950',
  appointment: 'bg-rose-600 text-white dark:bg-rose-500 dark:text-slate-950',
  school: 'bg-slate-200 text-slate-700 dark:bg-slate-800 dark:text-slate-200',
  holiday: 'bg-amber-500 text-white dark:bg-amber-400 dark:text-slate-950',
};

const actionStyleMap: Record<QuickAction['id'], string> = {
  'add-expense':
    'border border-rose-200/70 bg-rose-50 text-rose-700 shadow-rose-500/10 hover:border-rose-300 hover:bg-rose-100 dark:border-rose-900/60 dark:bg-rose-900/30 dark:text-rose-200',
  'create-event':
    'bg-teal-600 text-white shadow-teal-500/25 hover:bg-teal-700 dark:bg-teal-500 dark:text-slate-950',
  'send-message':
    'border border-slate-200/70 bg-white text-slate-700 shadow-slate-900/10 hover:border-slate-300 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200',
};

const shortcutStyleMap: Record<QuickAction['id'], string> = {
  'add-expense': 'border-rose-200 text-rose-600 dark:border-rose-900/60 dark:text-rose-200',
  'create-event': 'border-white/30 text-white/80 dark:border-teal-200/60 dark:text-teal-100',
  'send-message': 'border-slate-200 text-slate-500 dark:border-slate-700 dark:text-slate-300',
};

export function DashboardOverview({
  family,
  parents,
  children,
  upcomingEvents,
  permissionRequests,
  messages,
  invitations,
  activityFeed,
  budgetSummary,
  approvalsSummary,
  setupChecklist,
  widgetCards,
  quickActions,
  onOpenProfileDrawer,
  onOpenChildrenDrawer,
  onOpenInvitationsDrawer,
  onAddChild,
  onEditChild,
  onResendInvitation,
  onCancelInvitation,
  onNavigateSection,
  onViewApproval,
  onViewEvent,
  onOpenMessageThread,
  onQuickAddExpense,
  onQuickCreateEvent,
  onQuickSendMessage,
}: DashboardProps) {
  const parentLookup = useMemo(() => {
    return parents.reduce<Record<string, string>>((acc, parent) => {
      acc[parent.id] = parent.fullName;
      return acc;
    }, {});
  }, [parents]);

  const currentParent = useMemo(() => {
    return parents.find((parent) => parent.id === family.primaryParentId) ?? parents[0];
  }, [family.primaryParentId, parents]);

  const pendingApprovals = permissionRequests.filter((request) => request.status === 'pending');
  const unreadMessages = messages.filter((message) => message.unread);
  const pendingInvites = invitations.filter((invite) => invite.status === 'pending');

  const quickActionHandlers: Record<QuickAction['id'], (() => void) | undefined> = {
    'add-expense': onQuickAddExpense,
    'create-event': onQuickCreateEvent,
    'send-message': onQuickSendMessage,
  };

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-6 px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
        <section className={heroBase}>
          <header className="flex flex-col gap-5 lg:flex-row lg:items-start lg:justify-between">
            <div className="max-w-2xl">
              <p className="text-xs uppercase tracking-[0.35em] text-teal-600 dark:text-teal-400">
                Family Dashboard
              </p>
              <h1 className="mt-3 text-2xl font-semibold sm:text-3xl">
                Welcome back{currentParent ? `, ${currentParent.fullName.split(' ')[0]}` : ''}. Keep
                the family in sync.
              </h1>
              <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">
                {family.name} · Timezone {family.timezone} · {children.length} children
              </p>
              <div className="mt-4 flex flex-wrap items-center gap-2">
                <span className="inline-flex items-center gap-2 rounded-full bg-teal-50 px-3 py-1 text-xs font-medium text-teal-700 dark:bg-teal-900/40 dark:text-teal-200">
                  {approvalsSummary.totalPending} approvals awaiting response
                </span>
                <span className="inline-flex items-center gap-2 rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600 dark:bg-slate-800 dark:text-slate-200">
                  {upcomingEvents.length} upcoming events queued
                </span>
                <span className="inline-flex items-center gap-2 rounded-full bg-rose-50 px-3 py-1 text-xs font-medium text-rose-700 dark:bg-rose-900/40 dark:text-rose-200">
                  {unreadMessages.length} unread messages
                </span>
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <button
                onClick={onOpenProfileDrawer}
                className="rounded-full border border-slate-200/70 bg-white px-4 py-2 text-sm font-medium text-slate-700 shadow-sm transition hover:border-slate-300 hover:text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200"
              >
                Profile
              </button>
              <button
                onClick={onOpenChildrenDrawer}
                className="rounded-full border border-teal-200/70 bg-teal-50 px-4 py-2 text-sm font-medium text-teal-700 transition hover:border-teal-300 hover:bg-teal-100 dark:border-teal-900/60 dark:bg-teal-900/30 dark:text-teal-200"
              >
                Children
              </button>
              <button
                onClick={onOpenInvitationsDrawer}
                className="rounded-full border border-rose-200/70 bg-rose-50 px-4 py-2 text-sm font-medium text-rose-700 transition hover:border-rose-300 hover:bg-rose-100 dark:border-rose-900/60 dark:bg-rose-900/30 dark:text-rose-200"
              >
                Invitations
              </button>
            </div>
          </header>

          <div className="mt-6 rounded-2xl border border-slate-200/70 bg-white/80 p-4 shadow-sm dark:border-slate-800/70 dark:bg-slate-900/60">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <p className="text-sm font-semibold">Quick actions</p>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Launch the most common tasks in one tap.
                </p>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {quickActions.map((action) => (
                  <button
                    key={action.id}
                    onClick={quickActionHandlers[action.id]}
                    className={`inline-flex items-center gap-2 rounded-full px-4 py-2 text-sm font-medium shadow-lg transition hover:-translate-y-0.5 active:translate-y-0 ${actionStyleMap[action.id]}`}
                  >
                    {action.label}
                    <span
                      className={`rounded-full border px-2 py-0.5 text-[10px] uppercase tracking-[0.2em] ${shortcutStyleMap[action.id]}`}
                    >
                      {action.shortcut}
                    </span>
                  </button>
                ))}
              </div>
            </div>
          </div>
        </section>

        <section className="grid gap-4 md:grid-cols-6">
          {widgetCards.map((widget) => (
            <button
              key={widget.id}
              onClick={() => onNavigateSection?.(widget.sectionId)}
              className={`${cardBase} ${widgetSizeStyles[widget.size]} p-5 text-left transition hover:-translate-y-0.5 hover:shadow-md`}
            >
              <p className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-500 dark:text-slate-400">
                {widget.title}
              </p>
              <div className="mt-4 flex items-end justify-between gap-4">
                <div>
                  <p className="text-3xl font-semibold text-slate-900 dark:text-white">
                    {widget.value}
                  </p>
                  <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                    {widget.description}
                  </p>
                </div>
                <div className="text-right">
                  <p className={`text-sm font-semibold ${trendStyles[widget.trend]}`}>
                    {widget.delta}
                  </p>
                  <p className="text-[11px] text-slate-400 dark:text-slate-500">vs last period</p>
                </div>
              </div>
            </button>
          ))}
        </section>

        <section className="grid gap-4 lg:grid-cols-2">
          <div className={`${cardBase} p-5`}>
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-semibold text-slate-900 dark:text-white">
                  Upcoming events
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Next few scheduled items.
                </p>
              </div>
              <button
                onClick={() => onNavigateSection?.('calendar')}
                className="text-xs font-semibold text-teal-600 hover:text-teal-700 dark:text-teal-300"
              >
                View all
              </button>
            </div>
            <div className="mt-4 space-y-3">
              {upcomingEvents.length === 0 && (
                <p className="text-sm text-slate-500 dark:text-slate-400">No events scheduled.</p>
              )}
              {upcomingEvents.slice(0, 4).map((event) => (
                <button
                  key={event.id}
                  onClick={() => onViewEvent?.(event.id)}
                  className="w-full rounded-2xl border border-slate-200/70 bg-white/80 px-4 py-3 text-left text-sm transition hover:border-teal-200 dark:border-slate-800/70 dark:bg-slate-950/60"
                >
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <p className="font-medium text-slate-900 dark:text-white">{event.title}</p>
                      <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                        {formatDate(event.startAt)} · {formatTime(event.startAt)} –{' '}
                        {formatTime(event.endAt)}
                      </p>
                    </div>
                    <span
                      className={`rounded-full px-3 py-1 text-xs font-semibold ${eventTone[event.type]}`}
                    >
                      {event.type}
                    </span>
                  </div>
                </button>
              ))}
            </div>
          </div>

          <div className={`${cardBase} p-5`}>
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-semibold text-slate-900 dark:text-white">
                  Pending approvals
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Permissions and reimbursements.
                </p>
              </div>
              <button
                onClick={() => onNavigateSection?.('permissions')}
                className="text-xs font-semibold text-slate-500 hover:text-slate-700 dark:text-slate-300"
              >
                Review all
              </button>
            </div>
            <div className="mt-4 space-y-3">
              {pendingApprovals.length === 0 && (
                <p className="text-sm text-slate-500 dark:text-slate-400">No approvals waiting.</p>
              )}
              {pendingApprovals.slice(0, 4).map((request) => (
                <button
                  key={request.id}
                  onClick={() => onViewApproval?.(request.id)}
                  className="w-full rounded-2xl border border-slate-200/70 bg-white/80 px-4 py-3 text-left text-sm transition hover:border-teal-200 dark:border-slate-800/70 dark:bg-slate-950/60"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <p className="font-medium text-slate-900 dark:text-white">{request.title}</p>
                      <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                        {request.summary}
                      </p>
                    </div>
                    <span
                      className={`rounded-full px-3 py-1 text-xs font-medium ${getStatusTone(request.status)}`}
                    >
                      {request.status}
                    </span>
                  </div>
                </button>
              ))}
            </div>
          </div>
        </section>

        <section className="grid gap-4 lg:grid-cols-3">
          <div className={`${cardBase} p-5 lg:col-span-2`}>
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-semibold text-slate-900 dark:text-white">
                  Budget snapshot
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">{budgetSummary.month}</p>
              </div>
              <button
                onClick={() => onNavigateSection?.('expenses')}
                className="text-xs font-semibold text-rose-600 hover:text-rose-700 dark:text-rose-300"
              >
                Open expenses
              </button>
            </div>

            <div className="mt-4 grid gap-3 rounded-2xl border border-slate-200/70 bg-white/80 p-4 sm:grid-cols-3 dark:border-slate-800/70 dark:bg-slate-950/60">
              <div>
                <p className="text-xs text-slate-500 dark:text-slate-400">Spent</p>
                <p className="mt-1 text-lg font-semibold text-slate-900 dark:text-white">
                  {formatCurrency(budgetSummary.totalSpent, budgetSummary.currency)}
                </p>
              </div>
              <div>
                <p className="text-xs text-slate-500 dark:text-slate-400">Limit</p>
                <p className="mt-1 text-lg font-semibold text-slate-900 dark:text-white">
                  {formatCurrency(budgetSummary.totalLimit, budgetSummary.currency)}
                </p>
              </div>
              <div>
                <p className="text-xs text-slate-500 dark:text-slate-400">Remaining</p>
                <p className="mt-1 text-lg font-semibold text-slate-900 dark:text-white">
                  {formatCurrency(budgetSummary.remaining, budgetSummary.currency)}
                </p>
              </div>
            </div>

            <div className="mt-4 space-y-3">
              {budgetSummary.categories.length === 0 && (
                <p className="text-sm text-slate-500 dark:text-slate-400">
                  No budget categories yet.
                </p>
              )}
              {budgetSummary.categories.slice(0, 4).map((category) => (
                <div
                  key={category.category}
                  className="rounded-2xl border border-slate-200/70 bg-white/80 px-4 py-3 dark:border-slate-800/70 dark:bg-slate-950/60"
                >
                  <div className="flex items-center justify-between text-sm">
                    <p className="font-medium text-slate-900 dark:text-white">
                      {category.category}
                    </p>
                    <p className="text-xs text-slate-500 dark:text-slate-400">
                      {formatCurrency(category.spent, budgetSummary.currency)} /{' '}
                      {formatCurrency(category.limit, budgetSummary.currency)}
                    </p>
                  </div>
                  <div className="mt-2 h-2 rounded-full bg-slate-100 dark:bg-slate-800">
                    <div
                      className="h-2 rounded-full bg-rose-500"
                      style={{
                        width: `${Math.min(100, Math.round((category.spent / Math.max(category.limit, 1)) * 100))}%`,
                      }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className={`${cardBase} p-5`}>
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-semibold text-slate-900 dark:text-white">
                  Setup checklist
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  {setupChecklist.completedCount} of {setupChecklist.totalCount} complete
                </p>
              </div>
              <button
                onClick={() => onNavigateSection?.('family')}
                className="text-xs font-semibold text-slate-500 hover:text-slate-700 dark:text-slate-300"
              >
                Continue
              </button>
            </div>
            <div className="mt-4 space-y-3">
              {setupChecklist.items.map((item) => (
                <div
                  key={item.id}
                  className="flex items-center justify-between rounded-2xl border border-slate-200/70 bg-white/80 px-4 py-3 text-sm dark:border-slate-800/70 dark:bg-slate-950/60"
                >
                  <p className="text-slate-700 dark:text-slate-200">{item.label}</p>
                  <span
                    className={`rounded-full px-3 py-1 text-xs font-medium ${
                      item.completed
                        ? 'bg-teal-50 text-teal-700 dark:bg-teal-900/40 dark:text-teal-200'
                        : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300'
                    }`}
                  >
                    {item.completed ? 'Done' : 'Next'}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section className="grid gap-4 lg:grid-cols-2">
          <div className={`${cardBase} p-5`}>
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-semibold text-slate-900 dark:text-white">
                  Recent activity
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Latest updates across the family.
                </p>
              </div>
              <button
                onClick={() => onNavigateSection?.('dashboard')}
                className="text-xs font-semibold text-slate-500 hover:text-slate-700 dark:text-slate-300"
              >
                Refresh
              </button>
            </div>
            <div className="mt-4 space-y-3">
              {activityFeed.length === 0 && (
                <p className="text-sm text-slate-500 dark:text-slate-400">
                  No recent activity yet.
                </p>
              )}
              {activityFeed.slice(0, 6).map((item) => (
                <div
                  key={item.id}
                  className="rounded-2xl border border-slate-200/70 bg-white/80 px-4 py-3 text-sm dark:border-slate-800/70 dark:bg-slate-950/60"
                >
                  <p className="font-medium text-slate-900 dark:text-white">{item.title}</p>
                  <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{item.summary}</p>
                  <p className="mt-2 text-[11px] text-slate-400 dark:text-slate-500">
                    {parentLookup[item.actorParentId] || 'Parent'} · {formatDate(item.timestamp)}
                  </p>
                </div>
              ))}
            </div>
          </div>

          <div className={`${cardBase} p-5`}>
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-semibold text-slate-900 dark:text-white">Messages</h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Unread threads and quick replies.
                </p>
              </div>
              <button
                onClick={() => onNavigateSection?.('messaging')}
                className="text-xs font-semibold text-slate-500 hover:text-slate-700 dark:text-slate-300"
              >
                Open inbox
              </button>
            </div>
            <div className="mt-4 space-y-3">
              {unreadMessages.length === 0 && (
                <p className="text-sm text-slate-500 dark:text-slate-400">No unread threads.</p>
              )}
              {unreadMessages.slice(0, 3).map((message) => (
                <button
                  key={message.id}
                  onClick={() => onOpenMessageThread?.(message.threadId)}
                  className="w-full rounded-2xl border border-slate-200/70 bg-white/80 px-3 py-3 text-left text-sm transition hover:border-teal-200 dark:border-slate-800/70 dark:bg-slate-950/60"
                >
                  <p className="font-medium text-slate-900 dark:text-white">{message.subject}</p>
                  <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                    {message.preview}
                  </p>
                </button>
              ))}
            </div>
          </div>
        </section>

        <section className="grid gap-4 lg:grid-cols-2">
          <div className={`${cardBase} p-5`}>
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-semibold text-slate-900 dark:text-white">
                  Children overview
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Profiles and care details.
                </p>
              </div>
              <button
                onClick={onOpenChildrenDrawer}
                className="text-xs font-semibold text-teal-600 hover:text-teal-700 dark:text-teal-300"
              >
                Manage
              </button>
            </div>
            <div className="mt-4 grid gap-3">
              {children.length === 0 && (
                <p className="text-sm text-slate-500 dark:text-slate-400">No child profiles yet.</p>
              )}
              {children.map((child) => (
                <div
                  key={child.id}
                  className="rounded-2xl border border-slate-200/70 bg-white/80 p-4 dark:border-slate-800/70 dark:bg-slate-950/60"
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-sm font-semibold text-slate-900 dark:text-white">
                        {child.firstName} {child.lastName}
                      </p>
                      <p className="text-xs text-slate-500 dark:text-slate-400">
                        {child.school} · {child.grade}
                      </p>
                    </div>
                    <button
                      onClick={() => onEditChild?.(child.id)}
                      className="text-xs font-semibold text-slate-500 transition hover:text-teal-600"
                    >
                      Edit
                    </button>
                  </div>
                  <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
                    Notes: {child.medicalNotes ? child.medicalNotes : 'None'}
                  </p>
                </div>
              ))}
              <button
                onClick={onAddChild}
                className="rounded-2xl border border-dashed border-slate-300/70 bg-slate-50 px-4 py-3 text-left text-sm font-medium text-slate-600 transition hover:border-teal-300 hover:text-teal-700 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300"
              >
                + Add another child profile
              </button>
            </div>
          </div>

          <div className={`${cardBase} p-5`}>
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-base font-semibold text-slate-900 dark:text-white">
                  Invitations
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Pending caregiver access requests.
                </p>
              </div>
              <button
                onClick={onOpenInvitationsDrawer}
                className="text-xs font-semibold text-rose-600 hover:text-rose-700 dark:text-rose-300"
              >
                Review
              </button>
            </div>
            <div className="mt-4 space-y-3">
              {pendingInvites.length === 0 && (
                <p className="text-sm text-slate-500 dark:text-slate-400">
                  No pending invitations.
                </p>
              )}
              {pendingInvites.map((invitation) => (
                <div
                  key={invitation.id}
                  className="rounded-2xl border border-slate-200/70 bg-white/80 px-4 py-3 dark:border-slate-800/70 dark:bg-slate-950/60"
                >
                  <div className="flex items-center justify-between text-sm">
                    <div>
                      <p className="font-medium text-slate-900 dark:text-white">
                        {invitation.email}
                      </p>
                      <p className="text-xs text-slate-500 dark:text-slate-400">
                        Sent {formatDate(invitation.sentAt)}
                      </p>
                    </div>
                    <span className="rounded-full bg-amber-50 px-2 py-1 text-xs font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-200">
                      {invitation.status}
                    </span>
                  </div>
                  <div className="mt-3 flex flex-wrap gap-2">
                    <button
                      onClick={() => onResendInvitation?.(invitation.id)}
                      className="rounded-full border border-slate-200/70 px-3 py-1 text-xs font-medium text-slate-600 hover:border-teal-200 hover:text-teal-600 dark:border-slate-700 dark:text-slate-300"
                    >
                      Resend
                    </button>
                    <button
                      onClick={() => onCancelInvitation?.(invitation.id)}
                      className="rounded-full border border-rose-200/70 px-3 py-1 text-xs font-medium text-rose-600 hover:border-rose-300 dark:border-rose-900/60 dark:text-rose-300"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              ))}
              {invitations.length === 0 && (
                <button
                  onClick={onOpenInvitationsDrawer}
                  className="rounded-2xl border border-dashed border-slate-300/70 bg-slate-50 px-4 py-3 text-left text-sm font-medium text-slate-600 transition hover:border-rose-300 hover:text-rose-700 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300"
                >
                  + Invite a co-parent or caregiver
                </button>
              )}
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
