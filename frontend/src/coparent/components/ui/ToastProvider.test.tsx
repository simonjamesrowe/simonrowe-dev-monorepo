import { act, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ToastProvider, useToast } from './ToastProvider';

function Trigger() {
  const { showToast } = useToast();
  return (
    <button type="button" onClick={() => showToast({ variant: 'success', title: 'Agreed' })}>
      Show
    </button>
  );
}

describe('ToastProvider', () => {
  it('renders toasts inside the .coparent-app scope its utility classes need', () => {
    // main.tsx mounts the provider above the app's own .coparent-app wrapper, as here.
    render(
      <ToastProvider>
        <div className="coparent-app">
          <Trigger />
        </div>
      </ToastProvider>,
    );

    act(() => screen.getByRole('button', { name: 'Show' }).click());

    // The fixed-position region must sit under a .coparent-app other than the app's own, which
    // it is rendered beside rather than inside.
    const region = screen.getByText('Agreed').closest('.fixed');
    expect(region).not.toBeNull();
    expect(region?.closest('.coparent-app')).not.toBeNull();
  });
});
