import { Check, Mail, Pencil } from 'lucide-react';
import { useState } from 'react';

import { useInvitations, useRenameInvitedParent, useResendInvitation } from '../../hooks/api';
import type { Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import { useToast } from '../ui/ToastProvider';

import { firstName } from './parentTone';

/**
 * Shown while the co-parent has not accepted their invitation. Expenses can be logged against
 * them already, and until they join the parent here agrees to every one, whoever paid, and can
 * agree to them all at once. The primary parent can resend the invitation, and rename them
 * until they join and set their own name.
 */
export function InvitedCoparentBanner({
  familyId,
  invited,
  canManage,
  toAgree = 0,
  agreeing = false,
  onAgreeAll,
}: {
  familyId: string;
  invited: Parent;
  canManage: boolean;
  /** How many expenses are waiting for the parent here to agree. */
  toAgree?: number;
  agreeing?: boolean;
  onAgreeAll?: () => void;
}) {
  const { showToast } = useToast();
  const { data: invitations = [] } = useInvitations(familyId);
  const resend = useResendInvitation();
  const rename = useRenameInvitedParent();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(invited.fullName);
  const who = firstName(invited);
  const canAgreeAll = toAgree > 0 && onAgreeAll !== undefined;
  const invitation = invitations.find(
    (candidate) =>
      candidate.email.toLowerCase() === invited.email?.toLowerCase() &&
      (candidate.status === 'pending' || candidate.status === 'expired'),
  );

  const resendInvite = async () => {
    if (!invitation) return;
    try {
      await resend.mutateAsync({ id: invitation.id, familyId });
      showToast({ variant: 'success', title: 'Invitation sent again', description: `Sent to ${invited.email}.` });
    } catch (error) {
      showToast({ variant: 'error', title: 'That did not send', description: apiErrorMessage(error, 'Try again.') });
    }
  };

  const saveName = async () => {
    try {
      await rename.mutateAsync({ id: invited.id, familyId, fullName: name.trim() });
      setEditing(false);
    } catch (error) {
      showToast({ variant: 'error', title: 'Name not changed', description: apiErrorMessage(error, 'Try again.') });
    }
  };

  return (
    <section className="expense-invited" aria-label="Co-parent not joined yet">
      <Mail size={20} aria-hidden="true" className="expense-invited__icon" />
      <div className="expense-invited__body">
        <p className="expense-invited__title">{who} hasn&apos;t joined yet</p>
        <p className="expense-invited__text">
          Until {who} accepts the invitation sent to {invited.email}, you agree to every expense
          yourself, whoever paid, and settle up without waiting for them. Once they join, anything
          new waits for them to agree.
        </p>
        {editing && (
          <div className="expense-invited__rename">
            <input
              className="expense-input"
              aria-label="Their name"
              maxLength={100}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
            <button type="button" className="cp-button cp-button--primary cp-button--sm" disabled={!name.trim() || rename.isPending} onClick={saveName}>
              Save name
            </button>
            <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={() => { setEditing(false); setName(invited.fullName); }}>
              Cancel
            </button>
          </div>
        )}
      </div>
      {(canAgreeAll || (canManage && !editing)) && (
        <div className="expense-invited__actions">
          {canAgreeAll && (
            <button type="button" className="cp-button cp-button--primary cp-button--sm" disabled={agreeing} onClick={onAgreeAll}>
              <Check size={14} aria-hidden="true" /> {agreeing ? 'Agreeing…' : `Agree all ${toAgree}`}
            </button>
          )}
          {canManage && !editing && (
            <>
              <button type="button" className="cp-button cp-button--secondary cp-button--sm" onClick={() => setEditing(true)}>
                <Pencil size={14} aria-hidden="true" /> Change name
              </button>
              {invitation && (
                <button type="button" className="cp-button cp-button--secondary cp-button--sm" disabled={resend.isPending} onClick={resendInvite}>
                  Resend invitation
                </button>
              )}
            </>
          )}
        </div>
      )}
    </section>
  );
}
