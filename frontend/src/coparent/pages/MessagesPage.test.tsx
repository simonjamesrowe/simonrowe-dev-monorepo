import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import * as apiHooks from '../hooks/api';

import MessagesPage from './MessagesPage';

const createMessageMutate = vi.fn();
const createPermissionMutate = vi.fn();

vi.mock('../hooks/api', () => ({
  useFamilies: vi.fn(),
  useConversations: vi.fn(),
  useParents: vi.fn(),
  useChildren: vi.fn(),
  useCurrentUser: vi.fn(),
  useCreateMessageConversation: vi.fn(),
  useCreatePermissionConversation: vi.fn(),
  useSendMessage: vi.fn(),
  useMarkConversationRead: vi.fn(),
  useMarkConversationUnread: vi.fn(),
  useApprovePermission: vi.fn(),
  useDenyPermission: vi.fn(),
}));

const mockedUseFamilies = vi.mocked(apiHooks.useFamilies);
const mockedUseConversations = vi.mocked(apiHooks.useConversations);
const mockedUseParents = vi.mocked(apiHooks.useParents);
const mockedUseChildren = vi.mocked(apiHooks.useChildren);
const mockedUseCurrentUser = vi.mocked(apiHooks.useCurrentUser);
const mockedUseCreateMessageConversation = vi.mocked(apiHooks.useCreateMessageConversation);
const mockedUseCreatePermissionConversation = vi.mocked(apiHooks.useCreatePermissionConversation);
const mockedUseSendMessage = vi.mocked(apiHooks.useSendMessage);
const mockedUseMarkConversationRead = vi.mocked(apiHooks.useMarkConversationRead);
const mockedUseMarkConversationUnread = vi.mocked(apiHooks.useMarkConversationUnread);
const mockedUseApprovePermission = vi.mocked(apiHooks.useApprovePermission);
const mockedUseDenyPermission = vi.mocked(apiHooks.useDenyPermission);

describe('MessagesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();

    createMessageMutate.mockResolvedValue({ id: 'conv-2' });
    createPermissionMutate.mockResolvedValue({ id: 'conv-3' });

    mockedUseFamilies.mockReturnValue({
      data: [
        {
          id: 'fam-1',
          name: 'Messages Family',
          timeZone: 'America/New_York',
          parentIds: ['parent-1', 'parent-2'],
          childIds: ['child-1'],
          invitationIds: ['invite-1'],
          createdAt: '2026-01-01T00:00:00.000Z',
        },
      ],
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useFamilies>);

    mockedUseConversations.mockReturnValue({
      data: [
        {
          id: 'conv-1',
          type: 'message',
          subject: 'Existing thread',
          lastMessageAt: '2026-01-01T00:00:00.000Z',
          unreadCount: 0,
          participants: {
            parent1: { id: 'parent-1', name: 'Alex', avatarUrl: null },
            parent2: { id: 'parent-2', name: 'Sam', avatarUrl: null },
          },
          messages: [
            {
              id: 'msg-1',
              senderId: 'parent-1',
              content: 'Hello',
              timestamp: '2026-01-01T00:00:00.000Z',
              isRead: true,
              deliveryStatus: 'read',
            },
          ],
        },
      ],
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useConversations>);

    mockedUseParents.mockReturnValue({
      data: [
        {
          id: 'parent-1',
          familyId: 'fam-1',
          fullName: 'Alex Rowe',
          email: 'alex@example.com',
          role: 'primary',
          status: 'active',
        },
        {
          id: 'parent-2',
          familyId: 'fam-1',
          fullName: 'Sam Rowe',
          email: 'sam@example.com',
          role: 'co-parent',
          status: 'active',
        },
      ],
    } as unknown as ReturnType<typeof apiHooks.useParents>);

    mockedUseChildren.mockReturnValue({
      data: [
        {
          id: 'child-1',
          familyId: 'fam-1',
          fullName: 'Theo Rowe',
          dateOfBirth: '2016-04-22',
        },
      ],
    } as unknown as ReturnType<typeof apiHooks.useChildren>);

    mockedUseCurrentUser.mockReturnValue({
      data: {
        auth0Id: 'auth0|test-user',
        email: 'alex@example.com',
        profiles: [
          {
            id: 'parent-1',
            familyId: 'fam-1',
            fullName: 'Alex Rowe',
            role: 'primary',
            status: 'active',
          },
        ],
        isNewUser: false,
      },
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useCurrentUser>);

    mockedUseCreateMessageConversation.mockReturnValue({
      mutateAsync: createMessageMutate,
    } as unknown as ReturnType<typeof apiHooks.useCreateMessageConversation>);

    mockedUseCreatePermissionConversation.mockReturnValue({
      mutateAsync: createPermissionMutate,
    } as unknown as ReturnType<typeof apiHooks.useCreatePermissionConversation>);

    mockedUseSendMessage.mockReturnValue({ mutateAsync: vi.fn() } as unknown as ReturnType<
      typeof apiHooks.useSendMessage
    >);
    mockedUseMarkConversationRead.mockReturnValue({ mutateAsync: vi.fn() } as unknown as ReturnType<
      typeof apiHooks.useMarkConversationRead
    >);
    mockedUseMarkConversationUnread.mockReturnValue({
      mutateAsync: vi.fn(),
    } as unknown as ReturnType<typeof apiHooks.useMarkConversationUnread>);
    mockedUseApprovePermission.mockReturnValue({ mutateAsync: vi.fn() } as unknown as ReturnType<
      typeof apiHooks.useApprovePermission
    >);
    mockedUseDenyPermission.mockReturnValue({ mutateAsync: vi.fn() } as unknown as ReturnType<
      typeof apiHooks.useDenyPermission
    >);

    vi.spyOn(window, 'alert').mockImplementation(() => undefined);
  });

  it('renders conversation list', async () => {
    render(
      <MemoryRouter>
        <MessagesPage />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Existing thread' })).toBeInTheDocument();
    });
  });

  it('creates new message and permission conversations', async () => {
    const user = userEvent.setup();

    const promptMock = vi
      .spyOn(window, 'prompt')
      .mockImplementationOnce(() => 'School update')
      .mockImplementationOnce(() => 'Can you handle Thursday pickup?')
      .mockImplementationOnce(() => 'schedule')
      .mockImplementationOnce(() => 'Permission subject')
      .mockImplementationOnce(() => 'Please approve the schedule change.');

    render(
      <MemoryRouter>
        <MessagesPage />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'New message' }));
    await user.click(screen.getByRole('button', { name: 'New permission' }));

    await waitFor(() => {
      expect(createMessageMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          familyId: 'fam-1',
          subject: 'School update',
          message: 'Can you handle Thursday pickup?',
          recipientId: 'parent-2',
        }),
      );
    });

    expect(createPermissionMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        familyId: 'fam-1',
        subject: 'Permission subject',
        type: 'schedule',
        childId: 'child-1',
      }),
    );

    promptMock.mockRestore();
  });
});
