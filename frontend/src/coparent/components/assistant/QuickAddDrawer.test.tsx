import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { QuickAddDrawer } from './QuickAddDrawer';

const analyse = {
  mutateAsync: vi.fn(),
  isPending: false,
  isError: false,
  reset: vi.fn(),
};
let familyData = [{ id: 'family-1', name: 'Example Family' }];

vi.mock('../../hooks/api', () => ({
  useAnalyseAssistantInput: () => analyse,
  useAssistantBatch: () => ({ data: undefined, isLoading: false }),
  useAssistantBatches: () => ({
    data: [{
      id: 'private-batch', familyId: 'family-1', status: 'READY', actionCount: 2,
      createdAt: '2026-09-23T10:00:00Z', expiresAt: '2026-09-30T10:00:00Z',
    }],
  }),
  useAssistantEventCategories: () => ({ data: [] }),
  useChildren: () => ({ data: [] }),
  useConversations: () => ({ data: [] }),
  useCurrentUser: () => ({ data: { profiles: [{ id: 'parent-1', familyId: 'family-1' }] } }),
  useEvents: () => ({ data: [] }),
  useFamilies: () => ({ data: familyData }),
  useParents: () => ({ data: [] }),
  useScheduleChangeRequests: () => ({ data: [] }),
}));

vi.mock('../../lib/pwa/useOnlineStatus', () => ({
  useOnlineStatus: () => true,
}));

describe('QuickAddDrawer', () => {
  beforeEach(() => {
    analyse.mutateAsync.mockReset();
    analyse.reset.mockClear();
    analyse.isError = false;
    familyData = [{ id: 'family-1', name: 'Example Family' }];
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:preview'),
      revokeObjectURL: vi.fn(),
    });
  });

  it('keeps capture and review as separate wizard steps', async () => {
    const user = userEvent.setup({ applyAccept: false });
    render(<QuickAddDrawer open onClose={vi.fn()} />);

    expect(await screen.findByRole('heading', { name: 'What should we pick out?' }))
      .toBeInTheDocument();
    expect(screen.queryByText('Private to you in Example Family')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /2 actions/i })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /^2 Review actions/i }));
    expect(screen.getByRole('heading', { name: 'Review each proposed action' }))
      .toBeInTheDocument();
    expect(screen.getByText('Private to you in Example Family')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /2 actions/i })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'New input' }));
    await user.click(screen.getByRole('button', { name: 'Next: review actions' }));
    expect(screen.getByText('Add some text or choose an image first.')).toBeInTheDocument();

    const file = new File(['not an image'], 'note.gif', { type: 'image/gif' });
    await user.upload(screen.getByLabelText(/Add an image/i), file);
    expect(screen.getByText('Choose a JPEG, PNG, or WebP image.')).toBeInTheDocument();
    expect(analyse.mutateAsync).not.toHaveBeenCalled();
  });

  it('advances to review only after analysis succeeds', async () => {
    const user = userEvent.setup();
    analyse.mutateAsync.mockResolvedValue({ id: 'new-batch' });
    render(<QuickAddDrawer open onClose={vi.fn()} />);

    await user.type(
      await screen.findByRole('textbox', { name: /Note/i }),
      'School concert next Thursday',
    );
    await user.click(screen.getByRole('button', { name: 'Next: review actions' }));

    await waitFor(() => expect(analyse.mutateAsync).toHaveBeenCalled());
    expect(screen.getByRole('heading', { name: 'Review each proposed action' }))
      .toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: /Note/i })).not.toBeInTheDocument();
  });

  it('retains source text after failed analysis for a retry', async () => {
    const user = userEvent.setup();
    analyse.mutateAsync.mockRejectedValue(new Error('provider unavailable'));
    render(<QuickAddDrawer open onClose={vi.fn()} />);
    const input = await screen.findByPlaceholderText(/School photo day is next Thursday/i);
    await user.type(input, 'School concert next Thursday');
    await user.click(screen.getByRole('button', { name: 'Next: review actions' }));

    await waitFor(() => expect(analyse.mutateAsync).toHaveBeenCalledWith({
      familyId: 'family-1', text: 'School concert next Thursday', image: null,
    }));
    expect(input).toHaveValue('School concert next Thursday');
  });

  it('switches family, previews and removes an image, and clears input on close', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    familyData = [
      { id: 'family-1', name: 'Example Family' },
      { id: 'family-2', name: 'Second Family' },
    ];
    render(<QuickAddDrawer open onClose={onClose} />);

    await user.selectOptions(await screen.findByRole('combobox', { name: 'Family' }), 'family-2');
    const image = new File(['image'], 'flyer.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText(/Add an image/i), image);
    expect(screen.getByRole('img', { name: 'Selected upload preview' })).toHaveAttribute(
      'src', 'blob:preview',
    );
    await user.click(screen.getByRole('button', { name: 'Remove' }));
    expect(screen.queryByRole('img', { name: 'Selected upload preview' })).not.toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: /Note/i }), 'Keep this private');
    await user.click(screen.getByRole('button', { name: 'Close Quick add' }));
    expect(analyse.reset).toHaveBeenCalledOnce();
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('opens a selected recent private batch', async () => {
    const user = userEvent.setup();
    render(<QuickAddDrawer open onClose={vi.fn()} />);

    await user.click(await screen.findByRole('button', { name: /^2 Review actions/i }));
    await user.click(screen.getByRole('button', { name: /2 actions/i }));

    expect(screen.getByRole('heading', { name: 'Review each proposed action' }))
      .toBeInTheDocument();
  });
});
