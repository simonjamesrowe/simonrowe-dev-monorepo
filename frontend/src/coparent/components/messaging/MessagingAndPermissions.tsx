import { Check, CheckCheck } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';

import { apiErrorMessage } from '../../lib/api/errorMessage';

import type { Conversation, Message, PermissionRequest } from '../../lib/api/client';

import type { MessagingAndPermissionsProps } from './types';

type FilterMode = 'all' | 'message' | 'permission';

type StatusTone = 'pending' | 'approved' | 'denied';

const statusStyles: Record<StatusTone, string> = {
  pending:
    'border-teal-200/70 bg-teal-50 text-teal-700 dark:border-teal-900/60 dark:bg-teal-900/40 dark:text-teal-200',
  approved: 'bg-teal-600 text-white dark:bg-teal-500 dark:text-slate-950',
  denied:
    'border-rose-200/70 bg-rose-50 text-rose-700 dark:border-rose-900/60 dark:bg-rose-900/40 dark:text-rose-200',
};

const permissionTypeLabels: Record<PermissionRequest['type'], string> = {
  medical: 'Medical',
  travel: 'Travel',
  schedule: 'Schedule',
  extracurricular: 'Extracurricular',
};

const filterLabels: Record<FilterMode, string> = {
  all: 'All',
  message: 'Messages',
  permission: 'Permissions',
};

const formatTimestamp = (timestamp: string) => {
  const date = new Date(timestamp);
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
};

const formatTime = (timestamp: string) => {
  const date = new Date(timestamp);
  return date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
};

const getOtherParentName = (conversation: Conversation, currentUserId: string) => {
  const { parent1, parent2 } = conversation.participants;
  return parent1.id === currentUserId ? parent2.name : parent1.name;
};

const getSenderName = (conversation: Conversation, senderId: string) => {
  const { parent1, parent2 } = conversation.participants;
  if (parent1.id === senderId) return parent1.name;
  if (parent2.id === senderId) return parent2.name;
  return 'Parent';
};

function DeliveryStatus({ status }: { status: Message['deliveryStatus'] }) {
  const Icon = status === 'sent' ? Check : CheckCheck;
  const label = status.charAt(0).toUpperCase() + status.slice(1);

  return (
    <span
      aria-label={`Message ${status}`}
      className={`ml-2 inline-flex items-center gap-0.5 ${
        status === 'read' ? 'text-teal-600 dark:text-teal-400' : 'text-slate-400'
      }`}
    >
      <Icon aria-hidden="true" className="h-3.5 w-3.5" />
      <span>{label}</span>
    </span>
  );
}

