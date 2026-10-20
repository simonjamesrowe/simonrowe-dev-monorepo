package com.simonrowe.factory.linear.linear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The ticket operations the ticket agent uses: read, list, label and move by state name. */
class LinearGatewayTicketTest {

  private static final String TEAM =
      """
      {"data":{"teams":{"nodes":[{"id":"team-uuid","key":"SIM",
        "states":{"nodes":[{"id":"s-triage","name":"Triage","type":"triage"},
                           {"id":"s-progress","name":"In Progress","type":"started"},
                           {"id":"s-review","name":"In Review","type":"started"},
                           {"id":"s-done","name":"Done","type":"completed"}]},
        "labels":{"nodes":[%s]}}]}}}""";

  private static final String TICKET =
      """
      {"id":"i1","identifier":"SIM-107","title":"ERROR","description":null,"url":"u",
       "priority":2,"createdAt":"2026-10-19T10:00:00.000Z",
       "state":{"name":"Triage","type":"triage"},
       "labels":{"nodes":[{"name":"factory:logwatch"},{"name":"factory:agent"}]}}""";

  private HttpServer server;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final Map<String, List<String>> answers = new ConcurrentHashMap<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/graphql", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      bodies.add(body);
      String answer = "{\"data\":{}}";
      for (Map.Entry<String, List<String>> entry : answers.entrySet()) {
        if (body.contains(entry.getKey())) {
          List<String> queue = entry.getValue();
          answer = queue.size() > 1 ? queue.remove(0) : queue.get(0);
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

  private void answer(final String operation, final String... responses) {
    answers.put(operation, new CopyOnWriteArrayList<>(List.of(responses)));
  }

  private LinearGateway gateway() {
    return new LinearGateway(
        new LinearProperties(true, "k",
            "http://localhost:" + server.getAddress().getPort() + "/graphql", "SIM", null, false,
            null, null),
        new ObjectMapper());
  }

  private long calls(final String operation) {
    return bodies.stream().filter(body -> body.contains(operation)).count();
  }

  @Test
  void readsTicketByIdentifier() {
    answer("issue(id:", "{\"data\":{\"issue\":" + TICKET + "}}");

    LinearTicket ticket = gateway().ticket("SIM-107");

    assertThat(ticket).isEqualTo(new LinearTicket("i1", "SIM-107", "ERROR", "", "u",
        IssueStateType.TRIAGE, "Triage", List.of("factory:logwatch", "factory:agent"), 2,
        Instant.parse("2026-10-19T10:00:00Z")));
    assertThat(ticket.hasLabel("FACTORY:AGENT")).isTrue();
  }

  @Test
  void unknownTicketIsNull() {
    answer("issue(id:", "{\"data\":{\"issue\":null}}");

    assertThat(gateway().ticket("SIM-404")).isNull();
  }

  @Test
  void listsOpenTicketsCarryingLabelOnTheTeam() {
    answer("issues(first", "{\"data\":{\"issues\":{\"nodes\":[" + TICKET + "]}}}");

    assertThat(gateway().openTicketsLabelled("factory:agent"))
        .extracting(LinearTicket::identifier).containsExactly("SIM-107");
    assertThat(bodies.get(0)).contains("\"team\":\"SIM\"").contains("\"label\":\"factory:agent\"")
        .contains("nin");
  }

  @Test
  void addsAnExistingLabelWithoutCreatingIt() {
    answer("teams", TEAM.formatted("{\"id\":\"l-agent\",\"name\":\"Factory:Agent\"}"));
    answer("issueAddLabel", "{\"data\":{\"issueAddLabel\":{\"success\":true}}}");

    gateway().addLabel("i1", "factory:agent");

    assertThat(calls("issueLabelCreate")).isZero();
    assertThat(bodies.get(bodies.size() - 1)).contains("l-agent");
  }

  /**
   * The team is cached for the process lifetime, so a label created after startup is looked for
   * again before it is created: the first read misses, the second finds it.
   */
  @Test
  void looksAgainBeforeCreatingLabelTheCacheDoesNotHave() {
    answer("teams", TEAM.formatted(""),
        TEAM.formatted("{\"id\":\"l-new\",\"name\":\"agent:stuck\"}"));
    answer("issueAddLabel", "{\"data\":{\"issueAddLabel\":{\"success\":true}}}");

    gateway().addLabel("i1", "agent:stuck");

    assertThat(calls("teams")).isEqualTo(2);
    assertThat(calls("issueLabelCreate")).isZero();
  }

  @Test
  void createsLabelThatDoesNotExistAndRemembersIt() {
    answer("teams", TEAM.formatted(""));
    answer("issueLabelCreate",
        """
        {"data":{"issueLabelCreate":{"success":true,\
        "issueLabel":{"id":"l-made","name":"factory:agent"}}}}""");
    answer("issueAddLabel", "{\"data\":{\"issueAddLabel\":{\"success\":true}}}");
    LinearGateway gateway = gateway();

    gateway.addLabel("i1", "factory:agent");
    gateway.addLabel("i2", "factory:agent");

    assertThat(calls("issueLabelCreate")).isEqualTo(1);
    assertThat(bodies.stream().filter(body -> body.contains("issueAddLabel")))
        .allMatch(body -> body.contains("l-made"));
  }

  @Test
  void refusedLabelCreationIsAnError() {
    answer("teams", TEAM.formatted(""));
    answer("issueLabelCreate", "{\"data\":{\"issueLabelCreate\":{\"success\":false}}}");

    assertThatThrownBy(() -> gateway().addLabel("i1", "factory:agent"))
        .isInstanceOf(LinearApiException.class);
  }

  @Test
  void removingLabelTheTeamDoesNotHaveDoesNothing() {
    answer("teams", TEAM.formatted(""));

    gateway().removeLabel("i1", "agent:stuck");

    assertThat(calls("issueRemoveLabel")).isZero();
  }

  @Test
  void removesLabelTheTeamHas() {
    answer("teams", TEAM.formatted("{\"id\":\"l-agent\",\"name\":\"factory:agent\"}"));
    answer("issueRemoveLabel", "{\"data\":{\"issueRemoveLabel\":{\"success\":true}}}");

    gateway().removeLabel("i1", "factory:agent");

    assertThat(calls("issueRemoveLabel")).isEqualTo(1);
  }

  @Test
  void refusedLabelChangeIsAnError() {
    answer("teams", TEAM.formatted("{\"id\":\"l-agent\",\"name\":\"factory:agent\"}"));
    answer("issueAddLabel", "{\"data\":{\"issueAddLabel\":{\"success\":false}}}");
    answer("issueRemoveLabel", "{\"data\":{\"issueRemoveLabel\":{\"success\":false}}}");
    LinearGateway gateway = gateway();

    assertThatThrownBy(() -> gateway.addLabel("i1", "factory:agent"))
        .isInstanceOf(LinearApiException.class);
    assertThatThrownBy(() -> gateway.removeLabel("i1", "factory:agent"))
        .isInstanceOf(LinearApiException.class);
  }

  /** Two started states exist, so a move is by name: the type cannot tell them apart. */
  @Test
  void movesTicketToNamedState() {
    answer("teams", TEAM.formatted(""));
    answer("issueUpdate", "{\"data\":{\"issueUpdate\":{\"success\":true}}}");

    gateway().moveToState("i1", "In Review");

    assertThat(bodies.get(bodies.size() - 1)).contains("s-review").doesNotContain("s-progress");
  }

  @Test
  void stateTheTeamDoesNotHaveIsAnError() {
    answer("teams", TEAM.formatted(""));

    assertThatThrownBy(() -> gateway().moveToState("i1", "Shipped"))
        .isInstanceOf(LinearApiException.class).hasMessageContaining("Shipped");
  }
}
