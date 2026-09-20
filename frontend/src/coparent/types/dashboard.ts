// =============================================================================
// Dashboard Types (UI-facing)
// =============================================================================

export interface NotificationPreferences {
  email: boolean;
  sms: boolean;
  push: boolean;
}

export type ParentRole = 'primary' | 'secondary' | 'caregiver';

export interface Parent {
  id: string;
  fullName: string;
  email: string;
  role: ParentRole;
  phone: string;
  avatarUrl: string | null;
  lastActiveAt: string;
  notificationPreferences: NotificationPreferences;
}

export interface Child {
  id: string;
  firstName: string;
  lastName: string;
  birthdate: string;
  grade: string;
  school: string;
  avatarUrl: string | null;
  allergies: string[];
  medicalNotes: string;
}

export interface Family {
  id: string;
  name: string;
  timezone: string;
  primaryParentId: string;
  secondaryParentId: string;
  childIds: string[];
  createdAt: string;
  setupProgress: number;
}

export type EventStatus = 'confirmed' | 'tentative' | 'canceled';
export type EventType = 'custody' | 'activity' | 'appointment' | 'school' | 'holiday';

export interface Event {
  id: string;
  title: string;
  type: EventType;
  startAt: string;
  endAt: string;
  location: string;
  childId: string | null;
  status: EventStatus;
  notes: string;
}

export type ExpenseStatus = 'pending' | 'approved' | 'reimbursed' | 'denied';

export interface Expense {
  id: string;
  title: string;
  category: string;
  amount: number;
  currency: string;
  date: string;
  status: ExpenseStatus;
  paidByParentId: string;
  childId: string | null;
  receiptUrls: string[];
  description: string;
}

export type PermissionRequestType =
  | 'schedule-change'
  | 'activity'
  | 'purchase'
  | 'travel'
  | 'medical';

export type PermissionRequestStatus = 'pending' | 'approved' | 'denied';

export interface PermissionRequest {
  id: string;
  title: string;
  type: PermissionRequestType;
  status: PermissionRequestStatus;
  requestedByParentId: string;
  resolvedByParentId: string | null;
  requestedAt: string;
  resolvedAt: string | null;
  summary: string;
  relatedEntityType: 'event' | 'expense' | 'document';
  relatedEntityId: string;
}

export interface Message {
  id: string;
  threadId: string;
  fromParentId: string;
  toParentId: string;
  subject: string;
  preview: string;
  sentAt: string;
  unread: boolean;
}

export type InvitationRole = 'co-parent' | 'caregiver';
export type InvitationStatus = 'pending' | 'accepted' | 'expired' | 'canceled';

export interface Invitation {
  id: string;
  email: string;
  role: InvitationRole;
  status: InvitationStatus;
  sentAt: string;
  expiresAt: string;
  invitedByParentId: string;
}

export type ActivityFeedItemType =
  | 'event'
  | 'expense'
  | 'message'
  | 'permission'
  | 'document'
  | 'family';

export interface ActivityFeedItem {
  id: string;
  type: ActivityFeedItemType;
  title: string;
  timestamp: string;
  summary: string;
  actorParentId: string;
  entityType: 'event' | 'expense' | 'message' | 'permissionRequest' | 'document' | 'family';
  entityId: string;
}

export interface BudgetCategory {
  category: string;
  limit: number;
  spent: number;
}

export interface BudgetSummary {
  month: string;
  currency: string;
  totalLimit: number;
  totalSpent: number;
  remaining: number;
  categories: BudgetCategory[];
}

export interface ApprovalsSummary {
  totalPending: number;
  byType: {
    expenses: number;
    scheduleChanges: number;
    permissions: number;
  };
}

export interface SetupChecklistItem {
  id: string;
  label: string;
  completed: boolean;
}

export interface SetupChecklist {
  completedCount: number;
  totalCount: number;
  items: SetupChecklistItem[];
}

export type WidgetCardSize = 'sm' | 'md' | 'lg';
export type WidgetCardTrend = 'up' | 'down' | 'flat';

export interface WidgetCard {
  id: string;
  title: string;
  value: string;
  description: string;
  trend: WidgetCardTrend;
  delta: string;
  size: WidgetCardSize;
  sectionId: string;
}

export type QuickActionId = 'add-expense' | 'create-event' | 'send-message';

export interface QuickAction {
  id: QuickActionId;
  label: string;
  helper: string;
  shortcut: string;
}

export interface ParentProfileUpdate {
  fullName: string;
  email: string;
  phone: string;
  notificationPreferences: NotificationPreferences;
}

// =============================================================================
// Component Props
// =============================================================================

export interface DashboardProps {
  family: Family;
  parents: Parent[];
  children: Child[];
  upcomingEvents: Event[];
  permissionRequests: PermissionRequest[];
  expenses: Expense[];
  messages: Message[];
  invitations: Invitation[];
  activityFeed: ActivityFeedItem[];
  budgetSummary: BudgetSummary;
  approvalsSummary: ApprovalsSummary;
  setupChecklist: SetupChecklist;
  widgetCards: WidgetCard[];
  quickActions: QuickAction[];

  onOpenProfileDrawer?: () => void;
  onOpenChildrenDrawer?: () => void;
  onOpenInvitationsDrawer?: () => void;

  onAddChild?: () => void;
  onEditChild?: (childId: string) => void;

  onResendInvitation?: (invitationId: string) => void;
  onCancelInvitation?: (invitationId: string) => void;

  onNavigateSection?: (sectionId: string) => void;
  onViewApproval?: (permissionRequestId: string) => void;
  onViewEvent?: (eventId: string) => void;
  onViewExpense?: (expenseId: string) => void;
  onOpenMessageThread?: (threadId: string) => void;

  onQuickAddExpense?: () => void;
  onQuickCreateEvent?: () => void;
  onQuickSendMessage?: () => void;
}

export interface ProfileDrawerProps {
  parent: Parent;
  family: Family;
  isOpen: boolean;
  onClose: () => void;
  onSaveProfile?: (parentId: string, update: ParentProfileUpdate) => void;
}

export interface ChildrenDrawerProps {
  children: Child[];
  isOpen: boolean;
  onClose: () => void;
  onAddChild?: () => void;
  onEditChild?: (childId: string) => void;
}

export interface InvitationsDrawerProps {
  invitations: Invitation[];
  parents: Parent[];
  isOpen: boolean;
  onClose: () => void;
  onResendInvitation?: (invitationId: string) => void;
  onCancelInvitation?: (invitationId: string) => void;
}
