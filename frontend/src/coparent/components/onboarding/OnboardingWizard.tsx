import { useEffect, useState } from 'react';

import type { Family, Child, Invitation, OnboardingState, ParentRole } from '../../lib/api/client';
import { formatDate } from '../../lib/formatters/date';

type WizardStep = 'family' | 'child' | 'invite' | 'review';

type ChildDraft = {
  fullName: string;
  dateOfBirth: string;
  school?: string;
  medicalNotes?: string;
};

const getChildKey = (child: { fullName?: string; dateOfBirth?: string }) =>
  `${(child.fullName ?? '').trim().toLowerCase()}|${child.dateOfBirth ?? ''}`;

const LOCATION_OPTIONS = [
  { label: 'London, UK', timeZone: 'Europe/London' },
  { label: 'Edinburgh, UK', timeZone: 'Europe/London' },
  { label: 'Dublin, Ireland', timeZone: 'Europe/Dublin' },
  { label: 'Paris, France', timeZone: 'Europe/Paris' },
  { label: 'Berlin, Germany', timeZone: 'Europe/Berlin' },
  { label: 'Madrid, Spain', timeZone: 'Europe/Madrid' },
  { label: 'Rome, Italy', timeZone: 'Europe/Rome' },
  { label: 'Lisbon, Portugal', timeZone: 'Europe/Lisbon' },
  { label: 'New York, USA', timeZone: 'America/New_York' },
  { label: 'Chicago, USA', timeZone: 'America/Chicago' },
  { label: 'Denver, USA', timeZone: 'America/Denver' },
  { label: 'Los Angeles, USA', timeZone: 'America/Los_Angeles' },
  { label: 'Vancouver, Canada', timeZone: 'America/Vancouver' },
  { label: 'Toronto, Canada', timeZone: 'America/Toronto' },
  { label: 'Mexico City, Mexico', timeZone: 'America/Mexico_City' },
  { label: 'Sao Paulo, Brazil', timeZone: 'America/Sao_Paulo' },
  { label: 'Johannesburg, South Africa', timeZone: 'Africa/Johannesburg' },
  { label: 'Dubai, UAE', timeZone: 'Asia/Dubai' },
  { label: 'Mumbai, India', timeZone: 'Asia/Kolkata' },
  { label: 'Singapore, Singapore', timeZone: 'Asia/Singapore' },
  { label: 'Hong Kong, China', timeZone: 'Asia/Hong_Kong' },
  { label: 'Tokyo, Japan', timeZone: 'Asia/Tokyo' },
  { label: 'Sydney, Australia', timeZone: 'Australia/Sydney' },
  { label: 'Auckland, New Zealand', timeZone: 'Pacific/Auckland' },
];

const normalizeLocation = (value: string) => value.trim().toLowerCase();

const findLocationByLabel = (value: string) =>
  LOCATION_OPTIONS.find((option) => normalizeLocation(option.label) === normalizeLocation(value));

const findLocationByTimeZone = (value: string) =>
  LOCATION_OPTIONS.find((option) => option.timeZone === value);

const resolveInitialTimeZone = () => {
  if (typeof Intl === 'undefined') return 'UTC';
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
};

const getInvitationStatusBadge = (status: Invitation['status']) => {
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
};

const STEPS: { id: WizardStep; label: string; description: string }[] = [
  { id: 'family', label: 'Family', description: 'Name your family' },
  { id: 'child', label: 'Children', description: 'Add profiles' },
  { id: 'invite', label: 'Invite', description: 'Bring your co-parent' },
  { id: 'review', label: 'Review', description: 'Confirm setup' },
];

interface OnboardingWizardProps {
  families: Family[];
  children: Child[];
  invitations: Invitation[];
  onboardingStates: OnboardingState[];
  activeFamilyId?: string;
  onCreateFamily?: (name: string, timeZone: string, fullName: string) => void;
  onAddChild?: (child: ChildDraft) => Promise<void> | void;
  onInviteCoParent?: (familyId: string, email: string, role: ParentRole) => void;
  onResendInvite?: (invitationId: string) => void;
  onCompleteOnboarding?: (familyId: string) => void;
}

