import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { MessagingAndPermissions } from '../components/messaging';
import {
  ComposeDrawer,
  type ComposeMode,
  type ComposeSubmission,
} from '../components/messaging/ComposeDrawer';
import {
  useApprovePermission,
  useChildren,
  useConversations,
  useCreateMessageConversation,
  useCreatePermissionConversation,
  useCurrentUser,
  useDenyPermission,
  useFamilies,
  useMarkConversationRead,
  useMarkConversationUnread,
  useParents,
  useSendMessage,
} from '../hooks/api';
const MessagesPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  // `conversation` is what the dashboard and applied assistant actions link to; `thread` is the
  // name older links used.
  const linkedConversationId =
    searchParams.get('conversation') || searchParams.get('thread') || undefined;
  const [composeMode, setComposeMode] = useState<ComposeMode | null>(null);
  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const [activeFamilyId, setActiveFamilyId] = useState<string | undefined>();

  const { data: conversations = [], isLoading: conversationsLoading } =
    useConversations(activeFamilyId);
  const { data: parents = [] } = useParents(activeFamilyId);
  const { data: children = [] } = useChildren(activeFamilyId);
  const { data: currentUser, isLoading: userLoading } = useCurrentUser(!!activeFamilyId);

  const createMessageConversation = useCreateMessageConversation();
  const createPermissionConversation = useCreatePermissionConversation();
  const sendMessage = useSendMessage();
  const markRead = useMarkConversationRead();
  const markUnread = useMarkConversationUnread();
  const approvePermission = useApprovePermission();
  const denyPermission = useDenyPermission();

  useEffect(() => {
    const [firstFamily] = families;
    if (!activeFamilyId && firstFamily) {
      setActiveFamilyId(firstFamily.id);
    }
  }, [activeFamilyId, families]);

  const currentProfile = useMemo(
    () => currentUser?.profiles.find((profile) => profile.familyId === activeFamilyId),
    [currentUser?.profiles, activeFamilyId],
  );

  const currentUserId = currentProfile?.id;
  const otherParent = parents.find((parent) => parent.id !== currentUserId);

  const openConversation = (conversationId: string) =>
    setSearchParams({ conversation: conversationId }, { replace: true });

  const handleViewConversation = async (conversationId: string) => {
    if (!activeFamilyId) return;
    openConversation(conversationId);
    const conversation = conversations.find((item) => item.id === conversationId);
    if (conversation?.unreadCount && conversation.unreadCount > 0) {
      await markRead.mutateAsync({ conversationId, familyId: activeFamilyId });
    }
  };

  const handleSendMessage = async (conversationId: string, content: string) => {
    if (!activeFamilyId) return;
    await sendMessage.mutateAsync({ conversationId, familyId: activeFamilyId, content });
  };

  const handleMarkRead = async (conversationId: string) => {
    if (!activeFamilyId) return;
    await markRead.mutateAsync({ conversationId, familyId: activeFamilyId });
  };

  const handleMarkUnread = async (conversationId: string) => {
    if (!activeFamilyId) return;
    await markUnread.mutateAsync({ conversationId, familyId: activeFamilyId });
  };

  const handleCompose = async (submission: ComposeSubmission) => {
    if (!activeFamilyId || !otherParent) return;
    const created =
      submission.mode === 'message'
        ? await createMessageConversation.mutateAsync({
            familyId: activeFamilyId,
            subject: submission.subject,
            message: submission.message,
            recipientId: otherParent.id,
          })
        : await createPermissionConversation.mutateAsync({
            familyId: activeFamilyId,
            subject: submission.subject,
            type: submission.type,
            childId: submission.childId,
            childName:
              children.find((child) => child.id === submission.childId)?.fullName ?? '',
            description: submission.description,
          });
    openConversation(created.id);
  };

  const handleApprovePermission = async (permissionId: string, response?: string) => {
    if (!activeFamilyId) return;
    await approvePermission.mutateAsync({ permissionId, familyId: activeFamilyId, response });
  };

  const handleDenyPermission = async (permissionId: string, response?: string) => {
    if (!activeFamilyId) return;
    await denyPermission.mutateAsync({ permissionId, familyId: activeFamilyId, response });
  };

  if (familiesLoading || conversationsLoading || userLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 dark:bg-slate-900">
        <p className="text-slate-500 dark:text-slate-400">Loading messages...</p>
      </div>
    );
  }

  if (!activeFamilyId || !currentUserId) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 dark:bg-slate-900">
        <p className="text-slate-500 dark:text-slate-400">Select a family to view messages.</p>
      </div>
    );
  }

  return (
    <>
      <MessagingAndPermissions
        conversations={conversations}
        currentUserId={currentUserId}
        selectedConversationId={linkedConversationId}
        canCompose={Boolean(otherParent)}
        onViewConversation={handleViewConversation}
        onSendMessage={handleSendMessage}
        onMarkAsRead={handleMarkRead}
        onMarkAsUnread={handleMarkUnread}
        onCreateMessage={() => setComposeMode('message')}
        onCreatePermissionRequest={() => setComposeMode('permission')}
        onApprovePermission={handleApprovePermission}
        onDenyPermission={handleDenyPermission}
      />
      <ComposeDrawer
        mode={composeMode}
        recipientName={otherParent?.fullName ?? 'your co-parent'}
        children={children.map((child) => ({ id: child.id, fullName: child.fullName }))}
        onClose={() => setComposeMode(null)}
        onSubmit={handleCompose}
      />
    </>
  );
};

export default MessagesPage;
