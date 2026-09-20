import type { ChildrenDrawerProps } from '../../types/dashboard';

const panelBase =
  'h-full w-full max-w-md border-l border-slate-200/70 bg-white shadow-2xl shadow-slate-900/20 dark:border-slate-800/70 dark:bg-slate-950';

export function ChildrenDrawer({
  children,
  isOpen,
  onClose,
  onAddChild,
  onEditChild,
}: ChildrenDrawerProps) {
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
            <p className="text-xs uppercase tracking-[0.3em] text-slate-400">Children</p>
            <h2 className="mt-2 text-lg font-semibold text-slate-900 dark:text-white">
              Manage child profiles
            </h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Update school, health, and care info.
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
          {children.length === 0 && (
            <p className="text-sm text-slate-500 dark:text-slate-400">No child profiles yet.</p>
          )}
          {children.map((child) => (
            <div
              key={child.id}
              className="rounded-3xl border border-slate-200/70 bg-white/80 p-4 dark:border-slate-800/70 dark:bg-slate-950/60"
            >
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-semibold text-slate-900 dark:text-white">
                    {child.firstName} {child.lastName}
                  </p>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    {child.grade} · {child.school}
                  </p>
                </div>
                <button
                  onClick={() => onEditChild?.(child.id)}
                  className="rounded-full border border-slate-200/70 px-3 py-1 text-xs font-medium text-slate-600 hover:border-teal-200 hover:text-teal-600 dark:border-slate-700 dark:text-slate-300"
                >
                  Edit
                </button>
              </div>
              <div className="mt-3 text-xs text-slate-500 dark:text-slate-400">
                <p>Birthdate: {child.birthdate}</p>
                <p>Medical: {child.medicalNotes}</p>
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                {child.allergies.length === 0 ? (
                  <span className="rounded-full bg-slate-100 px-3 py-1 text-[11px] text-slate-500 dark:bg-slate-800 dark:text-slate-300">
                    No listed allergies
                  </span>
                ) : (
                  child.allergies.map((allergy) => (
                    <span
                      key={allergy}
                      className="rounded-full bg-rose-50 px-3 py-1 text-[11px] text-rose-700 dark:bg-rose-900/40 dark:text-rose-200"
                    >
                      {allergy}
                    </span>
                  ))
                )}
              </div>
            </div>
          ))}
        </div>

        <div className="border-t border-slate-200/70 px-6 py-5 dark:border-slate-800/70">
          <button
            onClick={onAddChild}
            className="w-full rounded-full bg-teal-600 px-4 py-3 text-sm font-semibold text-white shadow-lg shadow-teal-500/30 transition hover:-translate-y-0.5 hover:bg-teal-700 dark:bg-teal-500 dark:text-slate-950"
          >
            Add new child
          </button>
        </div>
      </aside>
    </div>
  );
}
