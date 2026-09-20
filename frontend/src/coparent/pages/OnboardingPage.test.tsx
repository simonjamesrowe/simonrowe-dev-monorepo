import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import * as apiHooks from '../hooks/api';

import OnboardingPage from './OnboardingPage';

const createFamilyMutate = vi.fn();
const createChildMutate = vi.fn();
const createInvitationMutate = vi.fn();
const resendInvitationMutate = vi.fn();
const updateOnboardingMutate = vi.fn();

vi.mock('../hooks/api', () => ({
  useFamilies: vi.fn(),
  useCreateFamily: vi.fn(),
  useChildren: vi.fn(),
  useCreateChild: vi.fn(),
  useInvitations: vi.fn(),
  useCreateInvitation: vi.fn(),
  useResendInvitation: vi.fn(),
  useOnboarding: vi.fn(),
  useUpdateOnboarding: vi.fn(),
}));

const mockedUseFamilies = vi.mocked(apiHooks.useFamilies);
const mockedUseCreateFamily = vi.mocked(apiHooks.useCreateFamily);
const mockedUseChildren = vi.mocked(apiHooks.useChildren);
const mockedUseCreateChild = vi.mocked(apiHooks.useCreateChild);
const mockedUseInvitations = vi.mocked(apiHooks.useInvitations);
const mockedUseCreateInvitation = vi.mocked(apiHooks.useCreateInvitation);
const mockedUseResendInvitation = vi.mocked(apiHooks.useResendInvitation);
const mockedUseOnboarding = vi.mocked(apiHooks.useOnboarding);
const mockedUseUpdateOnboarding = vi.mocked(apiHooks.useUpdateOnboarding);

describe('OnboardingPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();

    createFamilyMutate.mockResolvedValue({ id: 'fam-new' });
    createChildMutate.mockResolvedValue({ id: 'child-new' });
    createInvitationMutate.mockResolvedValue({ id: 'invite-new' });
    resendInvitationMutate.mockResolvedValue({});
    updateOnboardingMutate.mockResolvedValue({});

    mockedUseFamilies.mockReturnValue({
      data: [],
      isLoading: false,
    } as unknown as ReturnType<typeof apiHooks.useFamilies>);

    mockedUseChildren.mockReturnValue({
      data: [],
    } as unknown as ReturnType<typeof apiHooks.useChildren>);

    mockedUseInvitations.mockReturnValue({
      data: [],
    } as unknown as ReturnType<typeof apiHooks.useInvitations>);

    mockedUseOnboarding.mockReturnValue({
      data: undefined,
    } as unknown as ReturnType<typeof apiHooks.useOnboarding>);

    mockedUseCreateFamily.mockReturnValue({
      mutateAsync: createFamilyMutate,
    } as unknown as ReturnType<typeof apiHooks.useCreateFamily>);

    mockedUseCreateChild.mockReturnValue({
      mutateAsync: createChildMutate,
    } as unknown as ReturnType<typeof apiHooks.useCreateChild>);

    mockedUseCreateInvitation.mockReturnValue({
      mutateAsync: createInvitationMutate,
    } as unknown as ReturnType<typeof apiHooks.useCreateInvitation>);

    mockedUseResendInvitation.mockReturnValue({
      mutateAsync: resendInvitationMutate,
    } as unknown as ReturnType<typeof apiHooks.useResendInvitation>);

    mockedUseUpdateOnboarding.mockReturnValue({
      mutateAsync: updateOnboardingMutate,
    } as unknown as ReturnType<typeof apiHooks.useUpdateOnboarding>);
  });

  it('renders onboarding wizard family step', () => {
    render(
      <MemoryRouter>
        <OnboardingPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: 'Name Your Family' })).toBeInTheDocument();
    expect(screen.getByText('Family')).toBeInTheDocument();
    expect(screen.getByText('Children')).toBeInTheDocument();
  });

  it('submits family step and advances to child step', async () => {
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <OnboardingPage />
      </MemoryRouter>,
    );

    await user.type(screen.getByPlaceholderText('Enter your full name'), 'Alex Rowe');
    await user.type(screen.getByPlaceholderText('e.g., The Kingston Family'), 'Rowe Family');
    await user.type(
      screen.getByPlaceholderText('Start typing a city (e.g., London)'),
      'New York, USA',
    );

    await user.click(screen.getByRole('button', { name: 'Continue' }));

    await waitFor(() => {
      expect(createFamilyMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Rowe Family',
          timeZone: expect.any(String),
          fullName: 'Alex Rowe',
        }),
      );
    });

    expect(screen.getByRole('heading', { name: 'Add Your Children' })).toBeInTheDocument();
  });

  it('adds a child from the child step', async () => {
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <OnboardingPage />
      </MemoryRouter>,
    );

    await user.type(screen.getByPlaceholderText('Enter your full name'), 'Alex Rowe');
    await user.type(screen.getByPlaceholderText('e.g., The Kingston Family'), 'Rowe Family');
    await user.type(
      screen.getByPlaceholderText('Start typing a city (e.g., London)'),
      'New York, USA',
    );
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Add Your Children' })).toBeInTheDocument();
    });

    await user.type(screen.getByPlaceholderText('First and last name'), 'Theo Rowe');
    const dateInput = document.querySelector('input[type="date"]') as HTMLInputElement | null;
    expect(dateInput).not.toBeNull();
    await user.type(dateInput as HTMLInputElement, '2016-04-22');
    await user.click(screen.getByRole('button', { name: 'Add Child' }));

    await waitFor(() => {
      expect(createChildMutate).toHaveBeenCalledWith({
        familyId: 'fam-new',
        fullName: 'Theo Rowe',
        dateOfBirth: '2016-04-22',
        school: undefined,
        medicalNotes: undefined,
      });
    });
  });
});
