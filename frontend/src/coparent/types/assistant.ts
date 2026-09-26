export type AssistantActionType =
  | 'CREATE_EVENT'
  | 'UPDATE_EVENT'
  | 'DELETE_EVENT'
  | 'CREATE_CATEGORY'
  | 'UPDATE_CATEGORY'
  | 'DELETE_CATEGORY'
  | 'CREATE_SCHEDULE_CHANGE'
  | 'WITHDRAW_SCHEDULE_CHANGE'
  | 'START_MESSAGE_CONVERSATION'
  | 'SEND_MESSAGE'
  | 'CREATE_PERMISSION_REQUEST';

export type AssistantActionStatus =
  | 'BLOCKED'
  | 'PENDING'
  | 'APPLYING'
  | 'APPLIED'
  | 'REJECTED'
  | 'FAILED';

export interface AssistantFieldError {
  field: string;
  message: string;
}

export interface AssistantTargetSnapshot {
  entityType: string;
  entityId: string | null;
  observedUpdatedAt: string | null;
  hint: string | null;
}

export interface AssistantResultReference {
  entityType: string;
  entityId: string;
  route: string;
}

export interface AssistantAction {
  id: string;
  actionType: AssistantActionType;
  status: AssistantActionStatus;
  payload: Record<string, unknown>;
  fieldErrors: AssistantFieldError[];
  revision: number;
  targetSnapshot: AssistantTargetSnapshot | null;
  result: AssistantResultReference | null;
  failureMessage: string | null;
}

export interface AssistantBatchSummary {
  id: string;
  familyId: string;
  status: 'READY' | 'NO_ACTION' | 'FAILED';
  actionCount: number;
  createdAt: string;
  expiresAt: string;
}

export interface AssistantBatch extends AssistantBatchSummary {
  model: string;
  inputKinds: Array<'TEXT' | 'IMAGE'>;
  actions: AssistantAction[];
  updatedAt: string;
}
