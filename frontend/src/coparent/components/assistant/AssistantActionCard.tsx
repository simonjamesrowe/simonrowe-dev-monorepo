import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, Check, ChevronDown, ExternalLink, RotateCcw, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import {
  useApproveAssistantAction,
  useEditAssistantAction,
  useRejectAssistantAction,
} from '../../hooks/api/useAssistant';
import type { AssistantAction } from '../../types/assistant';

export interface AssistantEditorOption {
  value: string;
  label: string;
}

export type AssistantEditorOptions = Partial<Record<string, AssistantEditorOption[]>>;

const EMPTY_OPTIONS: AssistantEditorOptions = {};

const labels: Record<string, string> = {
  CREATE_EVENT: 'Create event',
  UPDATE_EVENT: 'Update event',
  DELETE_EVENT: 'Delete event',
  CREATE_CATEGORY: 'Create category',
  UPDATE_CATEGORY: 'Update category',
  DELETE_CATEGORY: 'Delete category',
  CREATE_SCHEDULE_CHANGE: 'Request schedule change',
  WITHDRAW_SCHEDULE_CHANGE: 'Withdraw schedule request',
  START_MESSAGE_CONVERSATION: 'Start conversation',
  SEND_MESSAGE: 'Send message',
  CREATE_PERMISSION_REQUEST: 'Create permission request',
};

const requiredFields: Record<string, string[]> = {
  CREATE_EVENT: ['title', 'startDate', 'childIds'],
  UPDATE_EVENT: ['eventId'],
  DELETE_EVENT: ['eventId'],
  CREATE_CATEGORY: ['name', 'icon'],
  UPDATE_CATEGORY: ['categoryId'],
  DELETE_CATEGORY: ['categoryId'],
  CREATE_SCHEDULE_CHANGE: ['type', 'newStartDate', 'newEndDate', 'reason'],
  WITHDRAW_SCHEDULE_CHANGE: ['requestId'],
  START_MESSAGE_CONVERSATION: ['message'],
  SEND_MESSAGE: ['conversationId', 'message'],
  CREATE_PERMISSION_REQUEST: ['type', 'childId', 'description'],
};

function editorSchema(action: AssistantAction) {
  return z.record(z.string(), z.unknown()).superRefine((payload, context) => {
    requiredFields[action.actionType]?.forEach((field) => {
      const value = payload[field];
      if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) {
        context.addIssue({ code: 'custom', path: [field], message: 'This field is required' });
      }
    });
  });
}

function editablePayload(payload: Record<string, unknown>, options: AssistantEditorOptions) {
  return Object.fromEntries(
    Object.entries(payload).map(([key, value]) => [
      key,
      Array.isArray(value) && !options[key] ? value.join(', ') : value,
    ]),
  );
}

function normalizedPayload(payload: Record<string, unknown>) {
  return Object.fromEntries(
    Object.entries(payload).map(([key, value]) => {
      if (key.endsWith('Ids') || key === 'recurringDays') {
        return [key, typeof value === 'string'
          ? value.split(',').map((item) => item.trim()).filter(Boolean)
          : value];
      }
      return [key, value === '' ? null : value];
    }),
  );
}

