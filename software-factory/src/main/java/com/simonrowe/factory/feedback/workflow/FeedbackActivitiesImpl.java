package com.simonrowe.factory.feedback.workflow;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.feedback.agent.DistillEngine;
import com.simonrowe.factory.feedback.agent.DistillProposal;
import com.simonrowe.factory.feedback.agent.DistillTarget;
import com.simonrowe.factory.feedback.agent.GuidanceWorkspaceFactory;
import com.simonrowe.factory.feedback.agent.HarvestEngine;
import com.simonrowe.factory.feedback.config.FeedbackProperties;
import com.simonrowe.factory.feedback.config.FeedbackTaskQueues;
import com.simonrowe.factory.feedback.domain.DistillationOutcome;
import com.simonrowe.factory.feedback.domain.DistillationStatus;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.LessonScope;
import com.simonrowe.factory.feedback.domain.ReviewConversation;
import com.simonrowe.factory.feedback.github.ConversationGateway;
import com.simonrowe.factory.feedback.github.FeedbackPrGateway;
import com.simonrowe.factory.feedback.persistence.LearningRecord;
import com.simonrowe.factory.feedback.persistence.LearningRepository;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Spring-managed activity adapter wiring harvesting, Mongo persistence, and distillation. */
@Component
@ActivityImpl(taskQueues = FeedbackTaskQueues.REVIEW_FEEDBACK)
public class FeedbackActivitiesImpl implements FeedbackActivities {

  private static final List<String> AGENT_SETUP_ALLOWED =
      List.of(
          "components/instructions/global.md",
          "components/instructions/monorepo-additions.md",
          "components/skills/**");
  private static final List<String> SOURCE_REPO_ALLOWED = List.of("CLAUDE.md");

  private final ConversationGateway conversationGateway;
  private final HarvestEngine harvestEngine;
  private final DistillEngine distillEngine;
  private final GuidanceWorkspaceFactory workspaceFactory;
  private final FeedbackPrGateway prGateway;
  private final LearningRepository repository;
  private final FeedbackProperties properties;
  private final CodeReviewProperties codeReviewProperties;
  private final GitHubCredentials credentials;

  public FeedbackActivitiesImpl(
      final ConversationGateway conversationGateway,
      final HarvestEngine harvestEngine,
      final DistillEngine distillEngine,
      final GuidanceWorkspaceFactory workspaceFactory,
      final FeedbackPrGateway prGateway,
      final LearningRepository repository,
      final FeedbackProperties properties,
      final CodeReviewProperties codeReviewProperties,
      final GitHubCredentials credentials) {
    this.conversationGateway = conversationGateway;
    this.harvestEngine = harvestEngine;
    this.distillEngine = distillEngine;
    this.workspaceFactory = workspaceFactory;
    this.prGateway = prGateway;
    this.repository = repository;
    this.properties = properties;
    this.codeReviewProperties = codeReviewProperties;
    this.credentials = credentials;
  }

  @Override
  public ReviewConversation fetchConversation(final FeedbackRequest request) {
    return conversationGateway.fetchConversation(request);
  }

  @Override
  public List<Lesson> harvestLessons(
      final FeedbackRequest request, final ReviewConversation conversation) {
    return harvestEngine.harvest(
        request, conversation, detail -> Activity.getExecutionContext().heartbeat(detail));
  }

  @Override
  public void recordLearnings(
      final FeedbackRequest request, final ReviewConversation conversation,
      final List<Lesson> lessons, final String workflowId,
      final DistillationStatus initialStatus) {
    repository.save(
        new LearningRecord(
            LearningRecord.idFor(request.owner(), request.repository(), request.pullNumber()),
            request.owner(), request.repository(), request.pullNumber(),
            conversation.title(), conversation.url(), conversation.merged(),
            workflowId, Instant.now(), codeReviewProperties.agent().promptVersion(),
            lessons,
            new LearningRecord.Distillation(initialStatus, List.of(), null)));
  }

