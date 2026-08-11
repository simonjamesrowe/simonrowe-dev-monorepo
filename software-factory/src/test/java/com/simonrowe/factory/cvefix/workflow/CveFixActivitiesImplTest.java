package com.simonrowe.factory.cvefix.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.cvefix.agent.FixEngine;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.dependencytrack.DependencyTrackClient;
import com.simonrowe.factory.cvefix.domain.Bump;
import com.simonrowe.factory.cvefix.domain.CiOutcome;
import com.simonrowe.factory.cvefix.domain.CiOutcome.CiState;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.Finding;
import com.simonrowe.factory.cvefix.domain.FixProposal;
import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import com.simonrowe.factory.cvefix.github.CiStatusGateway;
import com.simonrowe.factory.cvefix.github.CveFixPrBodyRenderer;
import com.simonrowe.factory.cvefix.github.CveFixPrGateway;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRepository;
import com.simonrowe.factory.cvefix.persistence.FindingSuppressor;
import com.simonrowe.factory.git.RepositoryWorkspace;
import com.simonrowe.factory.git.RepositoryWorkspaceFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CveFixActivitiesImplTest {

  private final DependencyTrackClient dependencyTrackClient = mock(DependencyTrackClient.class);
  private final FindingSuppressor suppressor = mock(FindingSuppressor.class);
  private final RepositoryWorkspaceFactory workspaceFactory =
      mock(RepositoryWorkspaceFactory.class);
  private final GitHubCredentials credentials = mock(GitHubCredentials.class);
  private final FixEngine fixEngine = mock(FixEngine.class);
  private final CveFixPrGateway prGateway = mock(CveFixPrGateway.class);
  private final CiStatusGateway ciStatusGateway = mock(CiStatusGateway.class);
  private final CveFixRunRepository runRepository = mock(CveFixRunRepository.class);

  private final CveFixActivitiesImpl activities =
      new CveFixActivitiesImpl(
          dependencyTrackClient, suppressor, workspaceFactory, credentials, fixEngine, prGateway,
          ciStatusGateway, runRepository, properties());

  @Test
  void fetchActionableFindingsGroupsThenSuppresses() {
    Finding first = new Finding("pkg:maven/a/a@1.0", "a", "1.0", "CVE-2024-0001", "HIGH", "");
    Finding second = new Finding("pkg:maven/a/a@1.0", "a", "1.0", "CVE-2024-0002", "HIGH", "");
    when(dependencyTrackClient.findings()).thenReturn(List.of(first, second));
    List<ComponentFindings> grouped = ComponentFindings.group(List.of(first, second));
    when(suppressor.retainActionable(grouped)).thenReturn(grouped);

    List<ComponentFindings> result = activities.fetchActionableFindings();

    assertThat(result).isEqualTo(grouped);
    verify(suppressor).retainActionable(grouped);
  }

  @Test
  void proposeAndPushValidatesChangedPathsBeforeCommitting() {
    RepositoryWorkspace workspace = mock(RepositoryWorkspace.class);
    when(credentials.installationId(anyString(), anyString())).thenReturn(42L);
    when(workspaceFactory.create(anyString(), anyString(), anyLong(), any(), anyString(), any()))
        .thenReturn(workspace);
    when(fixEngine.propose(eq(workspace), anyList(), isNull(), any()))
        .thenReturn(oneBumpProposal());
    when(workspaceFactory.changedPaths(eq(workspace), any()))
        .thenReturn(List.of("backend/src/main/java/Evil.java"));

    assertThatThrownBy(() -> activities.proposeAndPush(List.of(), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Evil.java");

    verify(workspaceFactory, never())
        .commitAndPush(any(), any(), any(), any(), any(), any(), any());
    verify(prGateway, never()).open(any(), any());
    verify(workspace).close();
  }

  @Test
  void proposeAndPushReturnsNullHeadShaWhenTheAgentChangedNothing() {
    RepositoryWorkspace workspace = mock(RepositoryWorkspace.class);
    when(credentials.installationId(anyString(), anyString())).thenReturn(42L);
    when(workspaceFactory.create(anyString(), anyString(), anyLong(), any(), anyString(), any()))
        .thenReturn(workspace);
    FixProposal emptyProposal = new FixProposal(List.of(), List.of(), "Nothing needed changing.");
    when(fixEngine.propose(eq(workspace), anyList(), isNull(), any())).thenReturn(emptyProposal);
    when(workspaceFactory.changedPaths(eq(workspace), any())).thenReturn(List.of());

    CveFixActivities.PushResult result = activities.proposeAndPush(List.of(), null);

    assertThat(result.headSha()).isNull();
    assertThat(result.summary().agentSummary()).isEqualTo("Nothing needed changing.");
    verify(workspaceFactory, never())
        .commitAndPush(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void proposeAndPushClosesTheWorkspaceEvenWhenTheAgentThrows() {
    RepositoryWorkspace workspace = mock(RepositoryWorkspace.class);
    when(credentials.installationId(anyString(), anyString())).thenReturn(42L);
    when(workspaceFactory.create(anyString(), anyString(), anyLong(), any(), anyString(), any()))
        .thenReturn(workspace);
    when(fixEngine.propose(eq(workspace), anyList(), isNull(), any()))
        .thenThrow(new IllegalStateException("agent crashed"));

    assertThatThrownBy(() -> activities.proposeAndPush(List.of(), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("agent crashed");

    verify(workspace).close();
  }

  @Test
  void proposeAndPushResolvesTheInstallationIdAndPassesItToCloneAndPush() {
    RepositoryWorkspace workspace = mock(RepositoryWorkspace.class);
    when(credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo")).thenReturn(42L);
    when(workspaceFactory.create(
            eq("simonjamesrowe"), eq("simonrowe-dev-monorepo"), eq(42L), any(), anyString(),
            any()))
        .thenReturn(workspace);
    when(fixEngine.propose(eq(workspace), anyList(), isNull(), any()))
        .thenReturn(oneBumpProposal());
    when(workspaceFactory.changedPaths(eq(workspace), any()))
        .thenReturn(List.of("gradle/libs.versions.toml"));
    when(workspaceFactory.commitAndPush(
            eq(workspace), anyString(), anyString(), anyString(), anyString(), eq(42L), any()))
        .thenReturn("abc123");

    CveFixActivities.PushResult result = activities.proposeAndPush(List.of(), null);

    assertThat(result.headSha()).isEqualTo("abc123");
    verify(workspaceFactory)
        .create(
            eq("simonjamesrowe"), eq("simonrowe-dev-monorepo"), eq(42L), any(), anyString(),
            any());
    verify(workspaceFactory)
        .commitAndPush(
            eq(workspace), anyString(), anyString(), anyString(), anyString(), eq(42L), any());
  }

  @Test
  void openPullRequestRendersTheTitleAndBodyFromTheSummary() {
    CveFixActivities.FixSummary summary =
        new CveFixActivities.FixSummary(
            List.of("a 1.0 -> 2.0 (CVE-2024-0001)"), List.of(), "Bumped one component.");
    CveFixPrGateway.OpenPullRequest expected =
        new CveFixPrGateway.OpenPullRequest(9, "https://github.com/example/pull/9", "abc123");
    when(prGateway.open(anyString(), anyString())).thenReturn(expected);

    CveFixPrGateway.OpenPullRequest result = activities.openPullRequest(summary);

    assertThat(result).isEqualTo(expected);
    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(prGateway).open(eq(CveFixPrBodyRenderer.title(summary)), bodyCaptor.capture());
    assertThat(bodyCaptor.getValue()).isEqualTo(CveFixPrBodyRenderer.body(summary));
    assertThat(bodyCaptor.getValue()).isNotBlank();
  }

  @Test
  void recordUnfixableDelegatesToTheSuppressorWithThisRunsFindings() {
    List<UnfixableComponent> unfixable =
        List.of(
            new UnfixableComponent(
                "pkg:maven/a/a@1.0", List.of("CVE-2024-0001"), "no released fix"));
    List<ComponentFindings> components =
        ComponentFindings.group(
            List.of(new Finding("pkg:maven/a/a@1.0", "a", "1.0", "CVE-2024-0001", "HIGH", "")));

    activities.recordUnfixable(unfixable, components);

    verify(suppressor).record(unfixable, components);
  }

  @Test
  void findOpenPrUrlPropagatesGatewayFailures() {
    when(prGateway.findOpen()).thenThrow(new IllegalStateException("GitHub is unreachable"));

    assertThatThrownBy(activities::findOpenPrUrl)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GitHub is unreachable");
  }

  @Test
  void findOpenPrUrlReturnsTheUrlOfAnAlreadyOpenPullRequest() {
    when(prGateway.findOpen())
        .thenReturn(
            Optional.of(
                new CveFixPrGateway.OpenPullRequest(
                    3, "https://github.com/example/pull/3", "sha")));

    assertThat(activities.findOpenPrUrl()).isEqualTo("https://github.com/example/pull/3");
  }

  @Test
  void findOpenPrUrlReturnsNullWhenNoneIsOpen() {
    when(prGateway.findOpen()).thenReturn(Optional.empty());

    assertThat(activities.findOpenPrUrl()).isNull();
  }

  @Test
  void checkCiDelegatesToTheCiStatusGateway() {
    CiOutcome outcome = new CiOutcome(CiState.GREEN, List.of(), "All non-advisory checks passed");
    when(ciStatusGateway.outcomeFor("abc123")).thenReturn(outcome);

    assertThat(activities.checkCi("abc123")).isEqualTo(outcome);
  }

  @Test
  void ciFailureLogsDelegatesToTheCiStatusGateway() {
    when(ciStatusGateway.failureLogs("abc123")).thenReturn("### build\nfailed");

    assertThat(activities.ciFailureLogs("abc123")).isEqualTo("### build\nfailed");
  }

  @Test
  void commentOnPullRequestDelegatesToTheGateway() {
    activities.commentOnPullRequest(7, "still red");

    verify(prGateway).comment(7, "still red");
  }

  @Test
  void recordRunSavesToTheRepository() {
    CveFixRunRecord record =
        new CveFixRunRecord(
            "workflow-1", "workflow-1", Instant.parse("2026-08-11T00:00:00Z"), null, 2, List.of(),
            null, 0, null);

    activities.recordRun(record);

    verify(runRepository).save(record);
  }

  private static FixProposal oneBumpProposal() {
    return new FixProposal(
        List.of(
            new Bump(
                "pkg:maven/a/a@1.0", "gradle/libs.versions.toml", "1.0", "2.0",
                List.of("CVE-2024-0001"))),
        List.of(), "Bumped one component.");
  }

  private static CveFixProperties properties() {
    return new CveFixProperties(
        true, "simonjamesrowe", "simonrowe-dev-monorepo", null, null, null, null, null, null,
        null, null);
  }
}
