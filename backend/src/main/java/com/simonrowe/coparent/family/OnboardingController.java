package com.simonrowe.coparent.family;

import com.simonrowe.coparent.model.OnboardingState;
import com.simonrowe.coparent.shared.CoparentIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP contract for resumable family onboarding. */
@RestController
@RequestMapping("/api/coparent/onboarding")
public class OnboardingController {

  private final OnboardingService service;

  public OnboardingController(final OnboardingService service) {
    this.service = service;
  }

  @GetMapping("/{familyId}")
  OnboardingResponse get(@PathVariable final String familyId) {
    return OnboardingResponse.from(service.get(CoparentIds.parse(familyId)));
  }

  @PatchMapping("/{familyId}")
  OnboardingResponse update(
      @PathVariable final String familyId,
      @RequestBody final UpdateOnboardingRequest request) {
    return OnboardingResponse.from(service.update(CoparentIds.parse(familyId),
        request.currentStep(), request.completedSteps(), request.isComplete()));
  }

  @PostMapping("/{familyId}/complete-step")
  OnboardingResponse completeStep(
      @PathVariable final String familyId,
      @Valid @RequestBody final CompleteStepRequest request) {
    return OnboardingResponse.from(
        service.completeStep(CoparentIds.parse(familyId), request.step()));
  }

  record UpdateOnboardingRequest(
      String currentStep,
      List<String> completedSteps,
      Boolean isComplete
  ) {
  }

  record CompleteStepRequest(@NotBlank String step) {
  }

  record OnboardingResponse(
      String id,
      String familyId,
      String currentStep,
      List<String> completedSteps,
      boolean isComplete,
      Instant lastUpdated
  ) {
    static OnboardingResponse from(final OnboardingState state) {
      return new OnboardingResponse(state.id().toHexString(), state.familyId().toHexString(),
          state.currentStep(), state.completedSteps(), state.complete(), state.lastUpdated());
    }
  }
}