export function MessagingAndPermissions({
  conversations,
  currentUserId,
  selectedConversationId,
  canCompose = true,
  onViewConversation,
  onSendMessage,
  onMarkAsRead,
  onMarkAsUnread,
  onCreateMessage,
  onCreatePermissionRequest,
  onApprovePermission,
  onDenyPermission,
}: MessagingAndPermissionsProps) {
  const [filterMode, setFilterMode] = useState<FilterMode>('all');
  const [activeId, setActiveId] = useState<string | null>(
    selectedConversationId ?? conversations[0]?.id ?? null,
  );
  const [draftMessage, setDraftMessage] = useState('');
  const [responseText, setResponseText] = useState('');
  const [decisionError, setDecisionError] = useState<string | null>(null);

  // A link to a conversation (or one just created) takes over the selection when it changes.
  useEffect(() => {
    if (selectedConversationId) setActiveId(selectedConversationId);
  }, [selectedConversationId]);

  const filteredConversations = useMemo(() => {
    if (filterMode === 'all') return conversations;
    return conversations.filter((conversation) => conversation.type === filterMode);
  }, [conversations, filterMode]);

  const activeConversation = useMemo(() => {
    return (
      filteredConversations.find((conversation) => conversation.id === activeId) ||
      filteredConversations[0] ||
      null
    );
  }, [filteredConversations, activeId]);

  const handleSelectConversation = (conversationId: string) => {
    setActiveId(conversationId);
    onViewConversation?.(conversationId);
  };

  const handleSendMessage = () => {
    if (!activeConversation || !draftMessage.trim()) return;
    onSendMessage?.(activeConversation.id, draftMessage.trim());
    setDraftMessage('');
  };

  const decide = async (
    handler: MessagingAndPermissionsProps['onApprovePermission'],
    permissionId: string,
  ) => {
    setDecisionError(null);
    try {
      await handler?.(permissionId, responseText.trim() || undefined);
      setResponseText('');
    } catch (failure) {
      setDecisionError(apiErrorMessage(failure, 'Your response could not be saved. Try again.'));
    }
  };

  const handleApprove = (permissionId: string) => decide(onApprovePermission, permissionId);
  const handleDeny = (permissionId: string) => decide(onDenyPermission, permissionId);

  return (
    <div className="relative min-h-[80vh] bg-gradient-to-br from-slate-50 via-white to-teal-50/40 dark:from-slate-950 dark:via-slate-900 dark:to-teal-950/30">
      <div className="relative mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8 lg:px-8">
        <div className="flex flex-col gap-5 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <p className="text-xs uppercase tracking-[0.2em] text-slate-500 dark:text-slate-400">
              Messaging & Permissions
            </p>
            <h1 className="mt-2 text-2xl font-semibold text-slate-900 sm:text-3xl dark:text-white">
              Clear, documented co-parent decisions
            </h1>
            <p className="mt-2 max-w-2xl text-sm text-slate-500 dark:text-slate-400">
              Track conversations alongside formal approvals, all in one transparent view designed
              for accountability.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {!canCompose && (
              <p className="w-full text-xs text-slate-500 dark:text-slate-400">
                Invite your co-parent to start conversations and requests.
              </p>
            )}
            <button
              onClick={onCreatePermissionRequest}
              disabled={!canCompose}
              className="inline-flex items-center gap-2 rounded-full border border-rose-200 bg-rose-50 px-4 py-2 text-sm font-medium text-rose-700 transition hover:border-rose-300 hover:bg-rose-100 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400 dark:border-rose-900/60 dark:bg-rose-900/30 dark:text-rose-200"
            >
              New permission
            </button>
            <button
              onClick={onCreateMessage}
              disabled={!canCompose}
              className="inline-flex items-center gap-2 rounded-full bg-teal-600 px-4 py-2 text-sm font-medium text-white shadow-lg shadow-teal-500/25 transition hover:-translate-y-0.5 hover:bg-teal-700 active:translate-y-0 disabled:translate-y-0 disabled:cursor-not-allowed disabled:bg-slate-300 disabled:shadow-none dark:bg-teal-500 dark:text-slate-950"
            >
              New message
            </button>
          </div>
        </div>

        <div className="mt-6 flex flex-wrap items-center gap-2 sm:mt-8">
          {Object.entries(filterLabels).map(([key, label]) => {
            const isActive = filterMode === key;
            return (
              <button
                key={key}
                onClick={() => setFilterMode(key as FilterMode)}
                className={`rounded-full px-4 py-2 text-sm font-medium transition ${
                  isActive
                    ? 'bg-slate-900 text-white shadow-lg shadow-slate-900/15 dark:bg-slate-100 dark:text-slate-900'
                    : 'border border-slate-200/70 bg-white text-slate-600 hover:border-slate-300 hover:text-slate-800 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-400 dark:hover:text-slate-200'
                }`}
              >
                {label}
              </button>
            );
          })}
        </div>

        <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-[360px_1fr]">
          <div className="rounded-3xl border border-slate-200/80 bg-white/80 shadow-xl shadow-slate-900/5 backdrop-blur dark:border-slate-800/60 dark:bg-slate-950/60">
            <div className="flex items-center justify-between border-b border-slate-200/70 px-5 py-4 dark:border-slate-800/70">
              <div>
                <h2 className="text-base font-semibold text-slate-900 dark:text-white">Threads</h2>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  {filteredConversations.length} active conversations
                </p>
              </div>
              <div className="flex items-center gap-2">
                <span className="rounded-full border border-slate-200 bg-slate-50 px-2 py-1 text-[11px] font-semibold uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-400">
                  {filterLabels[filterMode]}
                </span>
              </div>
            </div>

            <div className="divide-y divide-slate-200/70 dark:divide-slate-800/70">
              {filteredConversations.length === 0 ? (
                <div className="px-5 py-10 text-center">
                  <p className="text-sm font-semibold text-slate-700 dark:text-slate-200">
                    No conversations found
                  </p>
                  <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
                    Start a new message or permission request to begin the record.
                  </p>
                </div>
              ) : (
                filteredConversations.map((conversation) => {
                  const isActive = activeConversation?.id === conversation.id;
                  const previewText =
                    conversation.type === 'message'
                      ? (conversation.messages?.[conversation.messages.length - 1]?.content ??
                        'No messages yet.')
                      : (conversation.permissionRequest?.description ?? 'No permission details.');

                  return (
                    <button
                      key={conversation.id}
                      onClick={() => handleSelectConversation(conversation.id)}
                      className={`w-full px-5 py-4 text-left transition ${
                        isActive
                          ? 'bg-teal-50/70 dark:bg-teal-950/40'
                          : 'hover:bg-slate-50/80 dark:hover:bg-slate-900/60'
                      }`}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="text-sm font-semibold text-slate-900 dark:text-white">
                              {conversation.subject}
                            </span>
                            {conversation.type === 'permission' &&
                              conversation.permissionRequest && (
                                <span
                                  className={`rounded-full border px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide ${
                                    statusStyles[conversation.permissionRequest.status]
                                  }`}
                                >
                                  {conversation.permissionRequest.status}
                                </span>
                              )}
                          </div>
                          <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                            With {getOtherParentName(conversation, currentUserId)}
                          </p>
                        </div>
                        <div className="flex flex-col items-end gap-2">
                          <span className="text-xs text-slate-400 dark:text-slate-500">
                            {formatTimestamp(conversation.lastMessageAt)}
                          </span>
                          {conversation.unreadCount > 0 && (
                            <span className="inline-flex h-6 min-w-[24px] items-center justify-center rounded-full bg-rose-500 px-2 text-[11px] font-semibold text-white">
                              {conversation.unreadCount}
                            </span>
                          )}
                        </div>
                      </div>
                      <p className="mt-3 truncate text-sm text-slate-600 dark:text-slate-400">
                        {previewText}
                      </p>
                    </button>
                  );
                })
              )}
            </div>
          </div>

          <div className="rounded-3xl border border-slate-200/80 bg-white/90 shadow-2xl shadow-slate-900/10 backdrop-blur dark:border-slate-800/60 dark:bg-slate-950/60">
            {activeConversation ? (
              <div className="flex h-full flex-col">
                <div className="border-b border-slate-200/70 px-6 py-5 dark:border-slate-800/70">
                  <div className="flex flex-wrap items-center justify-between gap-4">
                    <div>
                      <div className="flex items-center gap-2">
                        <h2 className="text-lg font-semibold text-slate-900 dark:text-white">
                          {activeConversation.subject}
                        </h2>
                        <span className="rounded-full border border-slate-200 bg-slate-50 px-2 py-1 text-[11px] font-semibold uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-400">
                          {activeConversation.type === 'message' ? 'Message' : 'Permission'}
                        </span>
                      </div>
                      <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                        {activeConversation.type === 'permission'
                          ? 'Formal request with documented outcome'
                          : `Conversation with ${getOtherParentName(activeConversation, currentUserId)}`}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      {activeConversation.unreadCount > 0 ? (
                        <button
                          onClick={() => onMarkAsRead?.(activeConversation.id)}
                          className="rounded-full border border-slate-200 px-3 py-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600 hover:border-slate-300 hover:text-slate-800 dark:border-slate-800 dark:text-slate-400 dark:hover:text-slate-200"
                        >
                          Mark read
                        </button>
                      ) : (
                        <button
                          onClick={() => onMarkAsUnread?.(activeConversation.id)}
                          className="rounded-full border border-slate-200 px-3 py-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600 hover:border-slate-300 hover:text-slate-800 dark:border-slate-800 dark:text-slate-400 dark:hover:text-slate-200"
                        >
                          Mark unread
                        </button>
                      )}
                    </div>
                  </div>
                </div>

                {activeConversation.type === 'message' ? (
                  <MessageThread conversation={activeConversation} currentUserId={currentUserId} />
                ) : (
                  <PermissionPanel
                    conversation={activeConversation}
                    currentUserId={currentUserId}
                    error={decisionError}
                    responseText={responseText}
                    onResponseChange={setResponseText}
                    onApprove={handleApprove}
                    onDeny={handleDeny}
                  />
                )}

                <div className="border-t border-slate-200/70 px-6 py-4 dark:border-slate-800/70">
                  <div className="flex items-end gap-3">
                    <div className="flex-1 rounded-2xl border border-slate-200 bg-white px-4 py-3 shadow-sm dark:border-slate-800 dark:bg-slate-950">
                      <textarea
                        value={draftMessage}
                        onChange={(event) => setDraftMessage(event.target.value)}
                        rows={2}
                        placeholder="Write a message or follow up on a decision..."
                        className="w-full resize-none bg-transparent text-sm text-slate-700 outline-none placeholder:text-slate-400 dark:text-slate-200 dark:placeholder:text-slate-500"
                      />
                    </div>
                    <button
                      onClick={handleSendMessage}
                      className="inline-flex items-center justify-center rounded-2xl bg-teal-600 px-5 py-3 text-sm font-semibold text-white shadow-lg shadow-teal-500/20 transition hover:-translate-y-0.5 hover:bg-teal-700 active:translate-y-0 dark:bg-teal-500 dark:text-slate-950"
                    >
                      Send
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <div className="flex h-full items-center justify-center px-6 py-16 text-center">
                <div>
                  <p className="text-base font-semibold text-slate-800 dark:text-slate-200">
                    No conversations found
                  </p>
                  <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
                    Adjust your filters or create a new message thread to get started.
                  </p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function MessageThread({
  conversation,
  currentUserId,
}: {
  conversation: Conversation;
  currentUserId: string;
}) {
  const messages = conversation.messages ?? [];

  return (
    <div className="flex-1 px-6 py-5">
      <div className="flex flex-col gap-4">
        {messages.map((message: Message) => {
          const isCurrentUser = message.senderId === currentUserId;
          return (
            <div
              key={message.id}
              className={`flex flex-col ${isCurrentUser ? 'items-end' : 'items-start'}`}
            >
              <div
                className={`max-w-[80%] rounded-2xl px-4 py-3 text-sm shadow-sm ${
                  isCurrentUser
                    ? 'bg-teal-600 text-white dark:bg-teal-500 dark:text-slate-950'
                    : 'bg-slate-100 text-slate-700 dark:bg-slate-900 dark:text-slate-200'
                }`}
              >
                <p>{message.content}</p>
              </div>
              <div
                className={`mt-1 text-[11px] ${
                  isCurrentUser ? 'text-teal-700' : 'text-slate-400'
                } dark:text-slate-500`}
              >
                {getSenderName(conversation, message.senderId)} · {formatTime(message.timestamp)}
                {isCurrentUser && <DeliveryStatus status={message.deliveryStatus} />}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function PermissionPanel({
  conversation,
  currentUserId,
  error,
  responseText,
  onResponseChange,
  onApprove,
  onDeny,
}: {
  conversation: Conversation;
  currentUserId: string;
  error: string | null;
  responseText: string;
  onResponseChange: (value: string) => void;
  onApprove: (permissionId: string) => void;
  onDeny: (permissionId: string) => void;
}) {
  const permission = conversation.permissionRequest;

  if (!permission) {
    return (
      <div className="flex flex-1 items-center justify-center px-6 py-12">
        <p className="text-sm text-slate-500 dark:text-slate-400">
          Permission details unavailable.
        </p>
      </div>
    );
  }

  return (
    <div className="flex-1 px-6 py-5">
      <div className="rounded-2xl border border-slate-200/80 bg-slate-50 px-5 py-5 dark:border-slate-800/70 dark:bg-slate-900/60">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={`rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-wide ${
              statusStyles[permission.status]
            }`}
          >
            {permission.status}
          </span>
          <span className="rounded-full border border-rose-200 bg-rose-50 px-3 py-1 text-xs font-semibold uppercase tracking-wide text-rose-700 dark:border-rose-900/50 dark:bg-rose-900/30 dark:text-rose-200">
            {permissionTypeLabels[permission.type]}
          </span>
          <span className="rounded-full border border-slate-200 bg-white px-3 py-1 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-400">
            {permission.childName}
          </span>
        </div>

        <p className="mt-4 text-sm leading-relaxed text-slate-700 dark:text-slate-200">
          {permission.description}
        </p>

        <div className="mt-4 grid gap-3 text-xs text-slate-500 sm:grid-cols-2 dark:text-slate-400">
          <div className="rounded-xl border border-slate-200 bg-white px-3 py-2 dark:border-slate-800 dark:bg-slate-950">
            Requested {formatTimestamp(permission.createdAt)}
          </div>
          <div className="rounded-xl border border-slate-200 bg-white px-3 py-2 dark:border-slate-800 dark:bg-slate-950">
            {permission.resolvedAt
              ? `Resolved ${formatTimestamp(permission.resolvedAt)}`
              : 'Awaiting response'}
          </div>
        </div>
      </div>

      {permission.response && (
        <div className="mt-4 rounded-2xl border border-slate-200 bg-white px-5 py-4 dark:border-slate-800 dark:bg-slate-950">
          <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">Response</p>
          <p className="mt-2 text-sm text-slate-700 dark:text-slate-200">{permission.response}</p>
        </div>
      )}

      {/* The requester cannot answer their own request (the API refuses it with a 403), so
          they are told who it is waiting on instead of being offered buttons that fail. */}
      {permission.status === 'pending' && permission.requestedBy === currentUserId && (
        <p className="mt-5 rounded-2xl border border-slate-200 bg-white px-5 py-4 text-sm text-slate-500 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-400">
          Waiting for {getOtherParentName(conversation, currentUserId)} to respond.
        </p>
      )}

      {permission.status === 'pending' && permission.requestedBy !== currentUserId && (
        <div className="mt-5 rounded-2xl border border-slate-200 bg-white px-5 py-4 dark:border-slate-800 dark:bg-slate-950">
          <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">
            Your response
          </p>
          <textarea
            value={responseText}
            onChange={(event) => onResponseChange(event.target.value)}
            rows={3}
            placeholder="Share any notes or conditions..."
            className="mt-3 w-full resize-none rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700 outline-none placeholder:text-slate-400 focus:border-teal-400 focus:ring-2 focus:ring-teal-200 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-200 dark:placeholder:text-slate-500 dark:focus:border-teal-500/60 dark:focus:ring-teal-900/40"
          />
          {error && (
            <p role="alert" className="mt-3 text-sm text-red-700 dark:text-red-300">
              {error}
            </p>
          )}
          <div className="mt-4 flex flex-wrap gap-3">
            <button
              onClick={() => onApprove(permission.id)}
              className="inline-flex items-center justify-center rounded-xl bg-teal-600 px-4 py-2 text-sm font-semibold text-white shadow-lg shadow-teal-500/20 transition hover:-translate-y-0.5 hover:bg-teal-700 active:translate-y-0 dark:bg-teal-500 dark:text-slate-950"
            >
              Approve request
            </button>
            <button
              onClick={() => onDeny(permission.id)}
              className="inline-flex items-center justify-center rounded-xl border border-rose-200 bg-rose-50 px-4 py-2 text-sm font-semibold text-rose-700 transition hover:border-rose-300 hover:bg-rose-100 dark:border-rose-900/50 dark:bg-rose-900/30 dark:text-rose-200"
            >
              Deny request
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
