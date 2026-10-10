import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { useQuickAdd } from '../components/assistant';
import { dateToYmd } from '../components/calendar/recurrence';
import { useNow } from '../components/calendar/timeGrid';
import { ProfileDrawer } from '../components/dashboard';
import {
  DashboardBriefing,
  type BriefingMoney,
  type NeedsYouItem,
} from '../components/dashboard/DashboardBriefing';
import { briefingChildren, custodyParentOn, nextHandover } from '../components/dashboard/briefing';
import { formatMoney } from '../components/expenses/money';
import { firstName } from '../components/expenses/parentTone';
import { useToast } from '../components/ui/ToastProvider';
import {
  useChildren,
  useConversations,
  useCurrentParentId,
  useCurrentUser,
  useEvents,
  useExpenseSummary,
  useExpenseTransition,
  useExpenses,
  useFamilies,
  useInvitations,
  useParents,
  useParentsWithInvited,
  useResendInvitation,
  useScheduleChangeRequests,
  useUpdateCurrentUser,
} from '../hooks/api';
import type {
  Family as DashboardFamily,
  Parent as DashboardParent,
  ParentProfileUpdate,
} from '../types/dashboard';

const SENT_ON = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });

const sentOn = (iso: string) => {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : SENT_ON.format(date).replace(',', '');
};

const preview = (text: string, length = 90) =>
  text.length > length ? `${text.slice(0, length - 1).trimEnd()}…` : text;

