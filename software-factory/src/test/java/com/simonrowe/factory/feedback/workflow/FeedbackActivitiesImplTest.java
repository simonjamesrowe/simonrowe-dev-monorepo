package com.simonrowe.factory.feedback.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.feedback.agent.DistillEngine;
import com.simonrowe.factory.feedback.agent.DistillProposal;
import com.simonrowe.factory.feedback.agent.DistillTarget;
import com.simonrowe.factory.feedback.agent.GuidanceWorkspaceFactory;
import com.simonrowe.factory.feedback.agent.HarvestEngine;
import com.simonrowe.factory.feedback.config.FeedbackProperties;
import com.simonrowe.factory.feedback.domain.DistillationOutcome;
import com.simonrowe.factory.feedback.domain.DistillationStatus;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.LessonConfidence;
import com.simonrowe.factory.feedback.domain.LessonScope;
import com.simonrowe.factory.feedback.domain.LessonSource;
import com.simonrowe.factory.feedback.github.ConversationGateway;
import com.simonrowe.factory.feedback.github.FeedbackPrGateway;
import com.simonrowe.factory.feedback.persistence.LearningRecord;
import com.simonrowe.factory.feedback.persistence.LearningRepository;
import com.simonrowe.factory.git.RepositoryWorkspace;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeedbackActivitiesImplTest {

  private static Lesson lesson(final LessonScope scope) {
    return new Lesson(
        "t", "g", scope, List.of("https://c/1"), LessonSource.HUMAN, LessonConfidence.HIGH);
  }

  private static final FeedbackRequest REQUEST =
      new FeedbackRequest("simonjamesrowe", "simonrowe-dev-monorepo", 42, 999L, false);

  @Test
  void orgWideLessonsTargetOnlyAgentSetup() {
    var targets =
        FeedbackActivitiesImpl.resolveTargets(
            REQUEST, List.of(lesson(LessonScope.ORG_WIDE)), "simonjamesrowe/agent-setup");

    assertThat(targets).hasSize(1);
    assertThat(targets.getFirst().repository()).isEqualTo("agent-setup");
  }

  @Test
  void repoSpecificLessonsAddTheSourceRepoWithClaudeMdOnly() {
    var targets =
        FeedbackActivitiesImpl.resolveTargets(
            REQUEST,
            List.of(lesson(LessonScope.ORG_WIDE), lesson(LessonScope.REPO_SPECIFIC)),
            "simonjamesrowe/agent-setup");

    assertThat(targets).hasSize(2);
    assertThat(targets.get(1).repository()).isEqualTo("simonrowe-dev-monorepo");
    assertThat(targets.get(1).allowedPaths()).containsExactly("CLAUDE.md");
    assertThat(targets.get(1).lessons()).hasSize(1);
  }

  @Test
  void agentSetupAsTheSourceRepoIsNotTargetedTwice() {
    FeedbackRequest request =
        new FeedbackRequest("simonjamesrowe", "agent-setup", 7, 999L, false);

    var targets =
        FeedbackActivitiesImpl.resolveTargets(
            request, List.of(lesson(LessonScope.REPO_SPECIFIC)), "simonjamesrowe/agent-setup");

    assertThat(targets).hasSize(1);
  }

  @Test
  void distillAndProposeOpensPullRequestWhenTheDistillerChangedAllowedFiles() {
    ConversationGateway conversationGateway = mock(ConversationGateway.class);
    HarvestEngine harvestEngine = mock(HarvestEngine.class);
    DistillEngine distillEngine = mock(DistillEngine.class);
    GuidanceWorkspaceFactory workspaceFactory = mock(GuidanceWorkspaceFactory.class);
    FeedbackPrGateway prGateway = mock(FeedbackPrGateway.class);
    LearningRepository repository = mock(LearningRepository.class);
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    RepositoryWorkspace workspace = mock(RepositoryWorkspace.class);

    when(workspace.repository()).thenReturn(Path.of("/tmp/agent-setup"));
    when(workspace.defaultBranch()).thenReturn("main");
    when(credentials.installationId(anyString(), anyString())).thenReturn(555L);
    when(workspaceFactory.create(eq("simonjamesrowe"), eq("agent-setup"), eq(555L), any()))
        .thenReturn(workspace);
    when(distillEngine.distill(any(DistillTarget.class), anyList(), any()))
        .thenReturn(new DistillProposal(true, "added a lesson", "Propose guidance", "Body"));
    when(workspaceFactory.changedPaths(eq(workspace), any()))
        .thenReturn(List.of("components/instructions/global.md"));
    when(prGateway.openProposal(
            eq("simonjamesrowe"), eq("agent-setup"), anyString(), eq("main"),
            eq("Propose guidance"), eq("Body"), eq("agent-feedback"), eq(555L)))
        .thenReturn("https://github.com/simonjamesrowe/agent-setup/pull/9");

    FeedbackActivitiesImpl activities =
        new FeedbackActivitiesImpl(
            conversationGateway, harvestEngine, distillEngine, workspaceFactory, prGateway,
            repository, feedbackProperties("simonjamesrowe/agent-setup"), codeReviewProperties(),
            credentials);

    DistillationOutcome outcome =
        activities.distillAndPropose(REQUEST, List.of(lesson(LessonScope.ORG_WIDE)));

    assertThat(outcome.status()).isEqualTo(DistillationStatus.PROPOSED);
    assertThat(outcome.prUrls())
        .containsExactly("https://github.com/simonjamesrowe/agent-setup/pull/9");
    verify(workspaceFactory)
        .commitAndPush(
            eq(workspace), eq("feedback/simonrowe-dev-monorepo-pr-42"), eq("Propose guidance"),
            eq(555L), any());
    verify(prGateway)
        .openProposal(
            eq("simonjamesrowe"), eq("agent-setup"), eq("feedback/simonrowe-dev-monorepo-pr-42"),
            eq("main"), eq("Propose guidance"), eq("Body"), eq("agent-feedback"), eq(555L));
  }

  @Test
  void distillAndProposeSkipsAndReportsNoChangeWhenTheDistillerMadeNoChange() {
    ConversationGateway conversationGateway = mock(ConversationGateway.class);
    HarvestEngine harvestEngine = mock(HarvestEngine.class);
    DistillEngine distillEngine = mock(DistillEngine.class);
    GuidanceWorkspaceFactory workspaceFactory = mock(GuidanceWorkspaceFactory.class);
    FeedbackPrGateway prGateway = mock(FeedbackPrGateway.class);
    LearningRepository repository = mock(LearningRepository.class);
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    RepositoryWorkspace workspace = mock(RepositoryWorkspace.class);

    when(credentials.installationId(anyString(), anyString())).thenReturn(555L);
    when(workspaceFactory.create(eq("simonjamesrowe"), eq("agent-setup"), anyLong(), any()))
        .thenReturn(workspace);
    when(distillEngine.distill(any(DistillTarget.class), anyList(), any()))
        .thenReturn(new DistillProposal(false, "nothing to add", null, null));

    FeedbackActivitiesImpl activities =
        new FeedbackActivitiesImpl(
            conversationGateway, harvestEngine, distillEngine, workspaceFactory, prGateway,
            repository, feedbackProperties("simonjamesrowe/agent-setup"), codeReviewProperties(),
            credentials);

    DistillationOutcome outcome =
        activities.distillAndPropose(REQUEST, List.of(lesson(LessonScope.ORG_WIDE)));

    assertThat(outcome.status()).isEqualTo(DistillationStatus.NO_CHANGE);
    assertThat(outcome.prUrls()).isEmpty();
    assertThat(outcome.detail())
        .isEqualTo("simonjamesrowe/agent-setup: no change (nothing to add)");
    verify(workspaceFactory, never()).commitAndPush(any(), any(), any(), any(), any());
    verify(prGateway, never())
        .openProposal(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void distillAndProposeAggregatesOneProposalAndOneSkipAcrossTargets() {
    ConversationGateway conversationGateway = mock(ConversationGateway.class);
    HarvestEngine harvestEngine = mock(HarvestEngine.class);
    DistillEngine distillEngine = mock(DistillEngine.class);
    GuidanceWorkspaceFactory workspaceFactory = mock(GuidanceWorkspaceFactory.class);
    FeedbackPrGateway prGateway = mock(FeedbackPrGateway.class);
    LearningRepository repository = mock(LearningRepository.class);
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    RepositoryWorkspace agentSetupWorkspace = mock(RepositoryWorkspace.class);
    RepositoryWorkspace sourceRepoWorkspace = mock(RepositoryWorkspace.class);

    when(agentSetupWorkspace.repository()).thenReturn(Path.of("/tmp/agent-setup"));
    when(agentSetupWorkspace.defaultBranch()).thenReturn("main");
    when(sourceRepoWorkspace.repository()).thenReturn(Path.of("/tmp/monorepo"));
    when(sourceRepoWorkspace.defaultBranch()).thenReturn("main");
    when(credentials.installationId(anyString(), anyString())).thenReturn(555L);
    when(workspaceFactory.create(eq("simonjamesrowe"), eq("agent-setup"), anyLong(), any()))
        .thenReturn(agentSetupWorkspace);
    when(workspaceFactory.create(
            eq("simonjamesrowe"), eq("simonrowe-dev-monorepo"), anyLong(), any()))
        .thenReturn(sourceRepoWorkspace);
    when(distillEngine.distill(
            argThat(target -> target != null && "agent-setup".equals(target.repository())),
            anyList(), any()))
        .thenReturn(new DistillProposal(true, "added a lesson", "Propose guidance", "Body"));
    when(distillEngine.distill(
            argThat(
                target -> target != null && "simonrowe-dev-monorepo".equals(target.repository())),
            anyList(), any()))
        .thenReturn(new DistillProposal(false, "nothing repo-specific to add", null, null));
    when(workspaceFactory.changedPaths(eq(agentSetupWorkspace), any()))
        .thenReturn(List.of("components/instructions/global.md"));
    when(prGateway.openProposal(
            eq("simonjamesrowe"), eq("agent-setup"), anyString(), eq("main"),
            eq("Propose guidance"), eq("Body"), eq("agent-feedback"), anyLong()))
        .thenReturn("https://github.com/simonjamesrowe/agent-setup/pull/10");

    FeedbackActivitiesImpl activities =
        new FeedbackActivitiesImpl(
            conversationGateway, harvestEngine, distillEngine, workspaceFactory, prGateway,
            repository, feedbackProperties("simonjamesrowe/agent-setup"), codeReviewProperties(),
            credentials);

    DistillationOutcome outcome =
        activities.distillAndPropose(
            REQUEST, List.of(lesson(LessonScope.ORG_WIDE), lesson(LessonScope.REPO_SPECIFIC)));

    assertThat(outcome.status()).isEqualTo(DistillationStatus.PROPOSED);
    assertThat(outcome.prUrls())
        .containsExactly("https://github.com/simonjamesrowe/agent-setup/pull/10");
    assertThat(outcome.detail())
        .isEqualTo(
            "simonjamesrowe/simonrowe-dev-monorepo: no change (nothing repo-specific to add)");
    verify(workspaceFactory, never())
        .commitAndPush(eq(sourceRepoWorkspace), any(), any(), any(), any());
    verify(prGateway, never())
        .openProposal(
            eq("simonjamesrowe"), eq("simonrowe-dev-monorepo"), any(), any(), any(), any(), any(),
            any());
  }

  @Test
  void distillAndProposeContinuesToOtherTargetsWhenOneTargetFails() {
    ConversationGateway conversationGateway = mock(ConversationGateway.class);
    HarvestEngine harvestEngine = mock(HarvestEngine.class);
    DistillEngine distillEngine = mock(DistillEngine.class);
    GuidanceWorkspaceFactory workspaceFactory = mock(GuidanceWorkspaceFactory.class);
    FeedbackPrGateway prGateway = mock(FeedbackPrGateway.class);
    LearningRepository repository = mock(LearningRepository.class);
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    RepositoryWorkspace agentSetupWorkspace = mock(RepositoryWorkspace.class);

    when(agentSetupWorkspace.repository()).thenReturn(Path.of("/tmp/agent-setup"));
    when(agentSetupWorkspace.defaultBranch()).thenReturn("main");
    when(credentials.installationId(anyString(), anyString())).thenReturn(555L);
    when(workspaceFactory.create(eq("simonjamesrowe"), eq("agent-setup"), anyLong(), any()))
        .thenReturn(agentSetupWorkspace);
    when(workspaceFactory.create(
            eq("simonjamesrowe"), eq("simonrowe-dev-monorepo"), anyLong(), any()))
        .thenThrow(new IllegalStateException("agent crashed"));
    when(distillEngine.distill(
            argThat(target -> target != null && "agent-setup".equals(target.repository())),
            anyList(), any()))
        .thenReturn(new DistillProposal(true, "added a lesson", "Propose guidance", "Body"));
    when(workspaceFactory.changedPaths(eq(agentSetupWorkspace), any()))
        .thenReturn(List.of("components/instructions/global.md"));
    when(prGateway.openProposal(
            eq("simonjamesrowe"), eq("agent-setup"), anyString(), eq("main"),
            eq("Propose guidance"), eq("Body"), eq("agent-feedback"), anyLong()))
        .thenReturn("https://github.com/simonjamesrowe/agent-setup/pull/11");

    FeedbackActivitiesImpl activities =
        new FeedbackActivitiesImpl(
            conversationGateway, harvestEngine, distillEngine, workspaceFactory, prGateway,
            repository, feedbackProperties("simonjamesrowe/agent-setup"), codeReviewProperties(),
            credentials);

    DistillationOutcome outcome =
        activities.distillAndPropose(
            REQUEST, List.of(lesson(LessonScope.ORG_WIDE), lesson(LessonScope.REPO_SPECIFIC)));

    assertThat(outcome.status()).isEqualTo(DistillationStatus.PROPOSED);
    assertThat(outcome.prUrls())
        .containsExactly("https://github.com/simonjamesrowe/agent-setup/pull/11");
    assertThat(outcome.detail()).contains("simonjamesrowe/simonrowe-dev-monorepo: failed");
    assertThat(outcome.detail()).contains("agent crashed");
  }

  @Test
  void recordDistillationUpdatesTheExistingLearningRecordsDistillation() {
    ConversationGateway conversationGateway = mock(ConversationGateway.class);
    HarvestEngine harvestEngine = mock(HarvestEngine.class);
    DistillEngine distillEngine = mock(DistillEngine.class);
    GuidanceWorkspaceFactory workspaceFactory = mock(GuidanceWorkspaceFactory.class);
    FeedbackPrGateway prGateway = mock(FeedbackPrGateway.class);
    LearningRepository repository = mock(LearningRepository.class);
    GitHubCredentials credentials = mock(GitHubCredentials.class);

    Instant harvestedAt = Instant.parse("2026-08-01T00:00:00Z");
    LearningRecord existing =
        new LearningRecord(
            LearningRecord.idFor("simonjamesrowe", "simonrowe-dev-monorepo", 42),
            "simonjamesrowe", "simonrowe-dev-monorepo", 42, "Some PR title",
            "https://github.com/simonjamesrowe/simonrowe-dev-monorepo/pull/42", true,
            "workflow-1", harvestedAt, "v1", List.of(lesson(LessonScope.ORG_WIDE)),
            new LearningRecord.Distillation(
                DistillationStatus.SKIPPED_NO_LESSONS, List.of(), null));
    when(repository.findById(existing.id())).thenReturn(Optional.of(existing));

    FeedbackActivitiesImpl activities =
        new FeedbackActivitiesImpl(
            conversationGateway, harvestEngine, distillEngine, workspaceFactory, prGateway,
            repository, feedbackProperties("simonjamesrowe/agent-setup"), codeReviewProperties(),
            credentials);
    DistillationOutcome outcome =
        new DistillationOutcome(
            DistillationStatus.PROPOSED,
            List.of("https://github.com/simonjamesrowe/agent-setup/pull/9"), null);

    activities.recordDistillation(REQUEST, outcome);

    ArgumentCaptor<LearningRecord> captor = ArgumentCaptor.forClass(LearningRecord.class);
    verify(repository).save(captor.capture());
    LearningRecord saved = captor.getValue();
    assertThat(saved.id()).isEqualTo(existing.id());
    assertThat(saved.owner()).isEqualTo(existing.owner());
    assertThat(saved.repository()).isEqualTo(existing.repository());
    assertThat(saved.pullNumber()).isEqualTo(existing.pullNumber());
    assertThat(saved.prTitle()).isEqualTo(existing.prTitle());
    assertThat(saved.prUrl()).isEqualTo(existing.prUrl());
    assertThat(saved.merged()).isEqualTo(existing.merged());
    assertThat(saved.workflowId()).isEqualTo(existing.workflowId());
    assertThat(saved.harvestedAt()).isEqualTo(existing.harvestedAt());
    assertThat(saved.promptVersion()).isEqualTo(existing.promptVersion());
    assertThat(saved.lessons()).isEqualTo(existing.lessons());
    assertThat(saved.distillation())
        .isEqualTo(
            new LearningRecord.Distillation(outcome.status(), outcome.prUrls(), outcome.detail()));
  }

  @Test
  void recordDistillationThrowsWhenNoLearningRecordExists() {
    ConversationGateway conversationGateway = mock(ConversationGateway.class);
    HarvestEngine harvestEngine = mock(HarvestEngine.class);
    DistillEngine distillEngine = mock(DistillEngine.class);
    GuidanceWorkspaceFactory workspaceFactory = mock(GuidanceWorkspaceFactory.class);
    FeedbackPrGateway prGateway = mock(FeedbackPrGateway.class);
    LearningRepository repository = mock(LearningRepository.class);
    GitHubCredentials credentials = mock(GitHubCredentials.class);

    when(repository.findById(
            LearningRecord.idFor("simonjamesrowe", "simonrowe-dev-monorepo", 42)))
        .thenReturn(Optional.empty());

    FeedbackActivitiesImpl activities =
        new FeedbackActivitiesImpl(
            conversationGateway, harvestEngine, distillEngine, workspaceFactory, prGateway,
            repository, feedbackProperties("simonjamesrowe/agent-setup"), codeReviewProperties(),
            credentials);
    DistillationOutcome outcome =
        new DistillationOutcome(DistillationStatus.NO_CHANGE, List.of(), null);

    assertThatThrownBy(() -> activities.recordDistillation(REQUEST, outcome))
        .isInstanceOf(IllegalStateException.class);
  }

  private static FeedbackProperties feedbackProperties(final String agentSetupRepo) {
    return new FeedbackProperties(
        true, List.of(), "agent-feedback", agentSetupRepo, "factory-bot",
        "factory-bot@example.com", Path.of("/tmp/feedback-workspaces"), null, null);
  }

  private static CodeReviewProperties codeReviewProperties() {
    return new CodeReviewProperties(
        new CodeReviewProperties.Github(
            "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
        new CodeReviewProperties.Agent(
            "claude", "sonnet", "medium", 12, Duration.ofMinutes(15),
            Path.of("/tmp/reviewer-test"), 2_097_152, 80, "v1"),
        new CodeReviewProperties.Api(""));
  }
}
