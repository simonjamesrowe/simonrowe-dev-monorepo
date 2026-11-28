import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import SettingsPage from './SettingsPage';
import TimelinePage from './TimelinePage';

describe('CoParent placeholder pages', () => {
  it.each([
    ['Timeline and photos', TimelinePage],
    ['Settings', SettingsPage],
  ])('renders the %s route while its full module is pending', (heading, Page) => {
    render(<Page />);

    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument();
  });
});
