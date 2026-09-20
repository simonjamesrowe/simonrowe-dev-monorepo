import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import {
  ChildrenDrawer,
  DashboardOverview,
  InvitationsDrawer,
  ProfileDrawer,
} from '../components/dashboard';
import { useToast } from '../components/ui/ToastProvider';
import {
  useCancelInvitation,
  useChildren,
  useConversations,
  useEvents,
  useFamilies,
  useInvitations,
  useParents,
  useResendInvitation,
  useUpdateCurrentUser,
  useCurrentUser,
} from '../hooks/api';
import type {
  ApprovalsSummary,
  BudgetSummary,
  Child as DashboardChild,
  Event as DashboardEvent,
  Family as DashboardFamily,
  Parent as DashboardParent,
  ParentProfileUpdate,
  PermissionRequest as DashboardPermissionRequest,
  QuickAction,
  SetupChecklist,
  WidgetCard,
  Invitation as DashboardInvitation,
  Message as DashboardMessage,
} from '../types/dashboard';

const splitName = (fullName: string) => {
  const parts = fullName.trim().split(/\s+/).filter(Boolean);
  const firstName = parts[0] ?? '';
  const lastName = parts.slice(1).join(' ');
  return { firstName, lastName };
};

const toDashboardRole = (role: string | undefined): DashboardParent['role'] => {
  if (role === 'primary') return 'primary';
  if (role === 'co-parent') return 'secondary';
  return 'caregiver';
};

const toDashboardEventType = (type: string): DashboardEvent['type'] => {
  if (type === 'custody' || type === 'school' || type === 'holiday') return type;
  if (type === 'medical' || type === 'appointment') return 'appointment';
  return 'activity';
};

const combineEventDateTime = (date: string, time: string | undefined, allDayEnd = false) => {
  const datePart = date.split('T')[0];
  const timePart = time || (allDayEnd ? '23:59:59' : '00:00:00');
  return `${datePart}T${timePart.length === 5 ? `${timePart}:00` : timePart}`;
};

