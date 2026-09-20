package com.simonrowe.coparent.family;

import com.simonrowe.coparent.model.OnboardingState;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.OnboardingStateRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Consistent, retryable progression through family onboarding. */
@Service
public class OnboardingService {

  private static final List<String> STEPS =
      List.of("account", "family", "child", "invite", "review", "complete");

  private final OnboardingStateRepository states;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;

  public OnboardingService(
      final OnboardingStateRepository states,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits) {
    this.states = states;
    this.access = access;
    this.audits = audits;
  }

  /** Gets or initialises one family's onboarding state. */
  public OnboardingState get(final ObjectId familyId) {
    access.requireMember(familyId);
    return states.findByFamilyId(familyId).orElseGet(() -> {
      final Instant now = Instant.now();
      return states.save(new OnboardingState(null, familyId, "account", List.of(), false,
          now, now, now));
    });
  }

  /** Replaces supplied onboarding values after validating the finite state vocabulary. */
  public OnboardingState update(
      final ObjectId familyId,
      final String currentStep,
      final List<String> completedSteps,
      final Boolean complete) {
    final OnboardingState current = get(familyId);
    final String step = currentStep == null ? current.currentStep() : requireStep(currentStep);
    final List<String> completed = completedSteps == null
        ? current.completedSteps()
        : normaliseSteps(completedSteps);
    final boolean isComplete = complete == null ? current.complete() : complete;
    final Instant now = Instant.now();
    final OnboardingState saved = states.save(new OnboardingState(current.id(), familyId,
        isComplete ? "complete" : step, completed, isComplete, now, current.createdAt(), now));
    audit(saved, "update");
    return saved;
  }

  /** Marks a step once and advances deterministically. */
  public OnboardingState completeStep(final ObjectId familyId, final String rawStep) {
    final String step = requireStep(rawStep);
    final OnboardingState current = get(familyId);
    final LinkedHashSet<String> completed = new LinkedHashSet<>(current.completedSteps());
    completed.add(step);
    final int index = STEPS.indexOf(step);
    final boolean complete = "review".equals(step) || "complete".equals(step);
    final String next = complete ? "complete" : STEPS.get(Math.min(index + 1, STEPS.size() - 1));
    final Instant now = Instant.now();
    final OnboardingState saved = states.save(new OnboardingState(current.id(), familyId, next,
        new ArrayList<>(completed), complete, now, current.createdAt(), now));
    audit(saved, "complete-step");
    return saved;
  }

  private void audit(final OnboardingState state, final String action) {
    audits.record(state.familyId(), "onboarding", state.id(), action, Map.of(
        "currentStep", state.currentStep(),
        "completedSteps", state.completedSteps(),
        "isComplete", state.complete()));
  }

  private static String requireStep(final String step) {
    if (!STEPS.contains(step)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid onboarding step");
    }
    return step;
  }

  private static List<String> normaliseSteps(final List<String> steps) {
    steps.forEach(OnboardingService::requireStep);
    return List.copyOf(new LinkedHashSet<>(steps));
  }
}
