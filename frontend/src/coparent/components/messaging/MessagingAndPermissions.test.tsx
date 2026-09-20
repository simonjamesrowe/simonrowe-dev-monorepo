import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import type { Conversation } from '../../lib/api/client';

import { MessagingAndPermissions } from './MessagingAndPermissions';

type WrapperProps = {
  initialConversations: Conversation[];
  currentUserId?: string;
  onSendMessage?: (conversationId: string, content: string) => void;
  onApprovePermission?: (permissionId: string, response?: string) => void;
};

const currentUserId = 'parent-001';

const baseMessageConversation: Conversation = {
  id: 'conv-004',
  type: 'message',
  subject: 'School Parent-Teacher Conference',
  lastMessageAt: '2024-01-26T18:45:00Z',
  unreadCount: 0,
  participants: {
    parent1: { id: 'parent-002', name: 'David Martinez', avatarUrl: null },
    parent2: { id: 'parent-001', name: 'Sarah Martinez', avatarUrl: null },
  },
  messages: [
    {
      id: 'msg-001',
      senderId: 'parent-002',
      content: "Hey, got the reminder about Emma's parent-teacher conference.",
      timestamp: '2024-01-26T15:20:00Z',
      isRead: true,
      deliveryStatus: 'read',
    },
  ],
};

const basePermissionConversation: Conversation = {
  id: 'conv-001',
  type: 'permission',
  subject: 'Soccer Camp Registration',
  lastMessageAt: '2024-01-28T14:30:00Z',
  unreadCount: 0,
  participants: {
    parent1: { id: 'parent-001', name: 'Sarah Martinez', avatarUrl: null },
    parent2: { id: 'parent-002', name: 'David Martinez', avatarUrl: null },
  },
  permissionRequest: {
    id: 'perm-001',
    type: 'extracurricular',
    childId: 'child-001',
    childName: 'Emma Martinez',
    description: 'Register Emma for the summer soccer camp.',
    requestedBy: 'parent-001',
    status: 'pending',
    createdAt: '2024-01-28T14:30:00Z',
    resolvedAt: null,
    response: null,
  },
};

function Wrapper({
  initialConversations,
  currentUserId: overrideUserId,
  onSendMessage,
  onApprovePermission,
}: WrapperProps) {
  const [conversations, setConversations] = useState(initialConversations);

  const handleSendMessage = (conversationId: string, content: string) => {
    onSendMessage?.(conversationId, content);
    setConversations((prev) =>
      prev.map((conversation) => {
        if (conversation.id !== conversationId || conversation.type !== 'message')
          return conversation;
        const nextMessage = {
          id: 'msg-999',
          senderId: overrideUserId ?? currentUserId,
          content,
          timestamp: '2024-02-01T12:00:00Z',
          isRead: true,
          deliveryStatus: 'sent' as const,
        };
        return {
          ...conversation,
          lastMessageAt: nextMessage.timestamp,
          messages: [...(conversation.messages ?? []), nextMessage],
        };
      }),
    );
  };

  const handleApprovePermission = (permissionId: string, response?: string) => {
    onApprovePermission?.(permissionId, response);
    setConversations((prev) =>
      prev.map((conversation) => {
        if (conversation.permissionRequest?.id !== permissionId) return conversation;
        return {
          ...conversation,
          permissionRequest: {
            ...conversation.permissionRequest,
            status: 'approved',
            response: response ?? null,
            resolvedAt: '2024-02-01T10:00:00Z',
          },
        };
      }),
    );
  };

  return (
    <MessagingAndPermissions
      conversations={conversations}
      currentUserId={overrideUserId ?? currentUserId}
      onSendMessage={handleSendMessage}
      onApprovePermission={handleApprovePermission}
      onViewConversation={() => undefined}
      onMarkAsRead={() => undefined}
      onMarkAsUnread={() => undefined}
      onCreateMessage={() => undefined}
      onCreatePermissionRequest={() => undefined}
      onDenyPermission={() => undefined}
    />
  );
}