const DashboardPage = () => {
  const navigate = useNavigate();
  const { showToast } = useToast();

  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const [activeFamilyId, setActiveFamilyId] = useState<string | undefined>();

  const { data: currentUser } = useCurrentUser();
  const { data: parents = [], isLoading: parentsLoading } = useParents(activeFamilyId);
  const { data: children = [], isLoading: childrenLoading } = useChildren(activeFamilyId);
  const { data: invitations = [], isLoading: invitationsLoading } = useInvitations(activeFamilyId);
  const { data: conversations = [], isLoading: conversationsLoading } =
    useConversations(activeFamilyId);
  const { data: calendarEvents = [], isLoading: eventsLoading } = useEvents(activeFamilyId);

  const updateCurrentUser = useUpdateCurrentUser();
  const resendInvitation = useResendInvitation();
  const cancelInvitation = useCancelInvitation();

  const [isProfileOpen, setIsProfileOpen] = useState(false);
  const [isChildrenOpen, setIsChildrenOpen] = useState(false);
  const [isInvitesOpen, setIsInvitesOpen] = useState(false);

  useEffect(() => {
    const [firstFamily] = families;
    if (!activeFamilyId && firstFamily) {
      setActiveFamilyId(firstFamily.id);
    }
  }, [activeFamilyId, families]);

  const family = useMemo(() => {
    const apiFamily = families.find((entry) => entry.id === activeFamilyId) ?? families[0];
    if (!apiFamily) return null;

    const primary = parents.find((p) => p.role === 'primary')?.id ?? parents[0]?.id ?? 'unknown';
    const secondary = parents.find((p) => p.role !== 'primary')?.id ?? parents[1]?.id ?? primary;

    const hasChild = children.length > 0;
    const hasInvite = invitations.length > 0;
    const progress = Math.min(1, (hasChild ? 0.5 : 0) + (hasInvite ? 0.5 : 0));

    const dashboardFamily: DashboardFamily = {
      id: apiFamily.id,
      name: apiFamily.name,
      timezone: apiFamily.timeZone,
      primaryParentId: primary,
      secondaryParentId: secondary,
      childIds: children.map((c) => c.id),
      createdAt: apiFamily.createdAt,
      setupProgress: progress,
    };

    return dashboardFamily;
  }, [activeFamilyId, children, families, invitations.length, parents]);

  const dashboardParents = useMemo<DashboardParent[]>(() => {
    return parents.map((parent) => ({
      id: parent.id,
      fullName: parent.fullName,
      email: parent.email ?? currentUser?.email ?? '',
      role: toDashboardRole(parent.role),
      phone: '',
      avatarUrl: parent.avatarUrl ?? null,
      lastActiveAt: parent.lastSignedInAt ?? new Date().toISOString(),
      notificationPreferences: { email: true, sms: false, push: true },
    }));
  }, [currentUser?.email, parents]);

  const dashboardChildren = useMemo<DashboardChild[]>(() => {
    return children.map((child) => {
      const { firstName, lastName } = splitName(child.fullName);
      return {
        id: child.id,
        firstName,
        lastName,
        birthdate: child.dateOfBirth,
        grade: '',
        school: child.school ?? '',
        avatarUrl: null,
        allergies: [],
        medicalNotes: child.medicalNotes ?? '',
      };
    });
  }, [children]);

  const dashboardInvitations = useMemo<DashboardInvitation[]>(() => {
    const inviterId = family?.primaryParentId ?? parents[0]?.id ?? 'unknown';
    return invitations.map((invitation) => ({
      id: invitation.id,
      email: invitation.email,
      role:
        invitation.role === 'primary' || invitation.role === 'co-parent'
          ? 'co-parent'
          : 'caregiver',
      status: invitation.status,
      sentAt: invitation.sentAt,
      expiresAt: invitation.expiresAt,
      invitedByParentId: inviterId,
    }));
  }, [family?.primaryParentId, invitations, parents]);

  const dashboardMessages = useMemo<DashboardMessage[]>(() => {
    const fromParentId = family?.primaryParentId ?? parents[0]?.id ?? 'unknown';
    const toParentId = family?.secondaryParentId ?? parents[1]?.id ?? fromParentId;

    return conversations.map((conversation) => ({
      id: conversation.id,
      threadId: conversation.id,
      fromParentId,
      toParentId,
      subject:
        conversation.subject ||
        (conversation.type === 'permission' ? 'Permission request' : 'Message'),
      preview:
        conversation.type === 'permission'
          ? (conversation.permissionRequest?.description ?? 'Permission request')
          : (conversation.messages?.[conversation.messages.length - 1]?.content ?? 'Conversation'),
      sentAt: conversation.lastMessageAt,
      unread: conversation.unreadCount > 0,
    }));
  }, [conversations, family?.primaryParentId, family?.secondaryParentId, parents]);

  const dashboardUpcomingEvents = useMemo<DashboardEvent[]>(() => {
    const now = Date.now();
    const horizon = now + 14 * 24 * 60 * 60 * 1000;

    return calendarEvents
      .map((event) => ({
        id: event.id,
        title: event.title,
        type: toDashboardEventType(event.type),
        startAt: combineEventDateTime(event.startDate, event.startTime),
        endAt: combineEventDateTime(
          event.endDate || event.startDate,
          event.endTime,
          event.allDay,
        ),
        location: event.location ?? '',
        childId: event.childIds[0] ?? null,
        status: 'confirmed' as const,
        notes: event.notes ?? '',
      }))
      .filter((event) => {
        const start = new Date(event.startAt).getTime();
        const end = new Date(event.endAt).getTime();
        return end >= now && start <= horizon;
      })
      .sort((left, right) => Date.parse(left.startAt) - Date.parse(right.startAt));
  }, [calendarEvents]);

  const dashboardPermissionRequests = useMemo<DashboardPermissionRequest[]>(() => {
    return conversations.flatMap((conversation) => {
      const request = conversation.permissionRequest;
      if (!request) return [];
      const type: DashboardPermissionRequest['type'] =
        request.type === 'schedule'
          ? 'schedule-change'
          : request.type === 'extracurricular'
            ? 'activity'
            : request.type;
      return [
        {
          id: request.id,
          title: conversation.subject,
          type,
          status: request.status,
          requestedByParentId: request.requestedBy,
          resolvedByParentId: null,
          requestedAt: request.createdAt,
          resolvedAt: request.resolvedAt,
          summary: request.description,
          relatedEntityType: 'document' as const,
          relatedEntityId: request.childId,
        },
      ];
    });
  }, [conversations]);

  const approvalsSummary = useMemo<ApprovalsSummary>(() => {
    const pendingPermissions = dashboardPermissionRequests.filter(
      (request) => request.status === 'pending',
    ).length;
    return {
      totalPending: pendingPermissions,
      byType: { expenses: 0, scheduleChanges: 0, permissions: pendingPermissions },
    };
  }, [dashboardPermissionRequests]);

  const budgetSummary = useMemo<BudgetSummary>(() => {
    return {
      month: new Date().toLocaleDateString(undefined, { month: 'long', year: 'numeric' }),
      currency: 'GBP',
      totalLimit: 0,
      totalSpent: 0,
      remaining: 0,
      categories: [],
    };
  }, []);

  const setupChecklist = useMemo<SetupChecklist>(() => {
    const items = [
      {
        id: 'setup-profile',
        label: 'Complete parent profiles',
        completed: dashboardParents.length > 0,
      },
      { id: 'setup-children', label: 'Add children', completed: dashboardChildren.length > 0 },
      { id: 'setup-invite', label: 'Invite co-parent', completed: dashboardInvitations.length > 0 },
    ];
    const completedCount = items.filter((item) => item.completed).length;
    return { items, completedCount, totalCount: items.length };
  }, [dashboardChildren.length, dashboardInvitations.length, dashboardParents.length]);

  const widgetCards = useMemo<WidgetCard[]>(() => {
    return [
      {
        id: 'wid-events',
        title: 'Upcoming Events',
        value: String(dashboardUpcomingEvents.length),
        description: 'Next 14 days',
        trend: 'flat',
        delta: '0',
        size: 'lg',
        sectionId: 'calendar',
      },
      {
        id: 'wid-approvals',
        title: 'Pending Approvals',
        value: String(approvalsSummary.totalPending),
        description: 'Requires response',
        trend: 'flat',
        delta: '0',
        size: 'md',
        sectionId: 'permissions',
      },
      {
        id: 'wid-spend',
        title: 'Monthly Spend',
        value: '$0',
        description: '$0 remaining',
        trend: 'flat',
        delta: '0%',
        size: 'md',
        sectionId: 'expenses',
      },
      {
        id: 'wid-unread',
        title: 'Unread Messages',
        value: String(dashboardMessages.filter((m) => m.unread).length),
        description: 'Inbox',
        trend: dashboardMessages.some((m) => m.unread) ? 'up' : 'flat',
        delta: dashboardMessages.some((m) => m.unread) ? '+1' : '0',
        size: 'sm',
        sectionId: 'messaging',
      },
      {
        id: 'wid-setup',
        title: 'Family Setup',
        value: family ? `${Math.round(family.setupProgress * 100)}%` : '0%',
        description: `${Math.max(0, setupChecklist.totalCount - setupChecklist.completedCount)} steps remaining`,
        trend: 'up',
        delta: '+',
        size: 'sm',
        sectionId: 'family',
      },
      {
        id: 'wid-activity',
        title: 'Recent Activity',
        value: '0',
        description: 'This week',
        trend: 'flat',
        delta: '0',
        size: 'lg',
        sectionId: 'dashboard',
      },
    ];
  }, [
    approvalsSummary.totalPending,
    dashboardMessages,
    dashboardUpcomingEvents.length,
    family,
    setupChecklist.completedCount,
    setupChecklist.totalCount,
  ]);

  const quickActions = useMemo<QuickAction[]>(
    () => [
      { id: 'add-expense', label: 'Add Expense', helper: 'Upload a receipt', shortcut: 'E' },
      {
        id: 'create-event',
        label: 'Create Event',
        helper: 'Schedule custody or activity',
        shortcut: 'C',
      },
      { id: 'send-message', label: 'Send Message', helper: 'Start a new thread', shortcut: 'M' },
    ],
    [],
  );

  const isLoading =
    familiesLoading ||
    parentsLoading ||
    childrenLoading ||
    invitationsLoading ||
    conversationsLoading ||
    eventsLoading;

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 dark:bg-slate-900">
        <p className="text-slate-500 dark:text-slate-400">Loading dashboard...</p>
      </div>
    );
  }

  if (!family) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 dark:bg-slate-900">
        <div className="max-w-md text-center">
          <h1 className="text-2xl font-semibold text-slate-900 dark:text-white">No family yet</h1>
          <p className="mt-2 text-slate-600 dark:text-slate-400">
            Finish onboarding to create your first family.
          </p>
          <button
            onClick={() => navigate('/onboarding')}
            className="mt-6 rounded-full bg-teal-600 px-6 py-3 text-sm font-semibold text-white hover:bg-teal-700 dark:bg-teal-500 dark:text-slate-950"
          >
            Go to onboarding
          </button>
        </div>
      </div>
    );
  }

  const currentParent =
    dashboardParents.find((p) => p.id === family.primaryParentId) ?? dashboardParents[0];

  const handleSaveProfile = async (_parentId: string, update: ParentProfileUpdate) => {
    await updateCurrentUser.mutateAsync({ fullName: update.fullName });
    setIsProfileOpen(false);
  };

  const handleResendInvitation = async (invitationId: string) => {
    if (!activeFamilyId) return;
    try {
      await resendInvitation.mutateAsync({ id: invitationId, familyId: activeFamilyId });
      showToast({
        variant: 'success',
        title: 'Invitation resent',
        description: 'A new invitation email has been sent.',
      });
    } catch (error) {
      console.error('Failed to resend invitation', error);
      showToast({
        variant: 'error',
        title: 'Resend failed',
        description: 'Please try again.',
      });
    }
  };

  const handleCancelInvitation = async (invitationId: string) => {
    if (!activeFamilyId) return;
    try {
      await cancelInvitation.mutateAsync({ id: invitationId, familyId: activeFamilyId });
      showToast({
        variant: 'success',
        title: 'Invitation canceled',
        description: 'The invite has been canceled.',
      });
    } catch (error) {
      console.error('Failed to cancel invitation', error);
      showToast({
        variant: 'error',
        title: 'Cancel failed',
        description: 'Please try again.',
      });
    }
  };

  const handleNavigateSection = (sectionId: string) => {
    if (sectionId === 'calendar') navigate('/calendar');
    else if (sectionId === 'expenses') navigate('/expenses');
    else if (sectionId === 'messaging') navigate('/messages');
    else if (sectionId === 'family') navigate('/family-setup');
    else if (sectionId === 'dashboard') navigate('/dashboard');
    else navigate('/dashboard');
  };

  const handleAddChild = async () => {
    setIsChildrenOpen(false);
    navigate('/family-setup');
  };

  const handleEditChild = async (childId: string) => {
    setIsChildrenOpen(false);
    navigate(`/family-setup?childId=${encodeURIComponent(childId)}`);
  };

  return (
    <>
      <DashboardOverview
        family={family}
        parents={dashboardParents}
        children={dashboardChildren}
        upcomingEvents={dashboardUpcomingEvents}
        permissionRequests={dashboardPermissionRequests}
        expenses={[]}
        messages={dashboardMessages}
        invitations={dashboardInvitations}
        activityFeed={[]}
        budgetSummary={budgetSummary}
        approvalsSummary={approvalsSummary}
        setupChecklist={setupChecklist}
        widgetCards={widgetCards}
        quickActions={quickActions}
        onOpenProfileDrawer={() => setIsProfileOpen(true)}
        onOpenChildrenDrawer={() => setIsChildrenOpen(true)}
        onOpenInvitationsDrawer={() => setIsInvitesOpen(true)}
        onAddChild={handleAddChild}
        onEditChild={handleEditChild}
        onResendInvitation={handleResendInvitation}
        onCancelInvitation={handleCancelInvitation}
        onNavigateSection={handleNavigateSection}
        onViewApproval={(permissionId) => {
          const conversation = conversations.find(
            (entry) => entry.permissionRequest?.id === permissionId,
          );
          navigate(conversation ? `/messages?thread=${conversation.id}` : '/messages');
        }}
        onViewEvent={(eventId) => navigate(`/calendar?event=${encodeURIComponent(eventId)}`)}
        onOpenMessageThread={(threadId) => navigate(`/messages?thread=${threadId}`)}
        onQuickAddExpense={() => navigate('/expenses')}
        onQuickCreateEvent={() => navigate('/calendar')}
        onQuickSendMessage={() => navigate('/messages')}
      />

      {currentParent && (
        <ProfileDrawer
          parent={currentParent}
          family={family}
          isOpen={isProfileOpen}
          onClose={() => setIsProfileOpen(false)}
          onSaveProfile={handleSaveProfile}
        />
      )}

      <ChildrenDrawer
        children={dashboardChildren}
        isOpen={isChildrenOpen}
        onClose={() => setIsChildrenOpen(false)}
        onAddChild={handleAddChild}
        onEditChild={handleEditChild}
      />

      <InvitationsDrawer
        invitations={dashboardInvitations}
        parents={dashboardParents}
        isOpen={isInvitesOpen}
        onClose={() => setIsInvitesOpen(false)}
        onResendInvitation={handleResendInvitation}
        onCancelInvitation={handleCancelInvitation}
      />
    </>
  );
};

export default DashboardPage;
