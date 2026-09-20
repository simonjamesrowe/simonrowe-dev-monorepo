import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import { useFamilies, useInvitations, useOnboarding } from '../../hooks/api';

interface OnboardingGuardProps {
  children: React.ReactNode;
}

export function OnboardingGuard({ children }: OnboardingGuardProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const [activeFamilyId, setActiveFamilyId] = useState<string | undefined>();

  // Get onboarding state for the active family
  const { data: onboarding, isLoading: onboardingLoading } = useOnboarding(activeFamilyId);
  const { isLoading: invitationsLoading } = useInvitations(activeFamilyId);

  // Set active family to first family
  useEffect(() => {
    if (families.length > 0 && !activeFamilyId && families[0]) {
      setActiveFamilyId(families[0].id);
    }
  }, [families, activeFamilyId]);

  // Check onboarding status and redirect if needed
  useEffect(() => {
    // Don't redirect while loading
    if (familiesLoading || onboardingLoading || invitationsLoading) return;

    // Allow access to onboarding page itself
    if (location.pathname === '/onboarding') return;

    // If user has no families, redirect to onboarding
    if (families.length === 0) {
      navigate('/onboarding', { replace: true });
      return;
    }

    // If onboarding exists but is not complete, redirect to onboarding
    if (onboarding && !onboarding.isComplete) {
      navigate('/onboarding', { replace: true });
      return;
    }

    // If onboarding data doesn't exist yet for this family, redirect to onboarding
    if (!onboarding && activeFamilyId) {
      navigate('/onboarding', { replace: true });
      return;
    }
  }, [
    familiesLoading,
    onboardingLoading,
    invitationsLoading,
    families,
    onboarding,
    activeFamilyId,
    location.pathname,
    navigate,
  ]);

  // Show loading state while checking onboarding status
  if (familiesLoading || onboardingLoading || invitationsLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 via-white to-teal-50/30 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/20">
        <div className="text-center">
          <div className="mx-auto mb-4 flex h-16 w-16 animate-pulse items-center justify-center rounded-2xl bg-gradient-to-br from-teal-500 to-teal-600 shadow-xl shadow-teal-500/30">
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
          <p className="text-slate-500 dark:text-slate-400">Loading...</p>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
