package com.simonrowe.factory.linear.linear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinearGatewayWriteTest {

  private static final String TEAM_RESPONSE =
      """
      {"data":{"teams":{"nodes":[{"id":"team-uuid","key":"SIM",
        "states":{"nodes":[{"id":"s-triage","name":"Triage","type":"triage"}]},
        "labels":{"nodes":[{"id":"l-deploy","name":"factory:deploy"}]}}]}}}
      """;

  private HttpServer server;
  private final List<String> bodies = new ArrayList<>();
  private final Map<String, String> byOperation = new LinkedHashMap<>();

  @BeforeEach
  void startServer() throws IOException {
    byOperation.put("teams", TEAM_RESPONSE);
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/graphql",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          bodies.add(body);
          String answer = "{\"data\":{}}";
          for (Map.Entry<String, String> entry : byOperation.entrySet()) {
            if (body.contains(entry.getKey())) {
              answer = entry.getValue();
            }
          }
          byte[] out = answer.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, out.length);
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
    return new LinearGateway(
        new LinearProperties(
            true,
            "k",
            "http://localhost:" + server.getAddress().getPort() + "/graphql",
            "SIM",
            null,
            false,
            null,
            null),
        new ObjectMapper());
  }

  @Test
  void createsAnIssueInTriageWithThePriorityAndLabel() {
    byOperation.put(
        "issueCreate",
        """
        {"data":{"issueCreate":{"success":true,"issue":{"id":"i9","identifier":"SIM-9",
          "url":"https://linear.app/i/9"}}}}
        """);

    LinearGateway.CreatedIssue created =
        gateway().createIssue("recreate failed on backend", "the body", 1, "factory:deploy");

    assertThat(created.identifier()).isEqualTo("SIM-9");
    assertThat(created.url()).isEqualTo("https://linear.app/i/9");
    String mutation = bodies.get(bodies.size() - 1);
    assertThat(mutation).contains("team-uuid").contains("s-triage").contains("l-deploy");
    assertThat(mutation).contains("\"priority\":1");
  }

  @Test
  void createsAnIssueWithNoLabelWhenTheLabelDoesNotExistOnTheTeam() {
    // A missing label must not lose the finding. It files unlabelled and the runbook says to
    // create the label.
    byOperation.put(
        "issueCreate",
        """
        {"data":{"issueCreate":{"success":true,"issue":{"id":"i1","identifier":"SIM-1",
          "url":"u"}}}}
        """);

    assertThat(gateway().createIssue("t", "b", 3, "factory:bughunter").identifier())
        .isEqualTo("SIM-1");
    assertThat(bodies.get(bodies.size() - 1)).doesNotContain("labelIds");
  }

  @Test
  void failsNonRetryablyWhenIssueCreateReportsUnsuccessful() {
    byOperation.put("issueCreate", "{\"data\":{\"issueCreate\":{\"success\":false}}}");
    assertThatThrownBy(() -> gateway().createIssue("t", "b", 3, "factory:deploy"))
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("issueCreate")
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }

  @Test
  void attachesTheFingerprintUrl() {
    byOperation.put(
        "attachmentCreate",
        "{\"data\":{\"attachmentCreate\":{\"success\":true,\"attachment\":{\"id\":\"a1\"}}}}");

    gateway().attachFingerprint("i9", "https://factory.simonrowe.dev/fingerprint/abc");

    assertThat(bodies.get(bodies.size() - 1))
        .contains("attachmentCreate")
        .contains("fingerprint/abc")
        .contains("i9");
  }

  @Test
  void failsNonRetryablyWhenAttachFingerprintReportsUnsuccessful() {
    // The fingerprint attachment is the only thing that lets the sink find an issue again; a
    // silently swallowed failure here would file a duplicate ticket on every occurrence.
    byOperation.put("attachmentCreate", "{\"data\":{\"attachmentCreate\":{\"success\":false}}}");
    assertThatThrownBy(
            () -> gateway().attachFingerprint("i9", "https://factory.simonrowe.dev/fp/abc"))
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("attachmentCreate")
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }

  @Test
  void addsComment() {
    byOperation.put(
        "commentCreate",
        "{\"data\":{\"commentCreate\":{\"success\":true,\"comment\":{\"id\":\"c1\"}}}}");

    gateway().addComment("i9", "seen again at deadbeef");

    assertThat(bodies.get(bodies.size() - 1)).contains("commentCreate").contains("deadbeef");
  }

  @Test
  void failsNonRetryablyWhenAddCommentReportsUnsuccessful() {
    byOperation.put("commentCreate", "{\"data\":{\"commentCreate\":{\"success\":false}}}");
    assertThatThrownBy(() -> gateway().addComment("i9", "seen again"))
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("commentCreate")
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }

  @Test
  void relatingIssuesIsBestEffortAndNeverThrows() {
    // The regression issue's body always names its predecessor, so the relation is a nicety. If
    // Linear rejects the relation type, losing the link must not lose the ticket.
    byOperation.put(
        "issueRelationCreate", "{\"errors\":[{\"message\":\"Invalid relation type\"}]}");

    gateway().relateIssues("i9", "i1");

    assertThat(bodies.get(bodies.size() - 1)).contains("issueRelationCreate");
  }
}