describe('MessagingAndPermissions', () => {
  it('sends a message, clears the draft, and renders the new message', async () => {
    const user = userEvent.setup();
    const onSendMessage = vi.fn();

    render(
      <Wrapper
        initialConversations={[baseMessageConversation, basePermissionConversation]}
        onSendMessage={onSendMessage}
      />,
    );

    const textarea = screen.getByPlaceholderText('Write a message or follow up on a decision...');
    await user.type(textarea, 'Following up on the conference details.');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(onSendMessage).toHaveBeenCalledWith(
      'conv-004',
      'Following up on the conference details.',
    );
    expect(textarea).toHaveValue('');
    expect(screen.getAllByText('Following up on the conference details.').length).toBeGreaterThan(
      0,
    );
  });

  it('does not send an empty message', async () => {
    const user = userEvent.setup();
    const onSendMessage = vi.fn();

    render(
      <Wrapper initialConversations={[baseMessageConversation]} onSendMessage={onSendMessage} />,
    );

    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(onSendMessage).not.toHaveBeenCalled();
  });

  it('approves a permission request and updates the status', async () => {
    const user = userEvent.setup();
    const onApprovePermission = vi.fn();

    render(
      <Wrapper
        initialConversations={[basePermissionConversation, baseMessageConversation]}
        onApprovePermission={onApprovePermission}
      />,
    );

    await user.click(screen.getByRole('button', { name: /Soccer Camp Registration/ }));
    const responseBox = screen.getByPlaceholderText('Share any notes or conditions...');
    await user.type(responseBox, 'Approved, thanks for coordinating.');
    await user.click(screen.getByRole('button', { name: 'Approve request' }));

    expect(onApprovePermission).toHaveBeenCalledWith(
      'perm-001',
      'Approved, thanks for coordinating.',
    );
    expect(screen.getAllByText('approved')[0]).toBeInTheDocument();
    expect(screen.getByText(/Resolved/)).toBeInTheDocument();
  });

  it('filters conversations and shows empty states', async () => {
    const user = userEvent.setup();
    render(<Wrapper initialConversations={[baseMessageConversation]} />);

    await user.click(screen.getByRole('button', { name: 'Permissions' }));
    expect(screen.getAllByText('No conversations found')[0]).toBeInTheDocument();
  });

  it('shows permission details unavailable when missing data', async () => {
    const user = userEvent.setup();
    const missingPermission: Conversation = {
      ...basePermissionConversation,
      id: 'conv-099',
      permissionRequest: undefined,
    };

    render(<Wrapper initialConversations={[missingPermission, baseMessageConversation]} />);
    await user.click(screen.getByRole('button', { name: /Soccer Camp Registration/ }));

    expect(screen.getByText('Permission details unavailable.')).toBeInTheDocument();
  });

  it('calls mark read/unread callbacks based on unread state', async () => {
    const user = userEvent.setup();
    const onMarkAsRead = vi.fn();
    const onMarkAsUnread = vi.fn();
    const unreadConversation = { ...baseMessageConversation, unreadCount: 2 };

    const { rerender } = render(
      <MessagingAndPermissions
        conversations={[unreadConversation]}
        currentUserId={currentUserId}
        onMarkAsRead={onMarkAsRead}
        onMarkAsUnread={onMarkAsUnread}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Mark read' }));
    expect(onMarkAsRead).toHaveBeenCalledWith('conv-004');

    rerender(
      <MessagingAndPermissions
        conversations={[baseMessageConversation]}
        currentUserId={currentUserId}
        onMarkAsRead={onMarkAsRead}
        onMarkAsUnread={onMarkAsUnread}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Mark unread' }));
    expect(onMarkAsUnread).toHaveBeenCalledWith('conv-004');
  });

  it('renders unread badges and permission status badges', () => {
    const unreadConversation = { ...basePermissionConversation, unreadCount: 3 };

    render(<Wrapper initialConversations={[unreadConversation, baseMessageConversation]} />);

    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getAllByText('pending')[0]).toBeInTheDocument();
  });

  it('shows recipient delivery state only on messages sent by the current parent', () => {
    const conversation: Conversation = {
      ...baseMessageConversation,
      messages: [
        {
          id: 'msg-incoming',
          senderId: 'parent-002',
          content: 'Incoming message',
          timestamp: '2024-01-26T15:20:00Z',
          isRead: false,
          deliveryStatus: 'delivered',
        },
        {
          id: 'msg-delivered',
          senderId: currentUserId,
          content: 'Delivered message',
          timestamp: '2024-01-26T15:25:00Z',
          isRead: true,
          deliveryStatus: 'delivered',
        },
        {
          id: 'msg-read',
          senderId: currentUserId,
          content: 'Read message',
          timestamp: '2024-01-26T15:30:00Z',
          isRead: true,
          deliveryStatus: 'read',
        },
      ],
    };

    render(<Wrapper initialConversations={[conversation]} />);

    expect(screen.getByLabelText('Message delivered')).toBeInTheDocument();
    expect(screen.getByLabelText('Message read')).toBeInTheDocument();
    expect(screen.queryByText('Unread')).not.toBeInTheDocument();
    expect(screen.getAllByText('Delivered')).toHaveLength(1);
  });
});
