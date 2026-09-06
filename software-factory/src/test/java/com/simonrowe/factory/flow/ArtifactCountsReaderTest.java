package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ArtifactCountsReaderTest {

  /**
   * Never actually contacted: every test that reaches {@code github()} points at a local
   * server.
   */
  private static final String UNUSED_GITHUB_BASE_URL = "https://api.github.com";

  private final LinearIssueRepository repository = mock(LinearIssueRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper();

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
    ArtifactCountsReader reader = reader(credentials, UNUSED_GITHUB_BASE_URL);

    assertThat(reader.pullRequestCounts()).isNull();
    assertThat(reader.mainCounts()).isNull();
    assertThat(reader.agentSetupCounts()).isNull();
    assertThat(reader.pullRequestItems()).isNull();
    assertThat(reader.mainItems()).isNull();
    assertThat(reader.agentSetupItems()).isNull();
  }

  @Test
  void returnsNullForGitHubBackedNodesWhenGitHubItselfFails() {
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo"))
        .thenThrow(new RuntimeException("GitHub is unreachable"));
    ArtifactCountsReader reader = reader(credentials, UNUSED_GITHUB_BASE_URL);

    assertThat(reader.pullRequestCounts()).isNull();
    assertThat(reader.pullRequestItems()).isNull();
  }

  @Test
  void listsOpenLinearIssuesNewestFirstAsReadableItems() {
    Instant older = Instant.now().minusSeconds(3600);
    Instant newer = Instant.now();
    when(repository.findAll()).thenReturn(List.of(
        linearRecord("SIM-1", List.of("openssl", "CVE-2026-1"), "https://linear.app/sim-1",
            IssueStateType.TRIAGE, older),
        linearRecord("SIM-2", List.of("slow query"), "https://linear.app/sim-2",
            IssueStateType.STARTED, newer),
        linearRecord("SIM-3", List.of("already fixed"), "https://linear.app/sim-3",
            IssueStateType.COMPLETED, newer)));

    List<FlowDetail.Item> items = reader().linearItems();

    // Only the two open tickets, newest lastSeenAt first — the completed one is excluded.
    assertThat(items).extracting(FlowDetail.Item::id).containsExactly("SIM-2", "SIM-1");
    assertThat(items.get(0).title()).isEqualTo("slow query");
    assertThat(items.get(1).title()).isEqualTo("openssl · CVE-2026-1");
    assertThat(items.get(0).url()).isEqualTo("https://linear.app/sim-2");
  }

  @Test
  void treatsAnUnknownLinearStateAsOpenInItemsToo() {
    // Same reasoning as linearCounts(): if Linear adds a state type, the safe failure is to keep
    // listing the ticket, not to quietly drop it from view.
    when(repository.findAll()).thenReturn(List.of(
        linearRecord("SIM-9", List.of("k"), "https://linear.app/sim-9",
            IssueStateType.UNKNOWN, Instant.now())));

    assertThat(reader().linearItems())
        .extracting(FlowDetail.Item::id).containsExactly("SIM-9");
  }

  @Test
  void fallsBackToTheIssueIdentifierWhenKeyPartsIsBlank() {
    // keyParts is normalised to an empty (never null) list by the record, but not guaranteed
    // non-empty — a future producer filing with no key parts must not put a blank row in the
    // drawer.
    when(repository.findAll()).thenReturn(List.of(
        linearRecord("SIM-4", List.of(), "https://linear.app/sim-4",
            IssueStateType.TRIAGE, Instant.now())));

    assertThat(reader().linearItems().get(0).title()).isEqualTo("SIM-4");
  }

  @Test
  void returnsNullLinearItemsWhenTheCollectionCannotBeRead() {
    when(repository.findAll()).thenThrow(new RuntimeException("mongo down"));

    assertThat(reader().linearItems()).isNull();
  }

  @Test
  void listsOpenPullRequestsFromGitHubsJsonArrayResponseAsReadableItems() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/repos/simonjamesrowe/simonrowe-dev-monorepo/pulls",
        exchange -> {
          byte[] body = ("[{\"number\":7,\"title\":\"Fix flake\",\"draft\":false,"
              + "\"mergeable\":true,\"html_url\":\"https://github.com/x/pull/7\"},"
              + "{\"number\":8,\"title\":\"WIP\",\"draft\":true,"
              + "\"html_url\":\"https://github.com/x/pull/8\"}]")
              .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();

    try {
      GitHubCredentials credentials = mock(GitHubCredentials.class);
      when(credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo"))
          .thenReturn(123L);
      when(credentials.accessToken(123L)).thenReturn("test-token");
      ArtifactCountsReader reader =
          reader(credentials, "http://127.0.0.1:" + server.getAddress().getPort());

      List<FlowDetail.Item> items = reader.pullRequestItems();

      assertThat(items).hasSize(2);
      assertThat(items.get(0).id()).isEqualTo("#7");
      assertThat(items.get(0).title()).isEqualTo("Fix flake");
      assertThat(items.get(0).status()).isEqualTo("mergeable");
      assertThat(items.get(0).url()).isEqualTo("https://github.com/x/pull/7");
      assertThat(items.get(1).status()).isEqualTo("draft");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void listsRecentMergesToMainFromGitHubsJsonArrayResponse() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/repos/simonjamesrowe/simonrowe-dev-monorepo/commits",
        exchange -> {
          byte[] body = ("[{\"sha\":\"abcdef1234567890\",\"html_url\":"
              + "\"https://github.com/x/commit/abcdef1234567890\",\"commit\":{\"message\":"
              + "\"fix: patch the thing\\n\\nlonger body\",\"committer\":{\"date\":"
              + "\"2026-09-01T12:00:00Z\"}}}]").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();

    try {
      GitHubCredentials credentials = mock(GitHubCredentials.class);
      when(credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo"))
          .thenReturn(123L);
      when(credentials.accessToken(123L)).thenReturn("test-token");
      ArtifactCountsReader reader =
          reader(credentials, "http://127.0.0.1:" + server.getAddress().getPort());

      List<FlowDetail.Item> items = reader.mainItems();

      assertThat(items).hasSize(1);
      assertThat(items.get(0).id()).isEqualTo("abcdef1");
      assertThat(items.get(0).title()).isEqualTo("fix: patch the thing");
      assertThat(items.get(0).status()).isEqualTo("merged");
      assertThat(items.get(0).url()).isEqualTo("https://github.com/x/commit/abcdef1234567890");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void listsOpenAgentSetupPullRequestsFromTheirOwnRepository() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/repos/simonjamesrowe/agent-setup/pulls",
        exchange -> {
          byte[] body = ("[{\"number\":3,\"title\":\"Teach the reviewer a new lesson\","
              + "\"html_url\":\"https://github.com/x/agent-setup/pull/3\"}]")
              .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();

    try {
      GitHubCredentials credentials = mock(GitHubCredentials.class);
      when(credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo"))
          .thenReturn(123L);
      when(credentials.accessToken(123L)).thenReturn("test-token");
      ArtifactCountsReader reader =
          reader(credentials, "http://127.0.0.1:" + server.getAddress().getPort());

      List<FlowDetail.Item> items = reader.agentSetupItems();

      assertThat(items).hasSize(1);
      assertThat(items.get(0).id()).isEqualTo("#3");
      assertThat(items.get(0).title()).isEqualTo("Teach the reviewer a new lesson");
      assertThat(items.get(0).url())
          .isEqualTo("https://github.com/x/agent-setup/pull/3");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void capsGitHubBackedItemListsAtTwenty() throws IOException {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < 25; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"number\":").append(i).append(",\"title\":\"pr-").append(i)
          .append("\",\"html_url\":\"https://github.com/x/pull/").append(i).append("\"}");
    }
    json.append(']');
    byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/repos/simonjamesrowe/simonrowe-dev-monorepo/pulls",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();

    try {
      GitHubCredentials credentials = mock(GitHubCredentials.class);
      when(credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo"))
          .thenReturn(123L);
      when(credentials.accessToken(123L)).thenReturn("test-token");
      ArtifactCountsReader reader =
          reader(credentials, "http://127.0.0.1:" + server.getAddress().getPort());

      assertThat(reader.pullRequestItems()).hasSize(20);
    } finally {
      server.stop(0);
    }
  }

  /**
   * The only genuinely new logic in this class — header construction and JSON-array counting —
   * needs a real request/response round trip to exercise, not just the {@code installationId}
   * failure paths above. Uses an in-process {@link HttpServer} on an ephemeral port, the same
   * pattern {@code GitHubCredentialsTest} uses: a local server, never a real network call.
   */
  @Test
  void countsOpenPullRequestsFromGitHubsJsonArrayResponse() throws IOException {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> accept = new AtomicReference<>();
    AtomicReference<String> apiVersion = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/repos/simonjamesrowe/simonrowe-dev-monorepo/pulls",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          accept.set(exchange.getRequestHeaders().getFirst("Accept"));
          apiVersion.set(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
          byte[] body =
              "[{\"number\":1},{\"number\":2},{\"number\":3}]".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();

    try {
      GitHubCredentials credentials = mock(GitHubCredentials.class);
      when(credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo"))
          .thenReturn(123L);
      when(credentials.accessToken(123L)).thenReturn("test-token");
      ArtifactCountsReader reader =
          reader(credentials, "http://127.0.0.1:" + server.getAddress().getPort());

      NodeCounts counts = reader.pullRequestCounts();

      assertThat(counts.inFlight()).isEqualTo(3);
      assertThat(authorization.get()).isEqualTo("Bearer test-token");
      assertThat(accept.get()).isEqualTo("application/vnd.github+json");
      assertThat(apiVersion.get()).isEqualTo("2026-03-10");
    } finally {
      server.stop(0);
    }
  }

  private ArtifactCountsReader reader() {
    return reader(mock(GitHubCredentials.class), UNUSED_GITHUB_BASE_URL);
  }

  private ArtifactCountsReader reader(
      final GitHubCredentials credentials, final String gitHubApiBaseUrl) {
    return new ArtifactCountsReader(
        repository, credentials, objectMapper, gitHubApiBaseUrl,
        "simonjamesrowe", "simonrowe-dev-monorepo");
  }

  private static LinearIssueRecord record(
      final String id, final IssueStateType state, final Instant lastSeen) {
    return new LinearIssueRecord(
        id, "logwatch", "v1", List.of("k"), "iss", "SIM-1", "https://linear.app/x",
        false, lastSeen, lastSeen, 1, state, List.of());
  }

  private static LinearIssueRecord linearRecord(
      final String issueIdentifier, final List<String> keyParts, final String issueUrl,
      final IssueStateType state, final Instant lastSeen) {
    return new LinearIssueRecord(
        issueIdentifier, "logwatch", "v1", keyParts, "issue-" + issueIdentifier, issueIdentifier,
        issueUrl, false, lastSeen, lastSeen, 1, state, List.of());
  }
}