const DashboardPage = () => {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const quickAdd = useQuickAdd();
  const now = useNow();

  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const [activeFamilyId, setActiveFamilyId] = useState<string | undefined>();

  const { data: currentUser } = useCurrentUser();
  const currentParentId = useCurrentParentId(activeFamilyId);
  const { data: parents = [], isLoading: parentsLoading } = useParents(activeFamilyId);
  // Expenses, messages and invitations can name a co-parent who has not joined yet.
  const { data: allParents = [] } = useParentsWithInvited(activeFamilyId);
  const { data: children = [], isLoading: childrenLoading } = useChildren(activeFamilyId);
  const { data: invitations = [] } = useInvitations(activeFamilyId);
  const { data: conversations = [] } = useConversations(activeFamilyId);
  const { data: events = [], isLoading: eventsLoading } = useEvents(activeFamilyId);
  const { data: scheduleChangeRequests = [] } = useScheduleChangeRequests(activeFamilyId);
  const { data: expenses = [] } = useExpenses(activeFamilyId);
  const { data: expenseSummary } = useExpenseSummary(activeFamilyId);
  const expenseTransition = useExpenseTransition();
  const resendInvitation = useResendInvitation();
  const updateCurrentUser = useUpdateCurrentUser();

  const [isProfileOpen, setIsProfileOpen] = useState(false);

  useEffect(() => {
    const [firstFamily] = families;
    if (!activeFamilyId && firstFamily) {
      setActiveFamilyId(firstFamily.id);
    }
  }, [activeFamilyId, families]);

  const family = families.find((entry) => entry.id === activeFamilyId) ?? families[0];
  const kids = useMemo(() => briefingChildren(children), [children]);

  const otherParents = allParents.filter((parent) => parent.id !== currentParentId);
  const otherParent =
    otherParents.find((parent) => parent.status === 'active') ??
    otherParents.find((parent) => parent.status === 'invited');
  const otherName = firstName(otherParent);
  const nameOf = (parentId: string) =>
    parentId === currentParentId
      ? 'you'
      : firstName(allParents.find((parent) => parent.id === parentId), 'your co-parent');

  const today = dateToYmd(now);
  const custodyParentId = useMemo(() => custodyParentOn(events, today), [events, today]);
  const handover = useMemo(() => nextHandover(events, today), [events, today]);

  const agreeToExpense = async (expenseId: string) => {
    const expense = expenses.find((entry) => entry.id === expenseId);
    if (!activeFamilyId || !expense) return;
    try {
      await expenseTransition.mutateAsync({ familyId: activeFamilyId, expense, transition: 'agree' });
      showToast({ variant: 'success', title: 'Agreed', description: `${expense.title} is agreed.` });
    } catch {
      showToast({ variant: 'error', title: 'That did not work', description: 'Reload and try again.' });
    }
  };

  const resend = async (invitationId: string) => {
    if (!activeFamilyId) return;
    try {
      await resendInvitation.mutateAsync({ id: invitationId, familyId: activeFamilyId });
      showToast({ variant: 'success', title: 'Invitation resent', description: 'A new invitation email is on its way.' });
    } catch {
      showToast({ variant: 'error', title: 'Resend failed', description: 'Please try again.' });
    }
  };

  // Everything waiting on this parent, most consequential first: money they are asked to agree,
  // decisions the other parent asked for, then unread messages and anyone not yet joined.
  const needs: NeedsYouItem[] = [];

  const expenseApprovals = expenses.filter(
    (expense) => expense.agreement.status === 'pending' && expense.agreement.requestedBy !== currentParentId,
  );
  expenseApprovals.forEach((expense) => {
    const myShare = expense.shares.find((share) => share.parentId === currentParentId)?.sharePence ?? 0;
    needs.push({
      id: `expense-${expense.id}`,
      kind: 'expense',
      title: `${expense.title} · ${formatMoney(expense.amountPence)}`,
      detail: `${otherName} added it and asks you to agree. Your share is ${formatMoney(myShare)}.`,
      actions: [
        { label: 'Open', onClick: () => navigate(`/expenses?expense=${encodeURIComponent(expense.id)}`) },
        {
          label: 'Agree',
          primary: true,
          disabled: expenseTransition.isPending,
          onClick: () => void agreeToExpense(expense.id),
        },
      ],
    });
  });

  const otherExpenseActions = Math.max(0, (expenseSummary?.needsYourAction ?? 0) - expenseApprovals.length);
  if (otherExpenseActions > 0) {
    needs.push({
      id: 'expenses-other',
      kind: 'expense',
      title:
        otherExpenseActions === 1
          ? 'An expense is waiting on you'
          : `${otherExpenseActions} expenses are waiting on you`,
      detail: 'A repayment to confirm, a dispute to settle or a payment to record.',
      actions: [{ label: 'Open expenses', primary: true, onClick: () => navigate('/expenses') }],
    });
  }

  scheduleChangeRequests
    .filter((request) => request.status === 'pending' && request.requestedBy !== currentParentId)
    .forEach((request) => {
      needs.push({
        id: `schedule-${request.id}`,
        kind: 'schedule',
        title: `${firstName(allParents.find((parent) => parent.id === request.requestedBy))} asked to change the schedule`,
        detail: preview(request.reason || 'No reason given.'),
        actions: [
          {
            label: 'Review',
            primary: true,
            onClick: () => navigate(`/calendar?request=${encodeURIComponent(request.id)}`),
          },
        ],
      });
    });

  conversations.forEach((conversation) => {
    const request = conversation.permissionRequest;
    const openThread = () => navigate(`/messages?conversation=${encodeURIComponent(conversation.id)}`);
    if (request?.status === 'pending' && request.requestedBy !== currentParentId) {
      needs.push({
        id: `permission-${request.id}`,
        kind: 'permission',
        title: conversation.subject || `Permission for ${request.childName}`,
        detail: preview(request.description),
        actions: [{ label: 'Respond', primary: true, onClick: openThread }],
      });
    } else if (conversation.unreadCount > 0) {
      const last = conversation.messages?.[conversation.messages.length - 1];
      needs.push({
        id: `message-${conversation.id}`,
        kind: 'message',
        title: conversation.subject || `Message from ${otherName}`,
        detail: preview(last?.content ?? 'New message.'),
        actions: [{ label: 'Read', primary: true, onClick: openThread }],
      });
    }
  });

  invitations
    .filter((invitation) => invitation.status === 'pending')
    .forEach((invitation) => {
      const invited = allParents.find(
        (parent) => parent.status === 'invited' && parent.email?.toLowerCase() === invitation.email.toLowerCase(),
      );
      const name = invited ? firstName(invited) : invitation.email;
      const sent = sentOn(invitation.sentAt);
      needs.push({
        id: `invite-${invitation.id}`,
        kind: 'invite',
        title: `${name} hasn't joined yet`,
        detail: `${sent ? `Invitation sent ${sent}. ` : ''}They can't see the calendar or agree to expenses until they join.`,
        actions: [
          {
            label: 'Resend',
            primary: true,
            disabled: resendInvitation.isPending,
            onClick: () => void resend(invitation.id),
          },
        ],
      });
    });

  const money: BriefingMoney | null = expenseSummary
    ? (() => {
        const { balance, upcoming } = expenseSummary;
        const addExpense = { label: 'Add expense', onClick: () => navigate('/expenses?new=1') };
        const upcomingLine =
          upcoming.count > 0
            ? `${upcoming.count === 1 ? 'One shared cost' : `${upcoming.count} shared costs`} coming up, your share ${formatMoney(upcoming.yourSharePence)}.`
            : 'No shared costs coming up.';
        if (balance.netPence === 0) {
          return { headline: 'All square', detail: upcomingLine, actions: [addExpense] };
        }
        const owedToMe = balance.creditorParentId === currentParentId;
        return {
          headline: owedToMe
            ? `${otherName} owes you ${formatMoney(balance.netPence)}`
            : `You owe ${otherName} ${formatMoney(balance.netPence)}`,
          detail: upcomingLine,
          actions: [
            addExpense,
            { label: 'Settle up', primary: true, onClick: () => navigate('/expenses?settle=1') },
          ],
        };
      })()
    : null;

  // The profile drawer edits the signed-in person, never "the primary parent": PATCH /me
  // renames the caller, so showing somebody else's name there renamed you to them on Save.
  const me = parents.find((parent) => parent.id === currentParentId);
  const profileParent: DashboardParent | null = me
    ? {
        id: me.id,
        fullName: me.fullName,
        email: me.email ?? currentUser?.email ?? '',
        role: me.role === 'primary' ? 'primary' : 'secondary',
        phone: '',
        avatarUrl: me.avatarUrl ?? null,
        lastActiveAt: me.lastSignedInAt ?? new Date().toISOString(),
        notificationPreferences: { email: true, sms: false, push: true },
      }
    : null;
  const profileFamily: DashboardFamily | null = family
    ? {
        id: family.id,
        name: family.name,
        timezone: family.timeZone,
        primaryParentId: parents.find((parent) => parent.role === 'primary')?.id ?? '',
        secondaryParentId: parents.find((parent) => parent.role !== 'primary')?.id ?? '',
        childIds: children.map((child) => child.id),
        createdAt: family.createdAt,
        setupProgress: 1,
      }
    : null;

  const handleSaveProfile = async (_parentId: string, update: ParentProfileUpdate) => {
    await updateCurrentUser.mutateAsync({ fullName: update.fullName });
    setIsProfileOpen(false);
  };

  if (familiesLoading || parentsLoading || childrenLoading || eventsLoading) {
    return (
      <div className="cp-brief cp-brief--loading">
        <p className="cp-brief__empty">Loading your day…</p>
      </div>
    );
  }

  if (!family) {
    return (
      <main className="cp-brief">
        <h1 className="cp-brief__headline">No family yet.</h1>
        <p className="cp-brief__sub">Finish onboarding to create your first family.</p>
        <div className="cp-brief__actions">
          <button
            type="button"
            className="cp-brief__btn cp-brief__btn--primary"
            onClick={() => navigate('/onboarding')}
          >
            Go to onboarding
          </button>
        </div>
      </main>
    );
  }

  return (
    <>
      <DashboardBriefing
        now={now}
        children={kids}
        events={events}
        custodyParentName={custodyParentId ? nameOf(custodyParentId) : null}
        handover={handover ? { date: handover.date, parentName: nameOf(handover.parentId) } : null}
        needs={needs}
        money={money}
        onQuickAdd={quickAdd.available ? quickAdd.open : undefined}
        onAddEvent={() => navigate('/calendar?create=true')}
        onOpenCalendar={() => navigate('/calendar')}
        onOpenEvent={(eventId, occurrence) =>
          navigate(
            occurrence
              ? `/calendar?edit=${encodeURIComponent(eventId)}&occurrence=${occurrence}`
              : `/calendar?event=${encodeURIComponent(eventId)}`,
          )
        }
        onEditProfile={() => setIsProfileOpen(true)}
        onFamilySetup={() => navigate('/family-setup')}
      />

      {/* Mounted only while open: parked off-screen, its shadow showed down the page's right
          edge and its fields could still be reached with Tab. */}
      {isProfileOpen && profileParent && profileFamily && (
        <ProfileDrawer
          parent={profileParent}
          family={profileFamily}
          isOpen={isProfileOpen}
          onClose={() => setIsProfileOpen(false)}
          onSaveProfile={handleSaveProfile}
        />
      )}
    </>
  );
};

export default DashboardPage;
