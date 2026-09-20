import { useEffect, useMemo, useState } from 'react';

import { MessagingAndPermissions } from '../components/messaging';
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
import type { PermissionRequestType } from '../lib/api/client';

const permissionTypes: PermissionRequestType[] = [
  'medical',
  'travel',
  'schedule',
  'extracurricular',
];

const MessagesPage = () => {
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

  const handleViewConversation = async (conversationId: string) => {
    if (!activeFamilyId) return;
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

  const handleCreateMessage = async () => {
    if (!activeFamilyId || !otherParent) {
      window.alert('Add a co-parent before starting a new conversation.');
      return;
    }

    const subject = window.prompt('Subject for the conversation?');
    if (!subject?.trim()) return;

    const message = window.prompt('Write the first message to send.');
    if (!message?.trim()) return;

    await createMessageConversation.mutateAsync({
      familyId: activeFamilyId,
      subject: subject.trim(),
      message: message.trim(),
      recipientId: otherParent.id,
    });
  };

  const handleCreatePermission = async () => {
    if (!activeFamilyId || !otherParent) {
      window.alert('Add a co-parent before creating a permission request.');
      return;
    }

    const child = children[0];
    if (!child) {
      window.alert('Add a child profile before creating a permission request.');
      return;
    }

    const typeInput = window.prompt(
      'Permission type (medical, travel, schedule, extracurricular)',
      'schedule',
    );
    const normalizedType = typeInput?.trim().toLowerCase() as PermissionRequestType | undefined;
    const type = permissionTypes.includes(normalizedType as PermissionRequestType)
      ? (normalizedType as PermissionRequestType)
      : 'schedule';

    const subject =
      window.prompt('Subject for the request?') ??
      `${child.fullName} ${type.replace('-', ' ')} request`;
    const description = window.prompt('Describe the request details.');
    if (!description?.trim()) return;

    await createPermissionConversation.mutateAsync({
      familyId: activeFamilyId,
      subject: subject?.trim() || `${child.fullName} ${type.replace('-', ' ')} request`,
      type,
      childId: child.id,
      childName: child.fullName,
      description: description.trim(),
    });
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
    <MessagingAndPermissions
      conversations={conversations}
      currentUserId={currentUserId}
      onViewConversation={handleViewConversation}
      onSendMessage={handleSendMessage}
      onMarkAsRead={handleMarkRead}
      onMarkAsUnread={handleMarkUnread}
      onCreateMessage={handleCreateMessage}
      onCreatePermissionRequest={handleCreatePermission}
      onApprovePermission={handleApprovePermission}
      onDenyPermission={handleDenyPermission}
    />
  );
};

export default MessagesPage;
