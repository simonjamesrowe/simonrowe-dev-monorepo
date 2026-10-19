import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { UserMenu } from './UserMenu';

const { configured, openCookieSettings } = vi.hoisted(() => ({
  configured: vi.fn(() => false),
  openCookieSettings: vi.fn(),
}));
vi.mock('../../../analytics/analytics', () => ({
  track: vi.fn(),
  isAnalyticsConfigured: () => configured(),
  openCookieSettings: () => openCookieSettings(),
}));

describe('UserMenu cookie settings', () => {
  beforeEach(() => {
    configured.mockReturnValue(false);
    openCookieSettings.mockClear();
  });

  it('offers nothing in a build without analytics', () => {
    render(<UserMenu user={{ name: 'Alex Rowe' }} onLogout={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cookie settings' })).not.toBeInTheDocument();
  });

  it('re-opens the consent choices beside Log out', () => {
    configured.mockReturnValue(true);
    render(<UserMenu user={{ name: 'Alex Rowe' }} onLogout={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'Cookie settings' }));
    expect(openCookieSettings).toHaveBeenCalledTimes(1);
  });
});
