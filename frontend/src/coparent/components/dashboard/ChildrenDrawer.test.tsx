import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { Child } from '../../types/dashboard';

import { ChildrenDrawer } from './ChildrenDrawer';

const children: Child[] = [
  {
    id: 'child-1',
    firstName: 'Theo',
    lastName: 'Rowe',
    birthdate: '2016-04-22',
    grade: '3',
    school: 'River Elementary',
    avatarUrl: null,
    allergies: ['Peanuts'],
    medicalNotes: 'Carries EpiPen',
  },
];

describe('ChildrenDrawer', () => {
  it('renders children list details', () => {
    render(<ChildrenDrawer children={children} isOpen={true} onClose={vi.fn()} />);

    expect(screen.getByText('Theo Rowe')).toBeInTheDocument();
    expect(screen.getByText(/River Elementary/)).toBeInTheDocument();
    expect(screen.getByText('Peanuts')).toBeInTheDocument();
  });

  it('shows empty state when no children exist', () => {
    render(<ChildrenDrawer children={[]} isOpen={true} onClose={vi.fn()} />);

    expect(screen.getByText('No child profiles yet.')).toBeInTheDocument();
  });

  it('triggers add and edit callbacks', async () => {
    const user = userEvent.setup();
    const onAddChild = vi.fn();
    const onEditChild = vi.fn();

    render(
      <ChildrenDrawer
        children={children}
        isOpen={true}
        onClose={vi.fn()}
        onAddChild={onAddChild}
        onEditChild={onEditChild}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Add new child' }));
    await user.click(screen.getByRole('button', { name: 'Edit' }));

    expect(onAddChild).toHaveBeenCalledTimes(1);
    expect(onEditChild).toHaveBeenCalledWith('child-1');
  });
});
