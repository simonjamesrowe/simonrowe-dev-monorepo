import { useEffect, useState } from 'react';

import type {
  Family,
  Parent,
  Child,
  Invitation,
  InvitationStatus,
  ParentRole,
} from '../../lib/api/client';
import { formatDate } from '../../lib/formatters/date';

type ChildDraft = {
  fullName: string;
  dateOfBirth: string;
  school?: string;
  medicalNotes?: string;
};

interface FamilySetupHubProps {
  families: Family[];
  parents: Parent[];
  children: Child[];
  invitations: Invitation[];
  activeFamilyId?: string;
  childIdToEdit?: string;
  onCloseChildEditor?: () => void;
  onUpdateFamily?: (id: string, updates: Partial<Family>) => void;
  onAddChild?: (child: ChildDraft) => void;
  onUpdateChild?: (id: string, updates: Partial<ChildDraft>) => void;
  onInviteCoParent?: (familyId: string, email: string, role: ParentRole) => void;
  onResendInvite?: (invitationId: string) => void;
  onCancelInvite?: (invitationId: string) => void;
  onAssignRole?: (parentId: string, role: ParentRole) => void;
}

function getInvitationStatusBadge(status: InvitationStatus) {
  const badges = {
    pending: {
      bg: 'bg-amber-100 dark:bg-amber-900/30',
      text: 'text-amber-700 dark:text-amber-300',
      label: 'Pending',
    },
    accepted: {
      bg: 'bg-emerald-100 dark:bg-emerald-900/30',
      text: 'text-emerald-700 dark:text-emerald-300',
      label: 'Accepted',
    },
    expired: {
      bg: 'bg-slate-100 dark:bg-slate-800',
      text: 'text-slate-500 dark:text-slate-400',
      label: 'Expired',
    },
    canceled: {
      bg: 'bg-rose-100 dark:bg-rose-900/30',
      text: 'text-rose-700 dark:text-rose-300',
      label: 'Canceled',
    },
  };
  return badges[status];
}

function getRoleBadge(role: ParentRole) {
  return role === 'primary'
    ? {
        bg: 'bg-teal-100 dark:bg-teal-900/30',
        text: 'text-teal-700 dark:text-teal-300',
        label: 'Primary',
      }
    : {
        bg: 'bg-violet-100 dark:bg-violet-900/30',
        text: 'text-violet-700 dark:text-violet-300',
        label: 'Co-Parent',
      };
}

function calculateAge(dateOfBirth: string): number {
  const today = new Date();
  const birthDate = new Date(dateOfBirth);
  let age = today.getFullYear() - birthDate.getFullYear();
  const monthDiff = today.getMonth() - birthDate.getMonth();
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birthDate.getDate())) {
    age--;
  }
  return age;
}

