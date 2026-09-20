import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import * as apiHooks from '../../hooks/api';

import { OnboardingGuard } from './OnboardingGuard';

vi.mock('../../hooks/api', () => ({
  useFamilies: vi.fn(),
  useOnboarding: vi.fn(),
  useInvitations: vi.fn(),
}));

const mockedUseFamilies = vi.mocked(apiHooks.useFamilies);
const mockedUseOnboarding = vi.mocked(apiHooks.useOnboarding);
const mockedUseInvitations = vi.mocked(apiHooks.useInvitations);

function renderGuard() {
  return render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <Routes>
        <Route
          path="/dashboard"
          element={
            <OnboardingGuard>
              <div>Dashboard content</div>
            </OnboardingGuard>
          }
        />
        <Route path="/onboarding" element={<div>Onboarding page</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('OnboardingGuard', () => {
  beforeEach(() => {
    vi.clearAllMocks();

    mockedUseFamilies.mockReturnValue({
      data: [{ id: 'fam-1', name: 'Family' }],
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useFamilies>);

    mockedUseOnboarding.mockReturnValue({
      data: {
        familyId: 'fam-1',
        currentStep: 'complete',
        completedSteps: ['family', 'child', 'invite', 'review'],
        isComplete: true,
      },
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useOnboarding>);

    mockedUseInvitations.mockReturnValue({
      data: [
        {
          id: 'invite-1',
          familyId: 'fam-1',
          email: 'coparent@coparent.dev',
          role: 'co-parent',
          status: 'pending',
          sentAt: '2026-01-01T00:00:00.000Z',
          expiresAt: '2026-01-08T00:00:00.000Z',
        },
      ],
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useInvitations>);
  });

  it('renders children when onboarding is complete and invitations exist', async () => {
    renderGuard();

    await waitFor(() => {
      expect(screen.getByText('Dashboard content')).toBeInTheDocument();
    });
  });

  it('renders children when onboarding is complete and invitations are empty', async () => {
    mockedUseInvitations.mockReturnValue({
      data: [],
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useInvitations>);

    renderGuard();

    await waitFor(() => {
      expect(screen.getByText('Dashboard content')).toBeInTheDocument();
    });
  });

  it('redirects to onboarding when no families exist', async () => {
    mockedUseFamilies.mockReturnValue({
      data: [],
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useFamilies>);

    renderGuard();

    await waitFor(() => {
      expect(screen.getByText('Onboarding page')).toBeInTheDocument();
    });
  });

  it('redirects to onboarding when onboarding is incomplete', async () => {
    mockedUseOnboarding.mockReturnValue({
      data: {
        familyId: 'fam-1',
        currentStep: 'invite',
        completedSteps: ['family', 'child'],
        isComplete: false,
      },
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useOnboarding>);

    renderGuard();

    await waitFor(() => {
      expect(screen.getByText('Onboarding page')).toBeInTheDocument();
    });
  });

  it('shows loading state while onboarding checks are running', () => {
    mockedUseFamilies.mockReturnValue({
      data: [],
      isLoading: true,
    } as unknown as ReturnType<typeof apiHooks.useFamilies>);

    renderGuard();

    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });
});
