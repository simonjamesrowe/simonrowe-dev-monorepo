import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { AppShell } from './AppShell';

vi.mock('../assistant', () => ({
  QuickAddDrawer: ({ onClose }: { onClose: () => void }) => (
    <button type="button" onClick={onClose}>Close assistant drawer</button>
  ),
}));

describe('AppShell', () => {
  it('gives every navigation control an accessible name', async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();

    render(
      <AppShell
        navigationItems={[
          { label: 'Dashboard', href: '/dashboard' },
          { label: 'Calendar', href: '/calendar' },
          { label: 'Settings', href: '/settings' },
        ]}
        onNavigate={onNavigate}
      >
        <p>Content</p>
      </AppShell>,
    );

    await user.click(screen.getByRole('button', { name: 'Calendar' }));

    expect(screen.getByRole('button', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Settings' })).toBeInTheDocument();
    expect(onNavigate).toHaveBeenCalledWith('/calendar');
  });

  it('opens and closes the mobile navigation', async () => {
    const user = userEvent.setup();

    const { container } = render(
      <AppShell navigationItems={[]}>
        <p>Content</p>
      </AppShell>,
    );

    const toggle = screen.getByRole('button', { name: 'Toggle menu' });
    const sidebar = container.querySelector('aside');
    expect(sidebar).not.toBeNull();
    expect(sidebar).toHaveClass('invisible');

    await user.click(toggle);
    expect(sidebar).toHaveClass('visible');

    await user.click(toggle);
    expect(sidebar).toHaveClass('invisible');
  });

  it('shows desktop and mobile Quick add launchers only when enabled', () => {
    render(
      <AppShell navigationItems={[]} assistantEnabled>
        <p>Content</p>
      </AppShell>,
    );

    expect(screen.getAllByRole('button', { name: /Quick add/i })).toHaveLength(2);
  });

  it('opens and closes Quick add from both responsive launchers', async () => {
    const user = userEvent.setup();
    render(
      <AppShell navigationItems={[]} assistantEnabled>
        <p>Content</p>
      </AppShell>,
    );

    const launchers = screen.getAllByRole('button', { name: /Quick add/i });
    await user.click(launchers[0]);
    await user.click(screen.getByRole('button', { name: 'Close assistant drawer' }));
    await user.click(launchers[1]);

    expect(screen.getByRole('button', { name: 'Close assistant drawer' })).toBeInTheDocument();
  });

  it('formats the idle countdown and closes the menu through its overlay', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <AppShell navigationItems={[]} showIdleCountdown idleCountdownSeconds={125}>
        <p>Content</p>
      </AppShell>,
    );

    expect(screen.getByText('Auto logout 2:05')).toBeInTheDocument();
    expect(screen.getByText('Auto logout in 2:05')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Toggle menu' }));
    await user.click(screen.getByRole('button', { name: 'Close navigation menu' }));

    expect(container.querySelector('aside')).toHaveClass('invisible');
  });
});