export function OnboardingWizard({
  families,
  children,
  invitations,
  onboardingStates,
  activeFamilyId,
  onCreateFamily,
  onAddChild,
  onInviteCoParent,
  onResendInvite,
  onCompleteOnboarding,
}: OnboardingWizardProps) {
  const activeOnboarding = onboardingStates.find((state) => state.familyId === activeFamilyId);
  const familyInvitations = invitations.filter((invite) => invite.familyId === activeFamilyId);
  const shouldForceInviteStep = activeOnboarding?.isComplete && familyInvitations.length === 0;
  const initialStep = shouldForceInviteStep
    ? 'invite'
    : STEPS.some((step) => step.id === activeOnboarding?.currentStep)
      ? (activeOnboarding?.currentStep as WizardStep)
      : 'family';

  const [currentStep, setCurrentStep] = useState<WizardStep>(initialStep);
  const [fullName, setFullName] = useState('');
  const [familyName, setFamilyName] = useState('');
  const initialTimeZone = resolveInitialTimeZone();
  const [timeZone, setTimeZone] = useState(initialTimeZone);
  const [location, setLocation] = useState(
    () => findLocationByTimeZone(initialTimeZone)?.label ?? '',
  );
  const [childName, setChildName] = useState('');
  const [childDob, setChildDob] = useState('');
  const [childSchool, setChildSchool] = useState('');
  const [childMedicalNotes, setChildMedicalNotes] = useState('');
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<ParentRole>('co-parent');
  const [addedChildren, setAddedChildren] = useState<Partial<Child>[]>([]);
  const [familyError, setFamilyError] = useState<string | null>(null);

  useEffect(() => {
    if (shouldForceInviteStep) {
      setCurrentStep('invite');
      return;
    }
    if (!activeOnboarding?.currentStep) return;
    if (!STEPS.some((step) => step.id === activeOnboarding.currentStep)) return;
    setCurrentStep(activeOnboarding.currentStep as WizardStep);
  }, [activeOnboarding?.currentStep, shouldForceInviteStep]);

  const currentStepIndex = STEPS.findIndex((step) => step.id === currentStep);
  const completedSteps = activeOnboarding?.completedSteps || [];

  const goNext = () => {
    const nextIndex = currentStepIndex + 1;
    const nextStep = STEPS[nextIndex];
    if (nextStep) {
      setCurrentStep(nextStep.id);
    }
  };

  const goBack = () => {
    const prevIndex = currentStepIndex - 1;
    const prevStep = STEPS[prevIndex];
    if (prevStep) {
      setCurrentStep(prevStep.id);
    }
  };

  const handleCreateFamily = async () => {
    if (!onCreateFamily) {
      goNext();
      return;
    }

    try {
      setFamilyError(null);
      await onCreateFamily(familyName, timeZone, fullName);
      goNext();
    } catch (error) {
      console.error('Failed to create family:', error);
      setFamilyError('We could not create your family. Please try again.');
    }
  };

  const handleAddChild = async () => {
    const trimmedName = childName.trim();
    if (!trimmedName || !childDob) return;

    const newChild: ChildDraft = {
      fullName: trimmedName,
      dateOfBirth: childDob,
      school: childSchool || undefined,
      medicalNotes: childMedicalNotes || undefined,
    };
    const newChildKey = getChildKey(newChild);
    const alreadyAdded = [...familyChildren, ...addedChildren].some(
      (child) => getChildKey(child) === newChildKey,
    );

    if (!alreadyAdded) {
      await onAddChild?.(newChild);
      setAddedChildren((prev) => [...prev, newChild]);
    }

    setChildName('');
    setChildDob('');
    setChildSchool('');
    setChildMedicalNotes('');
  };

  const handleInvite = () => {
    if (activeFamilyId && inviteEmail) {
      onInviteCoParent?.(activeFamilyId, inviteEmail, inviteRole);
    }
    goNext();
  };

  const handleComplete = () => {
    if (activeFamilyId) {
      onCompleteOnboarding?.(activeFamilyId);
    }
  };

  const handleLocationChange = (value: string) => {
    setLocation(value);
    const matchedLocation = findLocationByLabel(value);
    if (matchedLocation) {
      setTimeZone(matchedLocation.timeZone);
    }
  };

  const selectedLocation = findLocationByLabel(location) ?? findLocationByTimeZone(timeZone);

  const activeFamily = families.find((f) => f.id === activeFamilyId);
  const familyChildren = children.filter((c) => c.familyId === activeFamilyId);
  const mergedChildren = (() => {
    const seen = new Set<string>();
    const merged: Partial<Child>[] = [];
    [...familyChildren, ...addedChildren].forEach((child) => {
      const key = getChildKey(child);
      if (!key || seen.has(key)) return;
      seen.add(key);
      merged.push(child);
    });
    return merged;
  })();
  const pendingInvites = familyInvitations.filter((i) => i.status === 'pending');

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-white to-teal-50/30 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
      {/* Decorative background pattern */}
      <div
        className="pointer-events-none fixed inset-0 opacity-[0.015] dark:opacity-[0.025]"
        style={{
          backgroundImage: 'radial-gradient(circle at 2px 2px, currentColor 1px, transparent 0)',
          backgroundSize: '32px 32px',
        }}
      />

      {/* Floating decorative shapes */}
      <div className="pointer-events-none fixed right-10 top-20 h-64 w-64 rounded-full bg-teal-400/10 blur-3xl dark:bg-teal-500/5" />
      <div className="pointer-events-none fixed bottom-20 left-10 h-96 w-96 rounded-full bg-rose-400/10 blur-3xl dark:bg-rose-500/5" />

      <div className="relative mx-auto max-w-3xl px-4 py-12 sm:px-6 lg:px-8">
        {/* Header */}
        <div className="mb-12 text-center">
          <div className="mb-6 inline-flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-teal-500 to-teal-600 shadow-xl shadow-teal-500/30">
            <svg
              className="h-8 w-8 text-white"
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
          <h1 className="text-3xl font-bold tracking-tight text-slate-900 sm:text-4xl dark:text-white">
            Welcome to CoParent
          </h1>
          <p className="mx-auto mt-3 max-w-lg text-lg text-slate-500 dark:text-slate-400">
            Let's set up your family in just a few steps
          </p>
        </div>

        {/* Stepper */}
        <div className="mb-12">
          <div className="flex items-center justify-between">
            {STEPS.map((step, index) => {
              const isCompleted = completedSteps.includes(step.id) || index < currentStepIndex;
              const isCurrent = step.id === currentStep;

              return (
                <div key={step.id} className="flex flex-1 items-center">
                  <div className="flex flex-1 flex-col items-center">
                    <div
                      className={`relative flex h-12 w-12 items-center justify-center rounded-2xl transition-all duration-300 ease-out ${
                        isCompleted
                          ? 'bg-teal-500 text-white shadow-lg shadow-teal-500/30'
                          : isCurrent
                            ? 'bg-white text-teal-600 shadow-xl shadow-slate-200/50 ring-2 ring-teal-500 dark:bg-slate-800 dark:text-teal-400 dark:shadow-slate-900/50'
                            : 'bg-slate-100 text-slate-400 dark:bg-slate-800/60 dark:text-slate-500'
                      } `}
                    >
                      {isCompleted ? (
                        <svg
                          className="h-5 w-5"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                          strokeWidth={2.5}
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            d="M4.5 12.75l6 6 9-13.5"
                          />
                        </svg>
                      ) : (
                        <span className="text-sm font-bold">{index + 1}</span>
                      )}
                      {isCurrent && (
                        <span className="absolute -inset-1 animate-pulse rounded-2xl bg-teal-500/20" />
                      )}
                    </div>
                    <div className="mt-3 text-center">
                      <p
                        className={`text-sm font-semibold ${
                          isCurrent
                            ? 'text-teal-600 dark:text-teal-400'
                            : isCompleted
                              ? 'text-slate-700 dark:text-slate-300'
                              : 'text-slate-400 dark:text-slate-500'
                        }`}
                      >
                        {step.label}
                      </p>
                      <p className="mt-0.5 hidden text-xs text-slate-400 sm:block dark:text-slate-500">
                        {step.description}
                      </p>
                    </div>
                  </div>
                  {index < STEPS.length - 1 && (
                    <div
                      className={`mx-2 mt-[-24px] h-0.5 flex-1 rounded-full transition-colors ${
                        isCompleted ? 'bg-teal-500' : 'bg-slate-200 dark:bg-slate-700'
                      }`}
                    />
                  )}
                </div>
              );
            })}
          </div>
        </div>

        {/* Step Content Card */}
        <div className="overflow-hidden rounded-3xl border border-slate-200/60 bg-white/80 shadow-2xl shadow-slate-200/40 backdrop-blur-xl dark:border-slate-700/60 dark:bg-slate-900/60 dark:shadow-slate-950/50">
          {/* Family Step */}
          {currentStep === 'family' && (
            <div className="p-8 sm:p-10">
              <div className="mb-8 flex items-center gap-4">
                <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-rose-500/10 dark:bg-rose-500/20">
                  <svg
                    className="h-6 w-6 text-rose-600 dark:text-rose-400"
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
                  <h2 className="text-2xl font-bold text-slate-900 dark:text-white">
                    Name Your Family
                  </h2>
                  <p className="text-slate-500 dark:text-slate-400">
                    This helps identify your co-parenting unit
                  </p>
                </div>
              </div>

              <div className="space-y-6">
                <div>
                  <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-300">
                    Your Full Name
                  </label>
                  <input
                    type="text"
                    value={fullName}
                    onChange={(e) => setFullName(e.target.value)}
                    placeholder="Enter your full name"
                    className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3.5 text-slate-900 transition placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                  />
                  <p className="mt-2 text-xs text-slate-400">
                    This will be shown to your co-parent
                  </p>
                </div>
                <div>
                  <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-300">
                    Family Name
                  </label>
                  <input
                    type="text"
                    value={familyName}
                    onChange={(e) => setFamilyName(e.target.value)}
                    placeholder="e.g., The Kingston Family"
                    className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3.5 text-slate-900 transition placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                  />
                  <p className="mt-2 text-xs text-slate-400">
                    This is visible to both parents in the family
                  </p>
                </div>
                <div>
                  <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-300">
                    Location
                  </label>
                  <input
                    type="text"
                    list="location-options"
                    value={location}
                    onChange={(e) => handleLocationChange(e.target.value)}
                    placeholder="Start typing a city (e.g., London)"
                    className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3.5 text-slate-900 transition placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                  />
                  <datalist id="location-options">
                    {LOCATION_OPTIONS.map((option) => (
                      <option key={option.label} value={option.label}>
                        {option.label}
                      </option>
                    ))}
                  </datalist>
                  <p className="mt-2 text-xs text-slate-400">
                    {selectedLocation
                      ? `Time zone inferred: ${selectedLocation.timeZone}`
                      : 'Select a location to infer your time zone.'}
                  </p>
                  {familyError ? <p className="mt-2 text-xs text-rose-600">{familyError}</p> : null}
                </div>

                <div className="rounded-xl border border-teal-200/60 bg-teal-50 p-4 dark:border-teal-800/40 dark:bg-teal-900/20">
                  <div className="flex gap-3">
                    <svg
                      className="mt-0.5 h-5 w-5 flex-shrink-0 text-teal-600 dark:text-teal-400"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth={1.5}
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="m11.25 11.25.041-.02a.75.75 0 0 1 1.063.852l-.708 2.836a.75.75 0 0 0 1.063.853l.041-.021M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z"
                      />
                    </svg>
                    <div>
                      <p className="text-sm font-medium text-teal-800 dark:text-teal-200">
                        Why a family name?
                      </p>
                      <p className="mt-1 text-sm text-teal-700 dark:text-teal-300">
                        The family name appears in your dashboard and helps organize shared
                        schedules, expenses, and documents.
                      </p>
                    </div>
                  </div>
                </div>
              </div>

              <div className="mt-10 flex justify-end">
                <button
                  type="button"
                  onClick={handleCreateFamily}
                  disabled={!familyName.trim() || !fullName.trim()}
                  className="inline-flex items-center gap-2 rounded-xl bg-teal-600 px-8 py-3.5 font-semibold text-white shadow-lg shadow-teal-500/25 transition-all duration-200 hover:-translate-y-0.5 hover:bg-teal-700 hover:shadow-xl hover:shadow-teal-500/30 active:translate-y-0 disabled:translate-y-0 disabled:cursor-not-allowed disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
                >
                  Continue
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
                      d="M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3"
                    />
                  </svg>
                </button>
              </div>
            </div>
          )}

          {/* Child Step */}
          {currentStep === 'child' && (
            <div className="p-8 sm:p-10">
              <div className="mb-8 flex items-center gap-4">
                <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-amber-500/10 dark:bg-amber-500/20">
                  <svg
                    className="h-6 w-6 text-amber-600 dark:text-amber-400"
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
                <div>
                  <h2 className="text-2xl font-bold text-slate-900 dark:text-white">
                    Add Your Children
                  </h2>
                  <p className="text-slate-500 dark:text-slate-400">
                    Create profiles for each child you're co-parenting
                  </p>
                </div>
              </div>

              {/* Added children list */}
              {mergedChildren.length > 0 && (
                <div className="mb-6">
                  <p className="mb-3 text-sm font-semibold text-slate-700 dark:text-slate-300">
                    Children added ({mergedChildren.length})
                  </p>
                  <div className="space-y-2">
                    {mergedChildren.map((child, index) => (
                      <div
                        key={child.id || index}
                        className="flex items-center gap-3 rounded-xl border border-slate-200/60 bg-slate-50 p-3 dark:border-slate-700/60 dark:bg-slate-800/60"
                      >
                        <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-amber-400 to-amber-500 text-sm font-bold text-white">
                          {child.fullName?.charAt(0)}
                        </div>
                        <div>
                          <p className="font-medium text-slate-900 dark:text-white">
                            {child.fullName}
                          </p>
                          <p className="text-xs text-slate-500 dark:text-slate-400">
                            Born {formatDate(child.dateOfBirth)}
                          </p>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div className="space-y-5">
                <div className="grid gap-4 sm:grid-cols-2">
                  <div>
                    <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-300">
                      Child's Full Name <span className="text-rose-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={childName}
                      onChange={(e) => setChildName(e.target.value)}
                      placeholder="First and last name"
                      className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-slate-900 transition placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                    />
                  </div>
                  <div>
                    <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-300">
                      Date of Birth <span className="text-rose-500">*</span>
                    </label>
                    <input
                      type="date"
                      value={childDob}
                      onChange={(e) => setChildDob(e.target.value)}
                      className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-slate-900 transition focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                    />
                  </div>
                </div>
                <div>
                  <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-300">
                    School <span className="font-normal text-slate-400">(optional)</span>
                  </label>
                  <input
                    type="text"
                    value={childSchool}
                    onChange={(e) => setChildSchool(e.target.value)}
                    placeholder="e.g., Willow Creek Elementary"
                    className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-slate-900 transition placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                  />
                </div>
                <div>
                  <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-300">
                    Medical Notes <span className="font-normal text-slate-400">(optional)</span>
                  </label>
                  <textarea
                    value={childMedicalNotes}
                    onChange={(e) => setChildMedicalNotes(e.target.value)}
                    placeholder="Allergies, medications, or special requirements"
                    rows={3}
                    className="w-full resize-none rounded-xl border border-slate-200 bg-white px-4 py-3 text-slate-900 transition placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                  />
                </div>
                <button
                  type="button"
                  onClick={handleAddChild}
                  disabled={!childName.trim() || !childDob}
                  className="inline-flex items-center gap-2 rounded-xl bg-slate-900 px-5 py-2.5 font-medium text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400 dark:bg-white dark:text-slate-900 dark:hover:bg-slate-100 dark:disabled:bg-slate-700 dark:disabled:text-slate-500"
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

              <div className="mt-10 flex justify-between">
                <button
                  type="button"
                  onClick={goBack}
                  className="inline-flex items-center gap-2 rounded-xl px-6 py-3 font-medium text-slate-600 transition hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
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
                      d="M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18"
                    />
                  </svg>
                  Back
                </button>
                <button
                  type="button"
                  onClick={async () => {
                    if (childName.trim() && childDob) {
                      await handleAddChild();
                    }
                    goNext();
                  }}
                  disabled={mergedChildren.length === 0 && (!childName.trim() || !childDob)}
                  className="inline-flex items-center gap-2 rounded-xl bg-teal-600 px-8 py-3.5 font-semibold text-white shadow-lg shadow-teal-500/25 transition-all duration-200 hover:-translate-y-0.5 hover:bg-teal-700 hover:shadow-xl hover:shadow-teal-500/30 active:translate-y-0 disabled:translate-y-0 disabled:cursor-not-allowed disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
                >
                  Continue
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
                      d="M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3"
                    />
                  </svg>
                </button>
              </div>
            </div>
          )}

          {/* Invite Step */}
          {currentStep === 'invite' && (
            <div className="p-8 sm:p-10">
              <div className="mb-8 flex items-center gap-4">
                <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-violet-500/10 dark:bg-violet-500/20">
                  <svg
                    className="h-6 w-6 text-violet-600 dark:text-violet-400"
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
                <div>
                  <h2 className="text-2xl font-bold text-slate-900 dark:text-white">
                    Invite Your Co-Parent
                  </h2>
                  <p className="text-slate-500 dark:text-slate-400">
                    Send an invitation to collaborate
                  </p>
                </div>
              </div>

              {familyInvitations.length > 0 && (
                <div className="mb-6 rounded-xl border border-violet-200/60 bg-violet-50 p-4 dark:border-violet-800/40 dark:bg-violet-900/20">
                  <p className="mb-3 text-sm font-medium text-violet-800 dark:text-violet-200">
                    Invitations
                  </p>
                  <div className="space-y-2">
                    {familyInvitations.map((invite) => {
                      const statusBadge = getInvitationStatusBadge(invite.status);
                      const canResend =
                        !!onResendInvite &&
                        (invite.status === 'pending' || invite.status === 'expired');
                      return (
                        <div
                          key={invite.id}
                          className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-white/70 px-3 py-2 dark:bg-violet-900/30"
                        >
                          <span className="text-sm text-violet-700 dark:text-violet-200">
                            {invite.email}
                          </span>
                          <div className="flex items-center gap-2">
                            <span
                              className={`rounded-full px-2 py-1 text-xs font-medium ${statusBadge.bg} ${statusBadge.text}`}
                            >
                              {statusBadge.label}
                            </span>
                            {canResend && (
                              <button
                                type="button"
                                onClick={() => onResendInvite?.(invite.id)}
                                className="rounded-lg border border-violet-200 px-3 py-1 text-xs font-semibold text-violet-700 transition hover:bg-violet-100 dark:border-violet-700 dark:text-violet-200 dark:hover:bg-violet-800"
                              >
                                Resend
                              </button>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              <div className="space-y-5">
                <div>
                  <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-300">
                    Co-Parent Email
                  </label>
                  <input
                    type="email"
                    value={inviteEmail}
                    onChange={(e) => setInviteEmail(e.target.value)}
                    placeholder="coparent@example.com"
                    className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-slate-900 transition placeholder:text-slate-400 focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                  />
                </div>
                <div>
                  <label className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-300">
                    Role
                  </label>
                  <select
                    value={inviteRole}
                    onChange={(e) => setInviteRole(e.target.value as ParentRole)}
                    className="w-full appearance-none rounded-xl border border-slate-200 bg-white px-4 py-3 text-slate-900 transition focus:border-teal-500 focus:outline-none focus:ring-2 focus:ring-teal-500/40 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                  >
                    <option value="co-parent">Co-Parent</option>
                    <option value="primary">Primary</option>
                  </select>
                </div>
              </div>

              <div className="mt-10 flex justify-between">
                <button
                  type="button"
                  onClick={goBack}
                  className="inline-flex items-center gap-2 rounded-xl px-6 py-3 font-medium text-slate-600 transition hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
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
                      d="M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18"
                    />
                  </svg>
                  Back
                </button>
                <div className="flex gap-3">
                  <button
                    type="button"
                    onClick={goNext}
                    className="inline-flex items-center gap-2 rounded-xl px-6 py-3 font-medium text-slate-600 transition hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
                  >
                    Skip
                  </button>
                  <button
                    type="button"
                    onClick={handleInvite}
                    disabled={!inviteEmail.trim()}
                    className="inline-flex items-center gap-2 rounded-xl bg-teal-600 px-8 py-3.5 font-semibold text-white shadow-lg shadow-teal-500/25 transition-all duration-200 hover:-translate-y-0.5 hover:bg-teal-700 hover:shadow-xl hover:shadow-teal-500/30 active:translate-y-0 disabled:translate-y-0 disabled:cursor-not-allowed disabled:bg-slate-300 disabled:shadow-none dark:disabled:bg-slate-700"
                  >
                    Send Invite
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
                        d="M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3"
                      />
                    </svg>
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Review Step */}
          {currentStep === 'review' && (
            <div className="p-8 sm:p-10">
              <div className="mb-8 flex items-center gap-4">
                <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-500/10 dark:bg-emerald-500/20">
                  <svg
                    className="h-6 w-6 text-emerald-600 dark:text-emerald-400"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={1.5}
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z"
                    />
                  </svg>
                </div>
                <div>
                  <h2 className="text-2xl font-bold text-slate-900 dark:text-white">
                    Review & Complete
                  </h2>
                  <p className="text-slate-500 dark:text-slate-400">Confirm your setup details</p>
                </div>
              </div>

              <div className="space-y-6">
                <div className="rounded-2xl border border-slate-200/60 bg-slate-50 p-5 dark:border-slate-700/60 dark:bg-slate-800/60">
                  <h3 className="mb-2 text-sm font-semibold text-slate-900 dark:text-white">
                    Family
                  </h3>
                  <p className="text-slate-700 dark:text-slate-300">
                    {activeFamily?.name || familyName || 'Family name pending'}
                  </p>
                  <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                    {selectedLocation?.label ? `Location: ${selectedLocation.label} • ` : ''}
                    Time Zone: {activeFamily?.timeZone || timeZone}
                  </p>
                </div>

                <div className="rounded-2xl border border-slate-200/60 bg-slate-50 p-5 dark:border-slate-700/60 dark:bg-slate-800/60">
                  <h3 className="mb-2 text-sm font-semibold text-slate-900 dark:text-white">
                    Children
                  </h3>
                  <div className="space-y-2">
                    {mergedChildren.map((child, index) => (
                      <div key={child.id || index} className="text-slate-700 dark:text-slate-300">
                        {child.fullName}
                      </div>
                    ))}
                  </div>
                </div>

                <div className="rounded-2xl border border-slate-200/60 bg-slate-50 p-5 dark:border-slate-700/60 dark:bg-slate-800/60">
                  <h3 className="mb-2 text-sm font-semibold text-slate-900 dark:text-white">
                    Invitations
                  </h3>
                  {pendingInvites.length > 0 ? (
                    <div className="space-y-2">
                      {pendingInvites.map((invite) => (
                        <div key={invite.id} className="text-slate-700 dark:text-slate-300">
                          {invite.email} ({invite.role})
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="text-slate-500 dark:text-slate-400">No invitations sent</p>
                  )}
                </div>
              </div>

              <div className="mt-10 flex justify-between">
                <button
                  type="button"
                  onClick={goBack}
                  className="inline-flex items-center gap-2 rounded-xl px-6 py-3 font-medium text-slate-600 transition hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
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
                      d="M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18"
                    />
                  </svg>
                  Back
                </button>
                <button
                  type="button"
                  onClick={handleComplete}
                  className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-8 py-3.5 font-semibold text-white shadow-lg shadow-emerald-500/25 transition-all duration-200 hover:-translate-y-0.5 hover:bg-emerald-700 hover:shadow-xl hover:shadow-emerald-500/30 active:translate-y-0"
                >
                  Complete Setup
                  <svg
                    className="h-4 w-4"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={2}
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5" />
                  </svg>
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
