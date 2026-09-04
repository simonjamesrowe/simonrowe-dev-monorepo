package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtifactCountsReaderTest {

  private final LinearIssueRepository repository = mock(LinearIssueRepository.class);

  @Test
  void countsOpenLinearIssuesAsInFlight() {
    when(repository.findAll()).thenReturn(List.of(
        record("a", IssueStateType.TRIAGE, Instant.now()),
        record("b", IssueStateType.STARTED, Instant.now()),
        record("c", IssueStateType.COMPLETED, Instant.now()),
        record("d", IssueStateType.CANCELED, Instant.now())));

    NodeCounts counts = reader().linearCounts();

    assertThat(counts.inFlight()).isEqualTo(2);
  }

  @Test
  void treatsAnUnknownLinearStateAsOpen() {
    // Same reasoning as the sink: if Linear adds a state type, the safe failure is to keep
    // showing the ticket, not to quietly declare it handled.
    when(repository.findAll())
        .thenReturn(List.of(record("a", IssueStateType.UNKNOWN, Instant.now())));

    assertThat(reader().linearCounts().inFlight()).isEqualTo(1);
  }

  @Test
  void countsRecentlyClosedLinearIssuesAsSettledWithinTheWindow() {
    Instant recent = Instant.now().minusSeconds(3600);
    Instant old = Instant.now().minusSeconds(60 * 60 * 48);
    when(repository.findAll()).thenReturn(List.of(
        record("a", IssueStateType.COMPLETED, recent),
        record("b", IssueStateType.CANCELED, recent),
        record("c", IssueStateType.COMPLETED, old)));

    NodeCounts counts = reader().linearCounts();

    assertThat(counts.ok24h()).isEqualTo(2);
    assertThat(counts.inFlight()).isZero();
  }

  @Test
  void reportsZeroRatherThanNullWhenLinearHasFiledNothing() {
    // An empty collection is a known fact. Null is reserved for "could not read", and the two
    // render differently: IDLE against UNAVAILABLE.
    when(repository.findAll()).thenReturn(List.of());

    assertThat(reader().linearCounts()).isEqualTo(NodeCounts.NONE);
  }

  @Test
  void returnsNullWhenTheLinearCollectionCannotBeRead() {
    when(repository.findAll()).thenThrow(new RuntimeException("mongo down"));

    assertThat(reader().linearCounts()).isNull();
  }

  @Test
  void returnsNullForGitHubBackedNodesWhenNoInstallationCanBeResolved() {
    // The reviewer's App credentials are configured but unusable in a local run. A console that
    // threw here would be unopenable on a developer machine, which is where it is most needed.
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo")).thenReturn(null);
    ArtifactCountsReader reader = new ArtifactCountsReader(
        repository, credentials, "simonjamesrowe", "simonrowe-dev-monorepo");

    assertThat(reader.pullRequestCounts()).isNull();
    assertThat(reader.mainCounts()).isNull();
    assertThat(reader.agentSetupCounts()).isNull();
  }

  @Test
  void returnsNullForGitHubBackedNodesWhenGitHubItselfFails() {
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo"))
        .thenThrow(new RuntimeException("GitHub is unreachable"));
    ArtifactCountsReader reader = new ArtifactCountsReader(
        repository, credentials, "simonjamesrowe", "simonrowe-dev-monorepo");

    assertThat(reader.pullRequestCounts()).isNull();
  }

  private ArtifactCountsReader reader() {
    return new ArtifactCountsReader(
        repository, mock(GitHubCredentials.class), "simonjamesrowe", "simonrowe-dev-monorepo");
  }

  private static LinearIssueRecord record(
      final String id, final IssueStateType state, final Instant lastSeen) {
    return new LinearIssueRecord(
        id, "logwatch", "v1", List.of("k"), "iss", "SIM-1", "https://linear.app/x",
        false, lastSeen, lastSeen, 1, state, List.of());
  }
}
