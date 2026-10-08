import { Mail, Pencil } from 'lucide-react';
import { useState } from 'react';

import { useInvitations, useRenameInvitedParent, useResendInvitation } from '../../hooks/api';
import type { Parent } from '../../lib/api/client';
import { apiErrorMessage } from '../../lib/api/errorMessage';
import { useToast } from '../ui/ToastProvider';

import { firstName } from './parentTone';

/**
 * Shown while the co-parent has not accepted their invitation. Expenses can be logged against
 * them already; they wait for that parent to agree, one by one, once they join. The primary
 * parent can resend the invitation, and rename them until they join and set their own name.
 */
export function InvitedCoparentBanner({
  familyId,
  invited,
  canManage,
}: {
  familyId: string;
  invited: Parent;
  canManage: boolean;
}) {
  const { showToast } = useToast();
  const { data: invitations = [] } = useInvitations(familyId);
  const resend = useResendInvitation();
  const rename = useRenameInvitedParent();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(invited.fullName);
  const who = firstName(invited);
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
          Add expenses now, whoever paid. Each one waits for {who} to agree once they accept the
          invitation sent to {invited.email}. Nothing counts towards the balance until then.
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
            <button type="button" className="expense-button expense-button--primary expense-button--sm" disabled={!name.trim() || rename.isPending} onClick={saveName}>
              Save name
            </button>
            <button type="button" className="expense-button expense-button--ghost expense-button--sm" onClick={() => { setEditing(false); setName(invited.fullName); }}>
              Cancel
            </button>
          </div>
        )}
      </div>
      {canManage && !editing && (
        <div className="expense-invited__actions">
          <button type="button" className="expense-button expense-button--ghost expense-button--sm" onClick={() => setEditing(true)}>
            <Pencil size={14} aria-hidden="true" /> Change name
          </button>
          {invitation && (
            <button type="button" className="expense-button expense-button--teal expense-button--sm" disabled={resend.isPending} onClick={resendInvite}>
              Resend invite
            </button>
          )}
        </div>
      )}
    </section>
  );
}
