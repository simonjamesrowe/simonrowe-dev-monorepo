import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { InstallPrompt } from './InstallPrompt';

vi.mock('../../lib/pwa/usePWAInstall', () => ({
  usePWAInstall: () => ({ canInstall: true, isInstalled: false, promptInstall: vi.fn() }),
}));

describe('InstallPrompt', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => window.localStorage.clear());

  it('stays dismissed after "Not Now" when the page is loaded again', async () => {
    const user = userEvent.setup();
    const { unmount } = render(<InstallPrompt />);

    await user.click(screen.getByRole('button', { name: 'Not Now' }));
    expect(screen.queryByText('Install CoParent')).not.toBeInTheDocument();

    unmount();
    render(<InstallPrompt />);
    expect(screen.queryByText('Install CoParent')).not.toBeInTheDocument();
  });

  it('asks again once the snooze has passed', () => {
    window.localStorage.setItem(
      'coparent.installPrompt.dismissedAt',
      String(Date.now() - 31 * 24 * 60 * 60 * 1000),
    );
    render(<InstallPrompt />);

    expect(screen.getByText('Install CoParent')).toBeInTheDocument();
  });
});
