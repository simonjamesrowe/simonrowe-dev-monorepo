package com.simonrowe.factory.linear.linear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinearGatewayReadTest {

  private HttpServer server;
  private final List<String> bodies = new ArrayList<>();
  private final List<String> authHeaders = new ArrayList<>();
  private final AtomicInteger requests = new AtomicInteger();
  private volatile String response = "{}";
  private volatile int status = 200;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/graphql",
        exchange -> {
          requests.incrementAndGet();
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] out = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private LinearGateway gateway() {
    LinearProperties properties =
        new LinearProperties(
            true,
            "lin_api_test",
            "http://localhost:" + server.getAddress().getPort() + "/graphql",
            "SIM",
            null,
            false,
            null,
            null);
    return new LinearGateway(properties, new ObjectMapper());
  }

  @Test
  void resolvesTheTeamTriageStateAndLabelsInOneQueryAndCachesIt() {
    response =
        """
        {"data":{"teams":{"nodes":[{"id":"team-uuid","key":"SIM",
          "states":{"nodes":[{"id":"s-triage","name":"Triage","type":"triage"},
                             {"id":"s-todo","name":"Todo","type":"unstarted"}]},
          "labels":{"nodes":[{"id":"l-deploy","name":"factory:deploy"},
                             {"id":"l-cvefix","name":"factory:cvefix"}]}}]}}}
        """;

    LinearGateway gateway = gateway();
    LinearGateway.TeamContext first = gateway.teamContext();
    LinearGateway.TeamContext second = gateway.teamContext();

    assertThat(first.teamId()).isEqualTo("team-uuid");
    assertThat(first.triageStateId()).isEqualTo("s-triage");
    assertThat(first.labelIds()).containsEntry("factory:deploy", "l-deploy");
    assertThat(second).isSameAs(first);
    assertThat(requests.get()).isEqualTo(1);
    assertThat(authHeaders.get(0)).isEqualTo("lin_api_test");
  }

  @Test
  void failsNonRetryablyWhenTheTeamHasNoTriageState() {
    // Triage is a per-team toggle and the whole suppression design depends on it. Failing loudly
    // here is the difference between a clear error and issues quietly landing in the backlog.
    response =
        """
        {"data":{"teams":{"nodes":[{"id":"t","key":"SIM",
          "states":{"nodes":[{"id":"s","name":"Todo","type":"unstarted"}]},
          "labels":{"nodes":[]}}]}}}
        """;

    assertThatThrownBy(() -> gateway().teamContext())
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("Triage")
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }

  @Test
  void failsNonRetryablyWhenTheTeamKeyMatchesNothing() {
    response = "{\"data\":{\"teams\":{\"nodes\":[]}}}";
    assertThatThrownBy(() -> gateway().teamContext())
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("SIM");
  }

  @Test
  void readsEveryIssueCarryingTheFingerprintIncludingCancelledOnes() {
    // The design turns on this: if a cancelled issue is not returned, suppression stops working
    // and declined bugs are re-filed forever.
    response =
        """
        {"data":{"attachmentsForURL":{"nodes":[
          {"issue":{"id":"i1","identifier":"SIM-1","url":"https://linear.app/i/1",
                    "createdAt":"2026-01-01T00:00:00.000Z","state":{"type":"canceled"}}},
          {"issue":{"id":"i2","identifier":"SIM-2","url":"https://linear.app/i/2",
                    "createdAt":"2026-06-01T00:00:00.000Z","state":{"type":"started"}}}]}}}
        """;

    List<TrackedIssue> issues =
        gateway().issuesForFingerprint("https://factory.simonrowe.dev/fingerprint/abc");

    assertThat(issues).hasSize(2);
    assertThat(issues.get(0).stateType()).isEqualTo(IssueStateType.CANCELED);
    assertThat(issues.get(1).identifier()).isEqualTo("SIM-2");
    assertThat(bodies.get(0)).contains("fingerprint/abc");
  }

  @Test
  void readsDuplicateStateIssueAsDuplicateNotUnknown() {
    // IssueStateType.DUPLICATE was added specifically because attachmentsForURL can return a
    // "duplicate"-type issue, and the gateway must not fall through to UNKNOWN for it — that
    // would classify a declined ticket as open and re-file it.
    response =
        """
        {"data":{"attachmentsForURL":{"nodes":[
          {"issue":{"id":"i3","identifier":"SIM-3","url":"https://linear.app/i/3",
                    "createdAt":"2026-08-27T12:54:56.633Z","state":{"type":"duplicate"}}}]}}}
        """;

    List<TrackedIssue> issues =
        gateway().issuesForFingerprint("https://factory.simonrowe.dev/fingerprint/dup");

    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).stateType()).isEqualTo(IssueStateType.DUPLICATE);
  }

  @Test
  void returnsEmptyWhenNothingCarriesTheFingerprint() {
    response = "{\"data\":{\"attachmentsForURL\":{\"nodes\":[]}}}";
    assertThat(gateway().issuesForFingerprint("https://x/y")).isEmpty();
  }

  @Test
  void classifiesAnUnauthorisedResponseAsNonRetryable() {
    // A read-only or revoked key must not burn a retry budget.
    status = 401;
    response = "{\"errors\":[{\"message\":\"Authentication required\"}]}";
    assertThatThrownBy(() -> gateway().issuesForFingerprint("https://x/y"))
        .isInstanceOf(LinearApiException.class)
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }

  @Test
  void classifiesServerErrorsAndRateLimitsAsRetryable() {
    status = 503;
    response = "upstream down";
    assertThatThrownBy(() -> gateway().issuesForFingerprint("https://x/y"))
        .isInstanceOf(LinearApiException.class)
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(true);

    status = 429;
    assertThatThrownBy(() -> gateway().issuesForFingerprint("https://x/y"))
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(true);
  }

  @Test
  void classifiesGraphQlErrorOnTwoHundredAsNonRetryable() {
    // A malformed query does not fix itself; retrying it is pure cost.
    status = 200;
    response = "{\"errors\":[{\"message\":\"Cannot query field \\\"nope\\\"\"}]}";
    assertThatThrownBy(() -> gateway().issuesForFingerprint("https://x/y"))
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("nope")
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }
}
