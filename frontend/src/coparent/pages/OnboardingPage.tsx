import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { OnboardingWizard } from '../components/onboarding/OnboardingWizard';
import {
  useFamilies,
  useCreateFamily,
  useChildren,
  useCreateChild,
  useInvitations,
  useCreateInvitation,
  useResendInvitation,
  useOnboarding,
  useUpdateOnboarding,
} from '../hooks/api';

const OnboardingPage = () => {
  const navigate = useNavigate();
  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const [activeFamilyId, setActiveFamilyId] = useState<string | undefined>();

  const { data: children = [] } = useChildren(activeFamilyId);
  const { data: invitations = [] } = useInvitations(activeFamilyId);
  const { data: onboarding } = useOnboarding(activeFamilyId);

  const createFamily = useCreateFamily();
  const createChild = useCreateChild();
  const createInvitation = useCreateInvitation();
  const resendInvitation = useResendInvitation();
  const updateOnboarding = useUpdateOnboarding();

  useEffect(() => {
    const [firstFamily] = families;
    if (!activeFamilyId && firstFamily) {
      setActiveFamilyId(firstFamily.id);
    }
  }, [activeFamilyId, families]);

  const onboardingStates = onboarding ? [onboarding] : [];

  const handleCreateFamily = async (name: string, timeZone: string, fullName: string) => {
    const family = await createFamily.mutateAsync({ name, timeZone, fullName });
    setActiveFamilyId(family.id);
    try {
      await updateOnboarding.mutateAsync({
        familyId: family.id,
        currentStep: 'child',
        completedSteps: ['family'],
      });
    } catch (error) {
      console.error('Failed to update onboarding after family creation:', error);
    }
  };

  const handleAddChild = async (child: {
    fullName: string;
    dateOfBirth: string;
    school?: string;
    medicalNotes?: string;
  }) => {
    if (!activeFamilyId) return;
    await createChild.mutateAsync({ familyId: activeFamilyId, ...child });
    if (!onboarding?.completedSteps?.includes('child')) {
      await updateOnboarding.mutateAsync({
        familyId: activeFamilyId,
        currentStep: 'invite',
        completedSteps: Array.from(new Set([...(onboarding?.completedSteps ?? []), 'child'])),
      });
    }
  };

  const handleInvite = async (familyId: string, email: string, role: 'primary' | 'co-parent') => {
    await createInvitation.mutateAsync({ familyId, email, role });
    if (!onboarding?.completedSteps?.includes('invite')) {
      await updateOnboarding.mutateAsync({
        familyId,
        currentStep: 'review',
        completedSteps: Array.from(new Set([...(onboarding?.completedSteps ?? []), 'invite'])),
      });
    }
  };

  const handleResendInvite = async (invitationId: string) => {
    if (!activeFamilyId) return;
    await resendInvitation.mutateAsync({ id: invitationId, familyId: activeFamilyId });
  };

  const handleComplete = async (familyId: string) => {
    try {
      await updateOnboarding.mutateAsync({
        familyId,
        isComplete: true,
        currentStep: 'complete',
        completedSteps: Array.from(new Set([...(onboarding?.completedSteps ?? []), 'review'])),
      });
    } finally {
      // Navigate to dashboard after completing onboarding, even if the update fails.
      navigate('/dashboard', { replace: true });
    }
  };

  if (familiesLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 dark:bg-slate-900">
        <p className="text-slate-500 dark:text-slate-400">Loading onboarding...</p>
      </div>
    );
  }

  return (
    <OnboardingWizard
      families={families}
      children={children}
      invitations={invitations}
      onboardingStates={onboardingStates}
      activeFamilyId={activeFamilyId}
      onCreateFamily={handleCreateFamily}
      onAddChild={handleAddChild}
      onInviteCoParent={handleInvite}
      onResendInvite={handleResendInvite}
      onCompleteOnboarding={handleComplete}
    />
  );
};

export default OnboardingPage;
