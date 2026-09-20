import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios';
import axios from 'axios';

const API_URL = '/api/coparent';

// Create axios instance
const apiClient: AxiosInstance = axios.create({
  baseURL: API_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Token getter function - will be set by the auth hook
let getAccessToken: (() => Promise<string>) | null = null;

export function setTokenGetter(getter: () => Promise<string>) {
  getAccessToken = getter;
}

// Request interceptor to add auth token
apiClient.interceptors.request.use(
  async (config: InternalAxiosRequestConfig) => {
    if (getAccessToken) {
      try {
        const token = await getAccessToken();
        if (token) {
          config.headers.Authorization = `Bearer ${token}`;
        }
      } catch (error) {
        console.error('Failed to get access token:', error);
      }
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  },
);

// Response interceptor for error handling
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // Token expired or invalid - could trigger re-authentication
      console.error('Authentication error - token may be expired');
    }
    return Promise.reject(error);
  },
);

export { apiClient };

// Type definitions for API responses
export type ParentRole = 'primary' | 'co-parent';
export type InvitationStatus = 'pending' | 'accepted' | 'expired' | 'canceled';
export type OnboardingStep = 'family' | 'child' | 'invite' | 'review' | 'complete';
export type ConversationType = 'message' | 'permission';
export type PermissionRequestType = 'medical' | 'travel' | 'schedule' | 'extracurricular';
export type PermissionStatus = 'pending' | 'approved' | 'denied';

export interface Family {
  id: string;
  name: string;
  timeZone: string;
  parentIds: string[];
  childIds: string[];
  invitationIds: string[];
  createdAt: string;
}

export interface Parent {
  id: string;
  familyId: string | null;
  fullName: string;
  email?: string;
  role: ParentRole;
  status: string;
  auth0Id?: string;
  color?: string;
  avatarUrl?: string;
  lastSignedInAt?: string;
}

export interface Child {
  id: string;
  familyId: string;
  fullName: string;
  dateOfBirth: string;
  school?: string;
  medicalNotes?: string;
}

export interface Invitation {
  id: string;
  familyId: string;
  email: string;
  role: ParentRole;
  status: InvitationStatus;
  sentAt: string;
  expiresAt: string;
  acceptedAt?: string;
  canceledAt?: string;
}

export interface OnboardingState {
  id?: string;
  familyId: string;
  currentStep: OnboardingStep;
  completedSteps: OnboardingStep[];
  isComplete: boolean;
  lastUpdated?: string | null;
}

export interface CurrentUserProfile {
  id: string;
  familyId: string;
  fullName: string;
  role: ParentRole;
  status: string;
}

export interface CurrentUser {
  auth0Id: string;
  email: string;
  profiles: CurrentUserProfile[];
  isNewUser: boolean;
}

export interface UpdatedCurrentUserProfile {
  id: string;
  fullName: string;
  email?: string;
  role: ParentRole;
  status: string;
}

export interface ConversationParticipant {
  id: string;
  name: string;
  avatarUrl?: string | null;
}

export interface Message {
  id: string;
  senderId: string;
  content: string;
  timestamp: string;
  isRead: boolean;
  deliveryStatus: 'sent' | 'delivered' | 'read';
}

export interface PermissionRequest {
  id: string;
  type: PermissionRequestType;
  childId: string;
  childName: string;
  description: string;
  requestedBy: string;
  status: PermissionStatus;
  createdAt: string;
  resolvedAt: string | null;
  response: string | null;
}

export interface Conversation {
  id: string;
  type: ConversationType;
  subject: string;
  lastMessageAt: string;
  unreadCount: number;
  participants: {
    parent1: ConversationParticipant;
    parent2: ConversationParticipant;
  };
  messages?: Message[];
  permissionRequest?: PermissionRequest;
}

// API request/response types
export interface CreateFamilyRequest {
  name: string;
  timeZone: string;
  fullName?: string;
}

export interface UpdateFamilyRequest {
  name?: string;
  timeZone?: string;
}

export interface CreateChildRequest {
  fullName: string;
  dateOfBirth: string;
  school?: string;
  medicalNotes?: string;
}

export interface UpdateChildRequest {
  fullName?: string;
  dateOfBirth?: string;
  school?: string;
  medicalNotes?: string;
}

export interface CreateInvitationRequest {
  email: string;
  role: ParentRole;
}

export interface UpdateOnboardingRequest {
  currentStep?: OnboardingStep;
  completedSteps?: OnboardingStep[];
  isComplete?: boolean;
}

export interface CompleteStepRequest {
  step: OnboardingStep;
}

export interface UpdateParentRoleRequest {
  role: ParentRole;
}

export interface UpdateCurrentUserRequest {
  fullName?: string;
}

export interface CreateMessageConversationRequest {
  subject?: string;
  message: string;
  recipientId?: string;
}

export interface CreatePermissionConversationRequest {
  subject?: string;
  type: PermissionRequestType;
  childId?: string;
  childName?: string;
  description: string;
}

export interface SendMessageRequest {
  content: string;
}

export interface PermissionResponseRequest {
  response?: string;
}
