import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import type {
  OnboardingState,
  UpdateOnboardingRequest,
  CompleteStepRequest,
} from '../../lib/api/client';
import { apiClient } from '../../lib/api/client';

export const onboardingKeys = {
  all: ['onboarding'] as const,
  details: () => [...onboardingKeys.all, 'detail'] as const,
  detail: (familyId: string) => [...onboardingKeys.details(), familyId] as const,
};

export function useOnboarding(familyId: string | undefined) {
  return useQuery({
    queryKey: onboardingKeys.detail(familyId!),
    queryFn: async () => {
      const { data } = await apiClient.get<OnboardingState>(`/onboarding/${familyId}`);
      return data;
    },
    enabled: !!familyId,
  });
}

export function useUpdateOnboarding() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      familyId,
      ...request
    }: UpdateOnboardingRequest & { familyId: string }) => {
      const { data } = await apiClient.patch<OnboardingState>(`/onboarding/${familyId}`, request);
      return data;
    },
    onSuccess: (data) => {
      queryClient.setQueryData(onboardingKeys.detail(data.familyId), data);
    },
  });
}

export function useCompleteOnboardingStep() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ familyId, ...request }: CompleteStepRequest & { familyId: string }) => {
      const { data } = await apiClient.post<OnboardingState>(
        `/onboarding/${familyId}/complete-step`,
        request,
      );
      return data;
    },
    onSuccess: (data) => {
      queryClient.setQueryData(onboardingKeys.detail(data.familyId), data);
    },
  });
}