export function FamilySetupHub({
  families,
  parents,
  children,
  invitations,
  activeFamilyId,
  childIdToEdit,
  onCloseChildEditor,
  onUpdateFamily,
  onAddChild,
  onUpdateChild,
  onInviteCoParent,
  onResendInvite,
  onCancelInvite,
  onAssignRole,
}: FamilySetupHubProps) {
  const activeFamily = families.find((f) => f.id === activeFamilyId);
  const familyParents = parents.filter((p) => p.familyId === activeFamilyId);
  const familyChildren = children.filter((c) => c.familyId === activeFamilyId);
  const familyInvitations = invitations.filter((i) => i.familyId === activeFamilyId);
  const pendingInvites = familyInvitations.filter((i) => i.status === 'pending');
  const [childName, setChildName] = useState('');
  const [childDob, setChildDob] = useState('');
  const [childSchool, setChildSchool] = useState('');
  const [childMedicalNotes, setChildMedicalNotes] = useState('');
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<ParentRole>('co-parent');
  const [editingChildId, setEditingChildId] = useState<string | null>(null);
  const [editChildName, setEditChildName] = useState('');
  const [editChildDob, setEditChildDob] = useState('');
  const [editChildSchool, setEditChildSchool] = useState('');
  const [editChildMedicalNotes, setEditChildMedicalNotes] = useState('');

  const editingChild =
    (editingChildId ? familyChildren.find((c) => c.id === editingChildId) : undefined) ??
    (childIdToEdit ? familyChildren.find((c) => c.id === childIdToEdit) : undefined);

  useEffect(() => {
    if (!editingChild) return;
    setEditChildName(editingChild.fullName);
    setEditChildDob(editingChild.dateOfBirth);
    setEditChildSchool(editingChild.school ?? '');
    setEditChildMedicalNotes(editingChild.medicalNotes ?? '');
  }, [
    editingChild?.dateOfBirth,
    editingChild?.fullName,
    editingChild?.id,
    editingChild?.medicalNotes,
    editingChild?.school,
  ]);

  const openEditor = (child: Child) => {
    setEditingChildId(child.id);
    setEditChildName(child.fullName);
    setEditChildDob(child.dateOfBirth);
    setEditChildSchool(child.school ?? '');
    setEditChildMedicalNotes(child.medicalNotes ?? '');
  };

  const closeEditor = () => {
    setEditingChildId(null);
    onCloseChildEditor?.();
  };

  const handleSaveChild = () => {
    if (!editingChild) return;
    const trimmedName = editChildName.trim();
    if (!trimmedName || !editChildDob) return;

    onUpdateChild?.(editingChild.id, {
      fullName: trimmedName,
      dateOfBirth: editChildDob,
      school: editChildSchool || undefined,
      medicalNotes: editChildMedicalNotes || undefined,
    });
  };

  const handleAddChild = () => {
    const trimmedName = childName.trim();
    if (!trimmedName || !childDob) return;
    onAddChild?.({
      fullName: trimmedName,
      dateOfBirth: childDob,
      school: childSchool || undefined,
      medicalNotes: childMedicalNotes || undefined,
    });
    setChildName('');
    setChildDob('');
    setChildSchool('');
    setChildMedicalNotes('');
  };

  const handleInvite = () => {
    const normalizedEmail = inviteEmail.trim();
    if (!activeFamilyId || !normalizedEmail) return;
    onInviteCoParent?.(activeFamilyId, normalizedEmail, inviteRole);
    setInviteEmail('');
    setInviteRole('co-parent');
  };

  if (!activeFamily) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 dark:bg-slate-900">
        <div className="text-center">
          <p className="text-slate-500 dark:text-slate-400">No family selected</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-white to-teal-50/30 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
      {editingChild && (
        <div className="fixed inset-0 z-50">
          <div
            className="absolute inset-0 bg-slate-950/30 backdrop-blur-sm"
            onClick={closeEditor}
          />
          <div className="absolute inset-x-0 bottom-0 mx-auto w-full max-w-2xl rounded-t-3xl border border-slate-200 bg-white p-6 shadow-2xl sm:inset-y-0 sm:my-auto sm:rounded-3xl dark:border-slate-700 dark:bg-slate-900">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">
                  Edit child
                </p>
                <h3 className="mt-1 text-lg font-bold text-slate-900 dark:text-white">
                  {editingChild.fullName}
                </h3>
              </div>
              <button
                type="button"
                onClick={closeEditor}
                className="rounded-full border border-slate-200 px-3 py-1 text-xs font-semibold text-slate-600 hover:border-slate-300 hover:text-slate-900 dark:border-slate-700 dark:text-slate-300"
              >
                Close
              </button>
            </div>

            <div className="mt-6 grid gap-4 sm:grid-cols-2">
              <div className="sm:col-span-2">
                <label
                  htmlFor="child-edit-full-name"
                  className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400"
                >
                  Full Name
                </label>
                <input
                  id="child-edit-full-name"
                  type="text"
                  value={editChildName}
                  onChange={(e) => setEditChildName(e.target.value)}
                  className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                />
              </div>
              <div>
                <label
                  htmlFor="child-edit-date-of-birth"
                  className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400"
                >
                  Date of Birth
                </label>
                <input
                  id="child-edit-date-of-birth"
                  type="date"
                  value={editChildDob}
                  onChange={(e) => setEditChildDob(e.target.value)}
                  className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                />
              </div>
              <div>
                <label
                  htmlFor="child-edit-school"
                  className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400"
                >
                  School (optional)
                </label>
                <input
                  id="child-edit-school"
                  type="text"
                  value={editChildSchool}
                  onChange={(e) => setEditChildSchool(e.target.value)}
                  className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                />
              </div>
              <div className="sm:col-span-2">
                <label
                  htmlFor="child-edit-medical-notes"
                  className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400"
                >
                  Medical Notes (optional)
                </label>
                <textarea
                  id="child-edit-medical-notes"
                  value={editChildMedicalNotes}
                  onChange={(e) => setEditChildMedicalNotes(e.target.value)}
                  rows={3}
                  className="w-full resize-none rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                />
              </div>
            </div>

            <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:justify-end">
              <button
                type="button"
                onClick={closeEditor}
                className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-700 hover:border-slate-300 dark:border-slate-700 dark:text-slate-200"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSaveChild}
                disabled={!editChildName.trim() || !editChildDob}
                className="rounded-lg bg-teal-600 px-4 py-2 text-sm font-semibold text-white shadow-md shadow-teal-500/20 transition hover:bg-teal-700 disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
              >
                Save changes
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Decorative background */}
      <div
        className="pointer-events-none fixed inset-0 opacity-[0.015] dark:opacity-[0.025]"
        style={{
          backgroundImage: 'radial-gradient(circle at 2px 2px, currentColor 1px, transparent 0)',
          backgroundSize: '32px 32px',
        }}
      />

      <div className="relative mx-auto max-w-6xl px-4 py-8 sm:px-6 sm:py-12 lg:px-8">
        {/* Header */}
        <div className="mb-10">
          <div className="mb-3 flex items-center gap-3">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-teal-500 to-teal-600 shadow-lg shadow-teal-500/30">
              <svg
                className="h-6 w-6 text-white"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={1.5}
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="m2.25 12 8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25"
                />
              </svg>
            </div>
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-400">
                Family Setup
              </p>
              <h1 className="text-3xl font-bold tracking-tight text-slate-900 sm:text-4xl dark:text-white">
                {activeFamily.name}
              </h1>
            </div>
          </div>
          <p className="max-w-2xl text-slate-500 dark:text-slate-400">
            Manage your family details, child profiles, co-parent invitations, and role assignments
            all in one place.
          </p>
        </div>

        {/* Stats Overview */}
        <div className="mb-8 grid grid-cols-2 gap-3 sm:grid-cols-4 sm:gap-4">
          <div className="rounded-xl border border-slate-200/60 bg-white/70 p-4 backdrop-blur-sm dark:border-slate-700/60 dark:bg-slate-800/50">
            <p className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
              Parents
            </p>
            <p className="mt-2 text-2xl font-bold text-slate-900 dark:text-white">
              {familyParents.length}
            </p>
          </div>
          <div className="rounded-xl border border-slate-200/60 bg-white/70 p-4 backdrop-blur-sm dark:border-slate-700/60 dark:bg-slate-800/50">
            <p className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
              Children
            </p>
            <p className="mt-2 text-2xl font-bold text-slate-900 dark:text-white">
              {familyChildren.length}
            </p>
          </div>
          <div className="rounded-xl border border-slate-200/60 bg-white/70 p-4 backdrop-blur-sm dark:border-slate-700/60 dark:bg-slate-800/50">
            <p className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
              Pending Invites
            </p>
            <p className="mt-2 text-2xl font-bold text-amber-600 dark:text-amber-400">
              {pendingInvites.length}
            </p>
          </div>
          <div className="rounded-xl border border-slate-200/60 bg-white/70 p-4 backdrop-blur-sm dark:border-slate-700/60 dark:bg-slate-800/50">
            <p className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
              Time Zone
            </p>
            <p className="mt-2 text-sm font-semibold text-slate-700 dark:text-slate-300">
              {activeFamily.timeZone.split('/')[1]?.replace('_', ' ') || activeFamily.timeZone}
            </p>
          </div>
        </div>

        {/* Main Grid */}
        <div className="grid gap-6 lg:grid-cols-2">
          {/* Family Card */}
          <div className="overflow-hidden rounded-2xl border border-slate-200/60 bg-white/80 shadow-xl shadow-slate-200/40 backdrop-blur-xl dark:border-slate-700/60 dark:bg-slate-900/60 dark:shadow-slate-950/50">
            <div className="border-b border-slate-200/60 p-6 dark:border-slate-700/60">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-teal-500/10 dark:bg-teal-500/20">
                    <svg
                      className="h-5 w-5 text-teal-600 dark:text-teal-400"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={1.5}
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="m2.25 12 8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25"
                      />
                    </svg>
                  </div>
                  <h2 className="text-lg font-bold text-slate-900 dark:text-white">
                    Family Details
                  </h2>
                </div>
                <button
                  type="button"
                  onClick={() => onUpdateFamily?.(activeFamily.id, {})}
                  className="text-sm font-medium text-teal-600 transition hover:text-teal-700 dark:text-teal-400 dark:hover:text-teal-300"
                >
                  Edit
                </button>
              </div>
            </div>
            <div className="space-y-4 p-6">
              <div>
                <p className="mb-1 text-xs uppercase tracking-wide text-slate-400">Family Name</p>
                <p className="text-base font-semibold text-slate-900 dark:text-white">
                  {activeFamily.name}
                </p>
              </div>
              <div>
                <p className="mb-1 text-xs uppercase tracking-wide text-slate-400">Time Zone</p>
                <p className="text-base font-medium text-slate-700 dark:text-slate-300">
                  {activeFamily.timeZone}
                </p>
              </div>
              <div>
                <p className="mb-1 text-xs uppercase tracking-wide text-slate-400">Created</p>
                <p className="text-base font-medium text-slate-700 dark:text-slate-300">
                  {formatDate(activeFamily.createdAt)}
                </p>
              </div>
            </div>
          </div>

          {/* Parents & Roles Card */}
          <div className="overflow-hidden rounded-2xl border border-slate-200/60 bg-white/80 shadow-xl shadow-slate-200/40 backdrop-blur-xl dark:border-slate-700/60 dark:bg-slate-900/60 dark:shadow-slate-950/50">
            <div className="border-b border-slate-200/60 p-6 dark:border-slate-700/60">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-violet-500/10 dark:bg-violet-500/20">
                  <svg
                    className="h-5 w-5 text-violet-600 dark:text-violet-400"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={1.5}
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Zm8.25 2.25a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
                    />
                  </svg>
                </div>
                <h2 className="text-lg font-bold text-slate-900 dark:text-white">
                  Parents & Roles
                </h2>
              </div>
            </div>
            <div className="p-6">
              {familyParents.length > 0 ? (
                <div className="space-y-3">
                  {familyParents.map((parent) => {
                    const roleBadge = getRoleBadge(parent.role);
                    return (
                      <div
                        key={parent.id}
                        className="flex items-center justify-between rounded-xl border border-slate-200/60 bg-slate-50 p-4 dark:border-slate-700/60 dark:bg-slate-800/60"
                      >
                        <div className="flex items-center gap-3">
                          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-teal-400 to-teal-500 font-bold text-white">
                            {parent.fullName.charAt(0)}
                          </div>
                          <div>
                            <p className="font-semibold text-slate-900 dark:text-white">
                              {parent.fullName}
                            </p>
                            <p className="text-xs text-slate-500 dark:text-slate-400">
                              {parent.email}
                            </p>
                          </div>
                        </div>
                        <div className="flex items-center gap-2">
                          <span
                            className={`rounded-full px-3 py-1 text-xs font-semibold ${roleBadge.bg} ${roleBadge.text}`}
                          >
                            {roleBadge.label}
                          </span>
                          <button
                            type="button"
                            onClick={() =>
                              onAssignRole?.(
                                parent.id,
                                parent.role === 'primary' ? 'co-parent' : 'primary',
                              )
                            }
                            className="text-slate-400 transition hover:text-slate-600 dark:hover:text-slate-300"
                          >
                            <svg
                              className="h-4 w-4"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                              strokeWidth={2}
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L6.832 19.82a4.5 4.5 0 0 1-1.897 1.13l-2.685.8.8-2.685a4.5 4.5 0 0 1 1.13-1.897L16.863 4.487Zm0 0L19.5 7.125"
                              />
                            </svg>
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="py-8 text-center">
                  <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-slate-100 dark:bg-slate-800">
                    <svg
                      className="h-8 w-8 text-slate-400"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={1.5}
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Zm8.25 2.25a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z"
                      />
                    </svg>
                  </div>
                  <p className="text-sm text-slate-500 dark:text-slate-400">
                    No parents assigned yet
                  </p>
                </div>
              )}
            </div>
          </div>

          {/* Children Card */}
          <div className="overflow-hidden rounded-2xl border border-slate-200/60 bg-white/80 shadow-xl shadow-slate-200/40 backdrop-blur-xl dark:border-slate-700/60 dark:bg-slate-900/60 dark:shadow-slate-950/50">
            <div className="border-b border-slate-200/60 p-6 dark:border-slate-700/60">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-amber-500/10 dark:bg-amber-500/20">
                  <svg
                    className="h-5 w-5 text-amber-600 dark:text-amber-400"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={1.5}
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M15.182 15.182a4.5 4.5 0 0 1-6.364 0M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM9.75 9.75c0 .414-.168.75-.375.75S9 10.164 9 9.75 9.168 9 9.375 9s.375.336.375.75Zm-.375 0h.008v.015h-.008V9.75Zm5.625 0c0 .414-.168.75-.375.75s-.375-.336-.375-.75.168-.75.375-.75.375.336.375.75Zm-.375 0h.008v.015h-.008V9.75Z"
                    />
                  </svg>
                </div>
                <h2 className="text-lg font-bold text-slate-900 dark:text-white">Children</h2>
              </div>
            </div>
            <div className="p-6">
              <div className="mb-6 rounded-xl border border-slate-200/60 bg-white/60 p-4 dark:border-slate-700/60 dark:bg-slate-800/40">
                <div className="grid gap-3 sm:grid-cols-2">
                  <div className="sm:col-span-2">
                    <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                      Child Name
                    </label>
                    <input
                      type="text"
                      value={childName}
                      onChange={(e) => setChildName(e.target.value)}
                      placeholder="First and last name"
                      className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-500 focus:outline-none focus:ring-2 focus:ring-amber-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                      Date of Birth
                    </label>
                    <input
                      type="date"
                      value={childDob}
                      onChange={(e) => setChildDob(e.target.value)}
                      className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:border-amber-500 focus:outline-none focus:ring-2 focus:ring-amber-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                      School (optional)
                    </label>
                    <input
                      type="text"
                      value={childSchool}
                      onChange={(e) => setChildSchool(e.target.value)}
                      placeholder="School name"
                      className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-500 focus:outline-none focus:ring-2 focus:ring-amber-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                      Medical Notes (optional)
                    </label>
                    <textarea
                      value={childMedicalNotes}
                      onChange={(e) => setChildMedicalNotes(e.target.value)}
                      placeholder="Allergies, medications, or notes"
                      rows={2}
                      className="w-full resize-none rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-amber-500 focus:outline-none focus:ring-2 focus:ring-amber-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                    />
                  </div>
                </div>
                <div className="mt-4 flex justify-end">
                  <button
                    type="button"
                    onClick={handleAddChild}
                    disabled={!childName.trim() || !childDob}
                    className="inline-flex items-center gap-2 rounded-lg bg-amber-600 px-4 py-2 text-sm font-semibold text-white shadow-md shadow-amber-500/20 transition-all hover:bg-amber-700 disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
                  >
                    <svg
                      className="h-4 w-4"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={2}
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                    </svg>
                    Add Child
                  </button>
                </div>
              </div>
              {familyChildren.length > 0 ? (
                <div className="space-y-3">
                  {familyChildren.map((child) => (
                    <div
                      key={child.id}
                      className="group rounded-xl border border-slate-200/60 bg-slate-50 p-4 transition hover:border-amber-200 dark:border-slate-700/60 dark:bg-slate-800/60 dark:hover:border-amber-800"
                    >
                      <div className="flex items-start justify-between">
                        <div className="flex flex-1 items-start gap-3">
                          <div className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-amber-400 to-amber-500 font-bold text-white">
                            {child.fullName.charAt(0)}
                          </div>
                          <div className="min-w-0 flex-1">
                            <div className="mb-1 flex items-center gap-2">
                              <p className="font-semibold text-slate-900 dark:text-white">
                                {child.fullName}
                              </p>
                              <span className="rounded-full bg-slate-200 px-2 py-0.5 text-xs text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                                {calculateAge(child.dateOfBirth)} yrs
                              </span>
                            </div>
                            <p className="mb-2 text-xs text-slate-500 dark:text-slate-400">
                              Born {formatDate(child.dateOfBirth)}
                            </p>
                            {child.school && (
                              <div className="mb-1 flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-400">
                                <svg
                                  className="h-3.5 w-3.5"
                                  fill="none"
                                  viewBox="0 0 24 24"
                                  stroke="currentColor"
                                  strokeWidth={2}
                                >
                                  <path
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    d="M4.26 10.147a60.438 60.438 0 0 0-.491 6.347A48.62 48.62 0 0 1 12 20.904a48.62 48.62 0 0 1 8.232-4.41 60.46 60.46 0 0 0-.491-6.347m-15.482 0a50.636 50.636 0 0 0-2.658-.813A59.906 59.906 0 0 1 12 3.493a59.903 59.903 0 0 1 10.399 5.84c-.896.248-1.783.52-2.658.814m-15.482 0A50.717 50.717 0 0 1 12 13.489a50.702 50.702 0 0 1 7.74-3.342M6.75 15a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Zm0 0v-3.675A55.378 55.378 0 0 1 12 8.443m-7.007 11.55A5.981 5.981 0 0 0 6.75 15.75v-1.5"
                                  />
                                </svg>
                                {child.school}
                              </div>
                            )}
                            {child.medicalNotes && (
                              <div className="mt-2 flex items-start gap-1.5 text-xs text-rose-600 dark:text-rose-400">
                                <svg
                                  className="mt-0.5 h-3.5 w-3.5 flex-shrink-0"
                                  fill="none"
                                  viewBox="0 0 24 24"
                                  stroke="currentColor"
                                  strokeWidth={2}
                                >
                                  <path
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z"
                                  />
                                </svg>
                                <span className="line-clamp-2">{child.medicalNotes}</span>
                              </div>
                            )}
                          </div>
                        </div>
                        <button
                          type="button"
                          onClick={() => openEditor(child)}
                          className="text-slate-400 opacity-0 transition hover:text-slate-600 group-hover:opacity-100 dark:hover:text-slate-300"
                        >
                          <svg
                            className="h-4 w-4"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                            strokeWidth={2}
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L6.832 19.82a4.5 4.5 0 0 1-1.897 1.13l-2.685.8.8-2.685a4.5 4.5 0 0 1 1.13-1.897L16.863 4.487Zm0 0L19.5 7.125"
                            />
                          </svg>
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="py-8 text-center">
                  <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-amber-100 dark:bg-amber-900/20">
                    <svg
                      className="h-8 w-8 text-amber-500"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={1.5}
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M15.182 15.182a4.5 4.5 0 0 1-6.364 0M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM9.75 9.75c0 .414-.168.75-.375.75S9 10.164 9 9.75 9.168 9 9.375 9s.375.336.375.75Zm-.375 0h.008v.015h-.008V9.75Zm5.625 0c0 .414-.168.75-.375.75s-.375-.336-.375-.75.168-.75.375-.75.375.336.375.75Zm-.375 0h.008v.015h-.008V9.75Z"
                      />
                    </svg>
                  </div>
                  <p className="mb-2 text-sm font-medium text-slate-700 dark:text-slate-300">
                    No children added yet
                  </p>
                  <p className="mb-4 text-xs text-slate-500 dark:text-slate-400">
                    Add child profiles to start organizing your co-parenting schedule
                  </p>
                  <button
                    type="button"
                    onClick={handleAddChild}
                    disabled={!childName.trim() || !childDob}
                    className="inline-flex items-center gap-2 rounded-lg bg-amber-600 px-4 py-2 text-sm font-medium text-white shadow-md shadow-amber-500/20 transition-all hover:bg-amber-700 hover:shadow-lg hover:shadow-amber-500/30 disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
                  >
                    <svg
                      className="h-4 w-4"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={2}
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                    </svg>
                    Add Your First Child
                  </button>
                </div>
              )}
            </div>
          </div>

          {/* Invitations Card */}
          <div className="overflow-hidden rounded-2xl border border-slate-200/60 bg-white/80 shadow-xl shadow-slate-200/40 backdrop-blur-xl dark:border-slate-700/60 dark:bg-slate-900/60 dark:shadow-slate-950/50">
            <div className="border-b border-slate-200/60 p-6 dark:border-slate-700/60">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-rose-500/10 dark:bg-rose-500/20">
                    <svg
                      className="h-5 w-5 text-rose-600 dark:text-rose-400"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={1.5}
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M21.75 6.75v10.5a2.25 2.25 0 0 1-2.25 2.25h-15a2.25 2.25 0 0 1-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25m19.5 0v.243a2.25 2.25 0 0 1-1.07 1.916l-7.5 4.615a2.25 2.25 0 0 1-2.36 0L3.32 8.91a2.25 2.25 0 0 1-1.07-1.916V6.75"
                      />
                    </svg>
                  </div>
                  <h2 className="text-lg font-bold text-slate-900 dark:text-white">
                    Co-Parent Invitations
                  </h2>
                </div>
                <button
                  type="button"
                  onClick={handleInvite}
                  disabled={!inviteEmail.trim()}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-rose-600 px-3 py-1.5 text-sm font-medium text-white shadow-md shadow-rose-500/20 transition-all hover:bg-rose-700 hover:shadow-lg hover:shadow-rose-500/30 disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
                >
                  <svg
                    className="h-4 w-4"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={2}
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                  </svg>
                  Invite
                </button>
              </div>
            </div>
            <div className="p-6">
              <div className="mb-6 rounded-xl border border-slate-200/60 bg-white/60 p-4 dark:border-slate-700/60 dark:bg-slate-800/40">
                <div className="grid gap-3 sm:grid-cols-3">
                  <div className="sm:col-span-2">
                    <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                      Email
                    </label>
                    <input
                      type="email"
                      value={inviteEmail}
                      onChange={(e) => setInviteEmail(e.target.value)}
                      placeholder="coparent@example.com"
                      className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-rose-500 focus:outline-none focus:ring-2 focus:ring-rose-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-400">
                      Role
                    </label>
                    <select
                      value={inviteRole}
                      onChange={(e) => setInviteRole(e.target.value as ParentRole)}
                      className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:border-rose-500 focus:outline-none focus:ring-2 focus:ring-rose-500/40 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                    >
                      <option value="co-parent">Co-Parent</option>
                      <option value="primary">Primary</option>
                    </select>
                  </div>
                </div>
                <div className="mt-4 flex justify-end">
                  <button
                    type="button"
                    onClick={handleInvite}
                    disabled={!inviteEmail.trim()}
                    className="inline-flex items-center gap-2 rounded-lg bg-rose-600 px-4 py-2 text-sm font-semibold text-white shadow-md shadow-rose-500/20 transition-all hover:bg-rose-700 disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
                  >
                    Send Invite
                  </button>
                </div>
              </div>
              {familyInvitations.length > 0 ? (
                <div className="space-y-3">
                  {familyInvitations.map((invite) => {
                    const statusBadge = getInvitationStatusBadge(invite.status);
                    const roleBadge = getRoleBadge(invite.role);
                    return (
                      <div
                        key={invite.id}
                        className="rounded-xl border border-slate-200/60 bg-slate-50 p-4 dark:border-slate-700/60 dark:bg-slate-800/60"
                      >
                        <div className="mb-3 flex items-start justify-between">
                          <div className="min-w-0 flex-1">
                            <p className="truncate font-medium text-slate-900 dark:text-white">
                              {invite.email}
                            </p>
                            <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                              Sent {formatDate(invite.sentAt)}
                            </p>
                          </div>
                          <div className="ml-3 flex flex-shrink-0 items-center gap-2">
                            <span
                              className={`rounded-full px-2.5 py-1 text-xs font-semibold ${statusBadge.bg} ${statusBadge.text}`}
                            >
                              {statusBadge.label}
                            </span>
                            <span
                              className={`rounded-full px-2.5 py-1 text-xs font-medium ${roleBadge.bg} ${roleBadge.text}`}
                            >
                              {roleBadge.label}
                            </span>
                          </div>
                        </div>
                        {invite.status === 'pending' && (
                          <div className="flex gap-2 border-t border-slate-200/60 pt-2 dark:border-slate-700/60">
                            <button
                              type="button"
                              onClick={() => onResendInvite?.(invite.id)}
                              className="flex-1 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-600 transition hover:bg-slate-50 dark:border-slate-600 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600"
                            >
                              Resend
                            </button>
                            <button
                              type="button"
                              onClick={() => onCancelInvite?.(invite.id)}
                              className="flex-1 rounded-lg border border-rose-200 bg-white px-3 py-1.5 text-xs font-medium text-rose-600 transition hover:bg-rose-50 dark:border-rose-800 dark:bg-slate-700 dark:text-rose-400 dark:hover:bg-rose-900/20"
                            >
                              Cancel
                            </button>
                          </div>
                        )}
                        {invite.status === 'accepted' && invite.acceptedAt && (
                          <p className="border-t border-slate-200/60 pt-2 text-xs text-emerald-600 dark:border-slate-700/60 dark:text-emerald-400">
                            Accepted on {formatDate(invite.acceptedAt)}
                          </p>
                        )}
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="py-8 text-center">
                  <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-rose-100 dark:bg-rose-900/20">
                    <svg
                      className="h-8 w-8 text-rose-500"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={1.5}
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M21.75 6.75v10.5a2.25 2.25 0 0 1-2.25 2.25h-15a2.25 2.25 0 0 1-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25m19.5 0v.243a2.25 2.25 0 0 1-1.07 1.916l-7.5 4.615a2.25 2.25 0 0 1-2.36 0L3.32 8.91a2.25 2.25 0 0 1-1.07-1.916V6.75"
                      />
                    </svg>
                  </div>
                  <p className="mb-2 text-sm font-medium text-slate-700 dark:text-slate-300">
                    No invitations sent
                  </p>
                  <p className="mb-4 text-xs text-slate-500 dark:text-slate-400">
                    Invite your co-parent to collaborate on schedules and shared information
                  </p>
                  <button
                    type="button"
                    onClick={handleInvite}
                    disabled={!inviteEmail.trim()}
                    className="inline-flex items-center gap-2 rounded-lg bg-rose-600 px-4 py-2 text-sm font-medium text-white shadow-md shadow-rose-500/20 transition-all hover:bg-rose-700 hover:shadow-lg hover:shadow-rose-500/30 disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
                  >
                    <svg
                      className="h-4 w-4"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={2}
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                    </svg>
                    Send First Invitation
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
