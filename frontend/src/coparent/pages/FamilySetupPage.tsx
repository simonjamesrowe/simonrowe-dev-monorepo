import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { FamilySetupHub } from '../components/family/FamilySetupHub';
import {
  useFamilies,
  useParents,
  useChildren,
  useInvitations,
  useCreateChild,
  useUpdateChild,
  useUpdateFamily,
  useCreateInvitation,
  useResendInvitation,
  useCancelInvitation,
  useUpdateParentRole,
} from '../hooks/api';

const FamilySetupPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const [activeFamilyId, setActiveFamilyId] = useState<string | undefined>();

  const { data: parents = [] } = useParents(activeFamilyId);
  const { data: children = [] } = useChildren(activeFamilyId);
  const { data: invitations = [] } = useInvitations(activeFamilyId);

  const createChild = useCreateChild();
  const updateChild = useUpdateChild();
  const updateFamily = useUpdateFamily();
  const createInvitation = useCreateInvitation();
  const resendInvitation = useResendInvitation();
  const cancelInvitation = useCancelInvitation();
  const updateParentRole = useUpdateParentRole();

  useEffect(() => {
    const [firstFamily] = families;
    if (!activeFamilyId && firstFamily) {
      setActiveFamilyId(firstFamily.id);
    }
  }, [activeFamilyId, families]);

  const childIdToEdit = useMemo(() => {
    const raw = searchParams.get('childId');
    return raw && raw.trim().length > 0 ? raw : undefined;
  }, [searchParams]);

  const handleAddChild = async (child: {
    fullName: string;
    dateOfBirth: string;
    school?: string;
    medicalNotes?: string;
  }) => {
    if (!activeFamilyId) return;
    await createChild.mutateAsync({ familyId: activeFamilyId, ...child });
  };

  const handleUpdateChild = async (
    childId: string,
    updates: Partial<{
      fullName: string;
      dateOfBirth: string;
      school?: string;
      medicalNotes?: string;
    }>,
  ) => {
    if (!updates || Object.keys(updates).length === 0) return;
    await updateChild.mutateAsync({ id: childId, ...updates });
    if (searchParams.get('childId') === childId) {
      setSearchParams({}, { replace: true });
    }
  };

  const handleUpdateFamily = async (
    id: string,
    updates: Partial<{ name: string; timeZone: string }>,
  ) => {
    if (!updates || Object.keys(updates).length === 0) return;
    await updateFamily.mutateAsync({ id, ...updates });
  };

  const handleInvite = async (familyId: string, email: string, role: 'primary' | 'co-parent') => {
    await createInvitation.mutateAsync({ familyId, email, role });
  };

  const handleResend = async (invitationId: string) => {
    if (!activeFamilyId) return;
    await resendInvitation.mutateAsync({ id: invitationId, familyId: activeFamilyId });
  };

  const handleCancel = async (invitationId: string) => {
    if (!activeFamilyId) return;
    await cancelInvitation.mutateAsync({ id: invitationId, familyId: activeFamilyId });
  };

  const handleAssignRole = async (parentId: string, role: 'primary' | 'co-parent') => {
    if (!activeFamilyId) return;
    await updateParentRole.mutateAsync({ id: parentId, familyId: activeFamilyId, role });
  };

  if (familiesLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 dark:bg-slate-900">
        <p className="text-slate-500 dark:text-slate-400">Loading family setup...</p>
      </div>
    );
  }

  return (
    <FamilySetupHub
      families={families}
      parents={parents}
      children={children}
      invitations={invitations}
      activeFamilyId={activeFamilyId}
      childIdToEdit={childIdToEdit}
      onCloseChildEditor={() => setSearchParams({}, { replace: true })}
      onUpdateFamily={handleUpdateFamily}
      onAddChild={handleAddChild}
      onUpdateChild={handleUpdateChild}
      onInviteCoParent={handleInvite}
      onResendInvite={handleResend}
      onCancelInvite={handleCancel}
      onAssignRole={handleAssignRole}
    />
  );
};

export default FamilySetupPage;
