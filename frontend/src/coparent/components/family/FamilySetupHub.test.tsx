import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import type { Child, Family, Invitation, Parent } from '../../lib/api/client';

import { FamilySetupHub } from './FamilySetupHub';

const family: Family = {
  id: 'fam-1',
  name: 'Rowe Family',
  timeZone: 'America/Los_Angeles',
  parentIds: [],
  childIds: [],
  invitationIds: [],
  createdAt: '2026-02-01T00:00:00Z',
};

const parent: Parent = {
  id: 'par-1',
  familyId: 'fam-1',
  fullName: 'Alex Rowe',
  email: 'alex@example.com',
  role: 'primary',
  status: 'active',
  auth0Id: 'auth0|1',
  color: '#0d9488',
  avatarUrl: undefined,
  lastSignedInAt: '2026-02-01T00:00:00Z',
};

const child: Child = {
  id: 'chi-1',
  familyId: 'fam-1',
  fullName: 'Theo Rowe',
  dateOfBirth: '2015-01-01',
  school: 'Test School',
  medicalNotes: 'None',
};

const invitation: Invitation = {
  id: 'inv-1',
  familyId: 'fam-1',
  email: 'sam@example.com',
  role: 'co-parent',
  status: 'pending',
  sentAt: '2026-02-01T00:00:00Z',
  expiresAt: '2026-02-08T00:00:00Z',
};

describe('FamilySetupHub child editor', () => {
  it('opens editor via childIdToEdit and saves updates', async () => {
    const user = userEvent.setup();
    const onUpdateChild = vi.fn();
    const onCloseChildEditor = vi.fn();

    render(
      <FamilySetupHub
        families={[family]}
        parents={[parent]}
        children={[child]}
        invitations={[] as Invitation[]}
        activeFamilyId="fam-1"
        childIdToEdit="chi-1"
        onCloseChildEditor={onCloseChildEditor}
        onUpdateChild={onUpdateChild}
      />,
    );

    const editor = within(screen.getByRole('dialog', { name: 'Theo Rowe' }));
    const dob = editor.getByLabelText('Date of Birth');
    const fullName = editor.getByLabelText('Full Name');
    const school = editor.getByLabelText('School (optional)');
    const medicalNotes = editor.getByLabelText('Medical Notes (optional)');
    await user.clear(fullName);
    await user.type(fullName, 'Theo James Rowe');
    await user.clear(dob);
    await user.type(dob, '2014-12-31');
    await user.clear(school);
    await user.type(school, 'New School');
    await user.clear(medicalNotes);
    await user.type(medicalNotes, 'Updated notes');

    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(onUpdateChild).toHaveBeenCalledWith(
      'chi-1',
      expect.objectContaining({
        fullName: 'Theo James Rowe',
        dateOfBirth: '2014-12-31',
        school: 'New School',
        medicalNotes: 'Updated notes',
      }),
    );
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onCloseChildEditor).toHaveBeenCalledTimes(1);
  });

  it('manages family members and invitations', async () => {
    const user = userEvent.setup();
    const onUpdateFamily = vi.fn();
    const onAssignRole = vi.fn();
    const onAddChild = vi.fn();
    const onInviteCoParent = vi.fn();
    const onResendInvite = vi.fn();
    const onCancelInvite = vi.fn();

    const { container } = render(
      <FamilySetupHub
        families={[family]}
        parents={[parent]}
        children={[child]}
        invitations={[invitation]}
        activeFamilyId="fam-1"
        onUpdateFamily={onUpdateFamily}
        onAssignRole={onAssignRole}
        onAddChild={onAddChild}
        onInviteCoParent={onInviteCoParent}
        onResendInvite={onResendInvite}
        onCancelInvite={onCancelInvite}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Edit' }));

    const parentRow = screen.getByText('alex@example.com').closest('div.flex.items-center')
      ?.parentElement;
    const roleButton = parentRow?.querySelector('button');
    expect(roleButton).not.toBeNull();
    await user.click(roleButton as HTMLButtonElement);

    await user.type(screen.getByPlaceholderText('First and last name'), 'Jamie Rowe');
    const dateInput = container.querySelector('input[type="date"]');
    expect(dateInput).not.toBeNull();
    await user.type(dateInput as HTMLInputElement, '2018-03-04');
    await user.type(screen.getByPlaceholderText('School name'), 'River School');
    await user.type(
      screen.getByPlaceholderText('Allergies, medications, or notes'),
      'Nut allergy',
    );
    await user.click(screen.getByRole('button', { name: 'Add Child' }));

    await user.type(screen.getByPlaceholderText('coparent@example.com'), 'new@example.com');
    await user.selectOptions(screen.getByRole('combobox'), 'primary');
    await user.click(screen.getByRole('button', { name: 'Send Invite' }));

    const invitationCard = screen.getByText('sam@example.com').closest('div.rounded-xl');
    expect(invitationCard).not.toBeNull();
    await user.click(within(invitationCard as HTMLElement).getByRole('button', { name: 'Resend' }));
    await user.click(within(invitationCard as HTMLElement).getByRole('button', { name: 'Cancel' }));

    expect(onUpdateFamily).toHaveBeenCalledWith('fam-1', {});
    expect(onAssignRole).toHaveBeenCalledWith('par-1', 'co-parent');
    expect(onAddChild).toHaveBeenCalledWith({
      fullName: 'Jamie Rowe',
      dateOfBirth: '2018-03-04',
      school: 'River School',
      medicalNotes: 'Nut allergy',
    });
    expect(onInviteCoParent).toHaveBeenCalledWith('fam-1', 'new@example.com', 'primary');
    expect(onResendInvite).toHaveBeenCalledWith('inv-1');
    expect(onCancelInvite).toHaveBeenCalledWith('inv-1');
    expect(container).toHaveTextContent('Rowe Family');

    const childCard = screen.getByText('Theo Rowe').closest('div.group');
    const editChildButton = childCard?.querySelector('button');
    expect(editChildButton).not.toBeNull();
    await user.click(editChildButton as HTMLButtonElement);
    const editor = screen.getByText('Edit child').closest('div.fixed');
    expect(editor).not.toBeNull();
    await user.click(within(editor as HTMLElement).getByRole('button', { name: 'Cancel' }));
  });
});
