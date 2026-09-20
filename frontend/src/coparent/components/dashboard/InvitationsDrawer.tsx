import type { InvitationsDrawerProps } from '../../types/dashboard';

const panelBase =
  'h-full w-full max-w-md border-l border-slate-200/70 bg-white shadow-2xl shadow-slate-900/20 dark:border-slate-800/70 dark:bg-slate-950';

const statusTone: Record<string, string> = {
  pending: 'bg-amber-50 text-amber-700 dark:bg-amber-900/40 dark:text-amber-200',
  accepted: 'bg-teal-50 text-teal-700 dark:bg-teal-900/40 dark:text-teal-200',
  expired: 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300',
  canceled: 'bg-rose-50 text-rose-700 dark:bg-rose-900/40 dark:text-rose-200',
};

const formatDate = (value: string) =>
  new Date(value).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });

export function InvitationsDrawer({
  invitations,
  parents,
  isOpen,
  onClose,
  onResendInvitation,
  onCancelInvitation,
}: InvitationsDrawerProps) {
  const parentLookup = parents.reduce<Record<string, string>>((acc, parent) => {
    acc[parent.id] = parent.fullName;
    return acc;
  }, {});

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
            <p className="text-xs uppercase tracking-[0.3em] text-slate-400">Invitations</p>
            <h2 className="mt-2 text-lg font-semibold text-slate-900 dark:text-white">
              Manage pending invites
            </h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Resend or cancel outstanding requests.
            </p>
          </div>
          <button
            onClick={onClose}
            className="rounded-full border border-slate-200/70 px-3 py-1 text-xs font-medium text-slate-600 hover:border-slate-300 hover:text-slate-900 dark:border-slate-700 dark:text-slate-300"
          >
            Close
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto px-6 py-5">
          {invitations.length === 0 && (
            <p className="text-sm text-slate-500 dark:text-slate-400">No invitations yet.</p>
          )}
          {invitations.map((invitation) => (
            <div
              key={invitation.id}
              className="rounded-3xl border border-slate-200/70 bg-white/80 p-4 dark:border-slate-800/70 dark:bg-slate-950/60"
            >
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-semibold text-slate-900 dark:text-white">
                    {invitation.email}
                  </p>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    Role: {invitation.role}
                  </p>
                </div>
                <span
                  className={`rounded-full px-3 py-1 text-xs font-medium ${statusTone[invitation.status] || statusTone.pending}`}
                >
                  {invitation.status}
                </span>
              </div>
              <div className="mt-3 text-xs text-slate-500 dark:text-slate-400">
                <p>Invited by {parentLookup[invitation.invitedByParentId] || 'Parent'}</p>
                <p>
                  Sent {formatDate(invitation.sentAt)} · Expires {formatDate(invitation.expiresAt)}
                </p>
              </div>
              {invitation.status === 'pending' && (
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
              )}
            </div>
          ))}
        </div>
      </aside>
    </div>
  );
}
