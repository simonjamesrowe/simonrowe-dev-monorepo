import { X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Drawer } from 'vaul';

import type { PermissionRequestType } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';

export type ComposeMode = 'message' | 'permission';

export interface ComposeChild {
  id: string;
  fullName: string;
}

export type ComposeSubmission =
  | { mode: 'message'; subject: string; message: string }
  | {
      mode: 'permission';
      subject: string;
      type: PermissionRequestType;
      childId: string;
      description: string;
    };

export interface ComposeDrawerProps {
  /** Which form to show; null keeps the drawer closed. */
  mode: ComposeMode | null;
  /** Name of the co-parent who will receive it, shown so nobody wonders where it goes. */
  recipientName: string;
  children: ComposeChild[];
  onClose: () => void;
  onSubmit: (submission: ComposeSubmission) => Promise<void>;
}

const PERMISSION_TYPES: { value: PermissionRequestType; label: string }[] = [
  { value: 'schedule', label: 'Schedule' },
  { value: 'travel', label: 'Travel' },
  { value: 'medical', label: 'Medical' },
  { value: 'extracurricular', label: 'Extracurricular' },
];

const fieldClass =
  'w-full rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100';
const labelClass = 'mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300';

/**
 * Starts a conversation or a permission request. Replaces a chain of `window.prompt` calls,
 * which could not offer a child picker, so every permission request was silently filed
 * against the family's first child.
 */
export function ComposeDrawer({
  mode,
  recipientName,
  children,
  onClose,
  onSubmit,
}: ComposeDrawerProps) {
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [type, setType] = useState<PermissionRequestType>('schedule');
  const [childId, setChildId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Each opening starts from a clean form, so a half-written permission request does not
  // reappear as the subject of the next message.
  useEffect(() => {
    if (!mode) return;
    setSubject('');
    setBody('');
    setType('schedule');
    setChildId('');
    setError(null);
  }, [mode]);

  const isPermission = mode === 'permission';
  const valid =
    body.trim().length > 0 && (isPermission ? childId.length > 0 : subject.trim().length > 0);

  const handleSubmit = async () => {
    if (!mode || !valid || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      if (mode === 'message') {
        await onSubmit({ mode, subject: subject.trim(), message: body.trim() });
      } else {
        const child = children.find((candidate) => candidate.id === childId);
        const label = PERMISSION_TYPES.find((option) => option.value === type)?.label ?? type;
        await onSubmit({
          mode,
          subject: subject.trim() || `${child?.fullName ?? 'Child'} — ${label.toLowerCase()}`,
          type,
          childId,
          description: body.trim(),
        });
      }
      onClose();
    } catch (failure) {
      setError(apiErrorMessage(failure, 'That could not be sent. Try again.'));
    } finally {
      setSubmitting(false);
    }
  };

  const title = isPermission ? 'New permission request' : 'New message';

  return (
    <Drawer.Root open={mode !== null} onOpenChange={(open) => !open && onClose()} direction="right">
      <Drawer.Portal>
        {/* Utilities are scoped to .coparent-app and Vaul portals to <body>, outside it. */}
        <div className="coparent-app">
          <Drawer.Overlay className="fixed inset-0 z-40 bg-black/40" />
          <Drawer.Content className="fixed bottom-0 right-0 top-0 z-50 w-full max-w-lg bg-white shadow-xl outline-none dark:bg-slate-900">
            <Drawer.Title className="sr-only">{title}</Drawer.Title>
            <Drawer.Description className="sr-only">
              {isPermission
                ? `Ask ${recipientName} to approve a decision about a child`
                : `Start a conversation with ${recipientName}`}
            </Drawer.Description>
            <form
              className="flex h-full flex-col"
              onSubmit={(event) => {
                event.preventDefault();
                void handleSubmit();
              }}
            >
              <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4 dark:border-slate-700">
                <div>
                  <h2 className="text-xl font-semibold text-slate-900 dark:text-slate-100">
                    {title}
                  </h2>
                  <p className="text-sm text-slate-500 dark:text-slate-400">To {recipientName}</p>
                </div>
                <button
                  type="button"
                  onClick={onClose}
                  className="rounded-lg p-2 text-slate-500 transition-colors hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                  aria-label="Close"
                >
                  <X size={20} />
                </button>
              </div>

              <div className="flex-1 space-y-5 overflow-y-auto p-6">
                {isPermission && (
                  <>
                    <div>
                      <label htmlFor="compose-type" className={labelClass}>
                        Type
                      </label>
                      <select
                        id="compose-type"
                        value={type}
                        onChange={(event) => setType(event.target.value as PermissionRequestType)}
                        className={fieldClass}
                      >
                        {PERMISSION_TYPES.map((option) => (
                          <option key={option.value} value={option.value}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div>
                      <label htmlFor="compose-child" className={labelClass}>
                        Child
                      </label>
                      <select
                        id="compose-child"
                        value={childId}
                        onChange={(event) => setChildId(event.target.value)}
                        className={fieldClass}
                      >
                        <option value="">Choose a child</option>
                        {children.map((child) => (
                          <option key={child.id} value={child.id}>
                            {child.fullName}
                          </option>
                        ))}
                      </select>
                    </div>
                  </>
                )}
                <div>
                  <label htmlFor="compose-subject" className={labelClass}>
                    Subject{isPermission ? ' (optional)' : ''}
                  </label>
                  <input
                    id="compose-subject"
                    value={subject}
                    onChange={(event) => setSubject(event.target.value)}
                    placeholder={isPermission ? 'e.g. Half term trip' : 'e.g. Half term pickup'}
                    className={fieldClass}
                  />
                </div>
                <div>
                  <label htmlFor="compose-body" className={labelClass}>
                    {isPermission ? 'What are you asking for?' : 'Message'}
                  </label>
                  <textarea
                    id="compose-body"
                    value={body}
                    onChange={(event) => setBody(event.target.value)}
                    rows={6}
                    className={fieldClass}
                  />
                </div>
              </div>

              <div className="border-t border-slate-200 px-6 py-4 dark:border-slate-700">
                {error && (
                  <p
                    role="alert"
                    className="mb-3 rounded-xl border border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/30 dark:text-red-300"
                  >
                    {error}
                  </p>
                )}
                <div className="flex items-center justify-end gap-3">
                  <button
                    type="button"
                    onClick={onClose}
                    className="rounded-xl px-4 py-2.5 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={!valid || submitting}
                    className="rounded-xl bg-teal-600 px-6 py-2.5 text-sm font-medium text-white shadow-lg shadow-teal-500/20 transition-all duration-200 hover:bg-teal-700 disabled:cursor-not-allowed disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
                  >
                    {submitting ? 'Sending…' : isPermission ? 'Send request' : 'Send message'}
                  </button>
                </div>
              </div>
            </form>
          </Drawer.Content>
        </div>
      </Drawer.Portal>
    </Drawer.Root>
  );
}