  @Override
  public DistillationOutcome distillAndPropose(
      final FeedbackRequest request, final List<Lesson> lessons) {
    Consumer<String> heartbeat = detail -> Activity.getExecutionContext().heartbeat(detail);
    List<String> prUrls = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    for (Target target : resolveTargets(request, lessons, properties.agentSetupRepo())) {
      Long installationId = credentials.installationId(target.owner(), target.repository());
      try (GuidanceWorkspaceFactory.GuidanceWorkspace workspace =
          workspaceFactory.create(target.owner(), target.repository(), installationId, heartbeat)) {
        DistillProposal proposal =
            distillEngine.distill(
                new DistillTarget(
                    target.owner(), target.repository(), workspace.repository(),
                    target.allowedPaths(), target.description()),
                target.lessons(), heartbeat);
        List<String> changed = workspaceFactory.changedPaths(workspace, heartbeat);
        if (!proposal.changed() || changed.isEmpty()) {
          notes.add(target.slug() + ": no change (" + proposal.reason() + ")");
          continue;
        }
        GuidanceWorkspaceFactory.validateAllowedPaths(changed, target.allowedPaths());
        String branch =
            "feedback/" + request.repository() + "-pr-" + request.pullNumber();
        workspaceFactory.commitAndPush(
            workspace, branch, proposal.prTitle(), installationId, heartbeat);
        prUrls.add(
            prGateway.openProposal(
                target.owner(), target.repository(), branch, workspace.defaultBranch(),
                proposal.prTitle(), proposal.prBody(), properties.skipLabel(), installationId));
      }
    }
    if (prUrls.isEmpty()) {
      return new DistillationOutcome(
          DistillationStatus.NO_CHANGE, List.of(), String.join("; ", notes));
    }
    return new DistillationOutcome(
        DistillationStatus.PROPOSED, prUrls, notes.isEmpty() ? null : String.join("; ", notes));
  }

  @Override
  public void recordDistillation(final FeedbackRequest request, final DistillationOutcome outcome) {
    LearningRecord existing =
        repository
            .findById(
                LearningRecord.idFor(request.owner(), request.repository(), request.pullNumber()))
            .orElseThrow(() -> new IllegalStateException("Learning record missing"));
    repository.save(
        new LearningRecord(
            existing.id(), existing.owner(), existing.repository(), existing.pullNumber(),
            existing.prTitle(), existing.prUrl(), existing.merged(), existing.workflowId(),
            existing.harvestedAt(), existing.promptVersion(), existing.lessons(),
            new LearningRecord.Distillation(
                outcome.status(), outcome.prUrls(), outcome.detail())));
  }

  /** Package-private for testing: repos/paths to distill guidance into for this PR's lessons. */
  static List<Target> resolveTargets(
      final FeedbackRequest request, final List<Lesson> lessons, final String agentSetupRepo) {
    String[] agentSetup = agentSetupRepo.split("/", 2);
    List<Target> targets = new ArrayList<>();
    // agent-setup always: org-wide lessons go to global.md/skills; repo-specific lessons for
    // the monorepo also land in monorepo-additions.md (canonical text lives in agent-setup).
    targets.add(
        new Target(
            agentSetup[0], agentSetup[1], AGENT_SETUP_ALLOWED,
            "the org-wide agent guidance package", lessons));
    boolean repoSpecific =
        lessons.stream().anyMatch(lesson -> lesson.scope() == LessonScope.REPO_SPECIFIC);
    boolean sourceIsAgentSetup =
        request.owner().equals(agentSetup[0]) && request.repository().equals(agentSetup[1]);
    if (repoSpecific && !sourceIsAgentSetup) {
      targets.add(
          new Target(
              request.owner(), request.repository(), SOURCE_REPO_ALLOWED,
              "the source repository's CLAUDE.md agent instructions",
              lessons.stream()
                  .filter(lesson -> lesson.scope() == LessonScope.REPO_SPECIFIC)
                  .toList()));
    }
    return targets;
  }

  /** One repo the distiller may edit, with its allowlist and the lessons destined for it. */
  record Target(
      String owner, String repository, List<String> allowedPaths, String description,
      List<Lesson> lessons) {

    String slug() {
      return owner + "/" + repository;
    }
  }
}