export function AssistantActionCard({
  familyId,
  batchId,
  action,
  online,
  options = EMPTY_OPTIONS,
}: {
  familyId: string;
  batchId: string;
  action: AssistantAction;
  online: boolean;
  options?: AssistantEditorOptions;
}) {
  const [expanded, setExpanded] = useState(action.status === 'BLOCKED');
  const schema = useMemo(() => editorSchema(action), [action]);
  const edit = useEditAssistantAction();
  const approve = useApproveAssistantAction();
  const reject = useRejectAssistantAction();
  const terminal = action.status === 'APPLIED' || action.status === 'REJECTED';
  const isDelete = action.actionType.startsWith('DELETE_')
    || action.actionType === 'WITHDRAW_SCHEDULE_CHANGE';
  const showEditor = !terminal && (!isDelete || action.status === 'BLOCKED');
  const form = useForm<Record<string, unknown>>({
    resolver: zodResolver(schema),
    defaultValues: editablePayload(action.payload, options),
  });

  useEffect(() => {
    form.reset(editablePayload(action.payload, options));
  }, [action.payload, form, options]);

  const save = form.handleSubmit(async (payload) => {
    await edit.mutateAsync({
      familyId,
      batchId,
      action: { ...action, payload: normalizedPayload(payload) },
    });
  });

  const decide = (kind: 'approve' | 'reject') => {
    const mutation = kind === 'approve' ? approve : reject;
    mutation.mutate({ familyId, batchId, action });
  };

  const busy = edit.isPending || approve.isPending || reject.isPending || action.status === 'APPLYING';
  const mutationError = edit.error || approve.error || reject.error;

  return (
    <article className={`assistant-card assistant-card--${action.status.toLowerCase()}`}>
      <button className="assistant-card__summary" type="button" onClick={() => setExpanded(!expanded)}>
        <span className="assistant-card__rail" aria-hidden="true" />
        <span>
          <span className="assistant-card__type">{labels[action.actionType]}</span>
          <span className="assistant-card__status">{action.status.toLowerCase()}</span>
        </span>
        <ChevronDown className={expanded ? 'assistant-card__chevron--open' : ''} size={18} />
      </button>

      {expanded && (
        <div className="assistant-card__body">
          {action.targetSnapshot?.hint && !action.targetSnapshot.entityId && (
            <p className="assistant-card__notice">
              <AlertTriangle size={16} /> Suggested target: {action.targetSnapshot.hint}
            </p>
          )}
          {action.fieldErrors.length > 0 && (
            <ul className="assistant-card__errors">
              {action.fieldErrors.map((error) => (
                <li key={`${error.field}-${error.message}`}>{error.message}</li>
              ))}
            </ul>
          )}

          {showEditor && (
            <form className="assistant-card__form" onSubmit={save}>
              {Object.entries(action.payload).map(([key, value]) => (
                <label key={key} className="assistant-field">
                  <span>{key.replace(/([A-Z])/g, ' $1').replace(/^./, (letter) => letter.toUpperCase())}</span>
                  {options[key] ? (
                    <select multiple={key.endsWith('Ids')} {...form.register(key)}>
                      {!key.endsWith('Ids') && <option value="">Select…</option>}
                      {options[key]?.map((option) => (
                        <option key={option.value} value={option.value}>{option.label}</option>
                      ))}
                    </select>
                  ) : typeof value === 'boolean' ? (
                    <input type="checkbox" {...form.register(key)} />
                  ) : key === 'message' || key === 'notes' || key === 'description' || key === 'reason' ? (
                    <textarea rows={3} {...form.register(key)} />
                  ) : (
                    <input {...form.register(key)} />
                  )}
                  {form.formState.errors[key]?.message && (
                    <small>{String(form.formState.errors[key]?.message)}</small>
                  )}
                </label>
              ))}
              <button className="assistant-button assistant-button--secondary" disabled={!online || busy} type="submit">
                Save changes
              </button>
            </form>
          )}

          {action.failureMessage && (
            <p className="assistant-card__notice"><RotateCcw size={16} /> {action.failureMessage}</p>
          )}
          {mutationError && <p className="assistant-card__errors">That request failed. Try again.</p>}

          <div className="assistant-card__actions">
            {!terminal && (
              <>
                <button
                  className="assistant-button assistant-button--approve"
                  type="button"
                  disabled={!online || busy || action.status === 'BLOCKED'}
                  onClick={() => decide('approve')}
                >
                  <Check size={16} /> {action.status === 'FAILED' ? 'Retry' : 'Approve'}
                </button>
                <button
                  className="assistant-button assistant-button--reject"
                  type="button"
                  disabled={!online || busy}
                  onClick={() => decide('reject')}
                >
                  <X size={16} /> Reject
                </button>
              </>
            )}
            {action.result && (
              <a className="assistant-card__result" href={action.result.route}>
                View result <ExternalLink size={14} />
              </a>
            )}
          </div>
        </div>
      )}
    </article>
  );
}
