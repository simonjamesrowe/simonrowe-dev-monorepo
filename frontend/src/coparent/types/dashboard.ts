// =============================================================================
// Profile drawer types (UI-facing)
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

export interface ParentProfileUpdate {
  fullName: string;
  email: string;
  phone: string;
  notificationPreferences: NotificationPreferences;
}

export interface ProfileDrawerProps {
  parent: Parent;
  family: Family;
  isOpen: boolean;
  onClose: () => void;
  onSaveProfile?: (parentId: string, update: ParentProfileUpdate) => void;
}
