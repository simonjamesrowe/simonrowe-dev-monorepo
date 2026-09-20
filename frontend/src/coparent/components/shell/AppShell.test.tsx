import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { AppShell } from './AppShell';

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
});
