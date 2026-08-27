package com.simonrowe.factory.linear.linear;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Linear's GraphQL API, over {@link HttpClient} — the {@code DependencyTrackClient} pattern, so
 * this module adds no dependency.
 *
 * <p>The team, its Triage state and its labels are resolved in one query and cached for the
 * process lifetime, <strong>lazily on first use and never at startup</strong>: an unreachable
 * Linear must not fail the application context and take the GitHub webhook receiver and the
 * {@code code-review} worker down with it, which is the failure mode {@code
 * CveFixScheduleInitializer} documents avoiding.
 */
@Component
public class LinearGateway {

  private static final String TEAM_QUERY =
      "query($key:String!){teams(filter:{key:{eq:$key}}){nodes{id key "
          + "states{nodes{id name type}} labels{nodes{id name}}}}}";

  private static final String ATTACHMENTS_QUERY =
      "query($url:String!){attachmentsForURL(url:$url){nodes{issue{id identifier url "
          + "createdAt state{type}}}}}";

  private final LinearProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  private volatile TeamContext cachedTeam;

  /**
   * Creates the gateway.
   *
   * @param properties the bound {@code factory.linear} configuration
   * @param objectMapper mapper used to build requests and parse responses
   */
  public LinearGateway(final LinearProperties properties, final ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
  }

  /**
   * Resolves and caches the team, its Triage state id and its label ids.
   *
   * @return the resolved context
   * @throws LinearApiException if the team key matches nothing, or the team has no Triage state
   */
  public TeamContext teamContext() {
    TeamContext local = cachedTeam;
    if (local != null) {
      return local;
    }
    synchronized (this) {
      if (cachedTeam != null) {
        return cachedTeam;
      }
      JsonNode team =
          execute(TEAM_QUERY, Map.of("key", properties.teamKey()))
              .path("teams")
              .path("nodes")
              .path(0);
      if (team.isMissingNode() || team.path("id").asText("").isEmpty()) {
        throw new LinearApiException(
            "Linear has no team with key " + properties.teamKey(), false);
      }
      String triageStateId = null;
      for (JsonNode state : team.path("states").path("nodes")) {
        if ("triage".equals(state.path("type").asText())) {
          triageStateId = state.path("id").asText();
        }
      }
      if (triageStateId == null) {
        throw new LinearApiException(
            "Team "
                + properties.teamKey()
                + " has no Triage state — enable Triage on the team in Linear settings; the "
                + "suppression design depends on it",
            false);
      }
      Map<String, String> labels = new HashMap<>();
      for (JsonNode label : team.path("labels").path("nodes")) {
        labels.put(label.path("name").asText(), label.path("id").asText());
      }
      cachedTeam = new TeamContext(team.path("id").asText(), triageStateId, Map.copyOf(labels));
      return cachedTeam;
    }
  }

  /**
   * Every issue carrying a fingerprint, in whatever state — cancelled included.
   *
   * @param fingerprintUrl the synthetic attachment URL from {@code Fingerprint.urlFor}
   * @return the issues found, in the order Linear returned them; empty when none
   * @throws LinearApiException on any API fault
   */
  public List<TrackedIssue> issuesForFingerprint(final String fingerprintUrl) {
    JsonNode nodes =
        execute(ATTACHMENTS_QUERY, Map.of("url", fingerprintUrl))
            .path("attachmentsForURL")
            .path("nodes");
    List<TrackedIssue> issues = new ArrayList<>();
    for (JsonNode node : nodes) {
      JsonNode issue = node.path("issue");
      if (issue.isMissingNode() || issue.path("id").asText("").isEmpty()) {
        continue;
      }
      issues.add(
          new TrackedIssue(
              issue.path("id").asText(),
              issue.path("identifier").asText(),
              issue.path("url").asText(),
              IssueStateType.from(issue.path("state").path("type").asText(null)),
              Instant.parse(issue.path("createdAt").asText("1970-01-01T00:00:00.000Z"))));
    }
    return List.copyOf(issues);
  }

  /**
   * Executes a GraphQL document and returns its {@code data} node.
   *
   * @param document the query or mutation
   * @param variables the variables, which may be empty
   * @return the {@code data} node
   * @throws LinearApiException on transport, status or GraphQL errors
   */
  protected JsonNode execute(final String document, final Map<String, Object> variables) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("query", document);
    payload.set("variables", objectMapper.valueToTree(variables));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(properties.apiBaseUrl()))
            .timeout(properties.requestTimeout())
            // Linear personal API keys go in Authorization with no Bearer prefix.
            .header("Authorization", properties.apiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException exception) {
      throw new LinearApiException("Linear request failed", true, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new LinearApiException("Interrupted calling Linear", true, exception);
    }

    int status = response.statusCode();
    if (status == 401 || status == 403) {
      throw new LinearApiException(
          "Linear rejected the API key with " + status + " — check LINEAR_API_KEY and its scopes",
          false);
    }
    if (status == 429 || status >= 500) {
      throw new LinearApiException("Linear returned " + status, true);
    }
    if (status < 200 || status >= 300) {
      throw new LinearApiException("Linear returned " + status + ": " + response.body(), false);
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(response.body());
    } catch (IOException exception) {
      throw new LinearApiException("Linear returned unparseable JSON", false, exception);
    }
    if (root.has("errors") && !root.path("errors").isEmpty()) {
      throw new LinearApiException("Linear GraphQL error: " + root.path("errors"), false);
    }
    return root.path("data");
  }

  /**
   * The resolved team, cached for the process lifetime.
   *
   * @param teamId the team UUID
   * @param triageStateId the id of the team's {@code triage}-type workflow state
   * @param labelIds label name to label id, for the labels that exist on the team
   */
  public record TeamContext(String teamId, String triageStateId, Map<String, String> labelIds) {
  }
}
