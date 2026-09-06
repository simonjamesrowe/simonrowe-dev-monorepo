package com.simonrowe.factory.linear.linear;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final String CREATE_ISSUE =
      "mutation($input:IssueCreateInput!){issueCreate(input:$input){success "
          + "issue{id identifier url}}}";

  private static final String CREATE_ATTACHMENT =
      "mutation($input:AttachmentCreateInput!){attachmentCreate(input:$input){success "
          + "attachment{id}}}";

  private static final String CREATE_COMMENT =
      "mutation($input:CommentCreateInput!){commentCreate(input:$input){success comment{id}}}";

  private static final String CREATE_RELATION =
      "mutation($input:IssueRelationCreateInput!){issueRelationCreate(input:$input){success}}";

  private static final String UPDATE_ISSUE =
      "mutation($id:String!,$input:IssueUpdateInput!){issueUpdate(id:$id,input:$input)"
          + "{success issue{id identifier url}}}";

  // GraphQL wire names shared by the queries/mutations above and the response handling below.
  // VAR_INPUT is the variable every mutation binds its input object to; FIELD_NODES is the
  // items field of a GraphQL connection (teams/states/labels/attachments all use it);
  // FIELD_ISSUE_ID is the input field naming which issue a mutation acts on; FIELD_SUCCESS and
  // FIELD_ERRORS are the mutation-result and top-level-error fields respectively.
  private static final String VAR_INPUT = "input";
  private static final String FIELD_NODES = "nodes";
  private static final String FIELD_ISSUE_ID = "issueId";
  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_ERRORS = "errors";

  private static final Logger log = LoggerFactory.getLogger(LinearGateway.class);

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
              .path(FIELD_NODES)
              .path(0);
      if (team.isMissingNode() || team.path("id").asText("").isEmpty()) {
        throw new LinearApiException(
            "Linear has no team with key " + properties.teamKey(), false);
      }
      String triageStateId = null;
      for (JsonNode state : team.path("states").path(FIELD_NODES)) {
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
      for (JsonNode label : team.path("labels").path(FIELD_NODES)) {
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
            .path(FIELD_NODES);
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
   * Creates an issue in the team's Triage state.
   *
   * @param title the issue title
   * @param body the issue description, in Markdown
   * @param priority the Linear priority integer
   * @param labelName the label to apply; skipped with a {@code WARN} when the team has no such
   *     label, because a missing label must not cost the finding — but a silent skip would make
   *     the missing label undetectable
   * @return the created issue
   * @throws LinearApiException on any API fault, or when Linear reports the mutation unsuccessful
   */
  public CreatedIssue createIssue(
      final String title, final String body, final int priority, final String labelName) {
    TeamContext team = teamContext();
    ObjectNode input = objectMapper.createObjectNode();
    input.put("teamId", team.teamId());
    input.put("stateId", team.triageStateId());
    input.put("title", title);
    input.put("description", body);
    input.put("priority", priority);
    String labelId = team.labelIds().get(labelName);
    if (labelId != null) {
      input.putArray("labelIds").add(labelId);
    } else {
      // The only runtime signal that human prerequisite 2 was skipped. Silently omitting the
      // label files every ticket unlabelled, forever, successfully, with nothing anywhere to
      // notice - and the label is how a human tells factory tickets apart in Triage.
      log.warn(
          "Team {} has no label named {} - filing this issue unlabelled. Create the label in "
              + "Linear; teamContext() caches positively for the process lifetime, so the "
              + "container needs restarting before the new label is picked up.",
          properties.teamKey(),
          labelName);
    }
    JsonNode result = execute(CREATE_ISSUE, Map.of(VAR_INPUT, input)).path("issueCreate");
    if (!result.path(FIELD_SUCCESS).asBoolean(false)) {
      throw new LinearApiException("Linear issueCreate reported failure", false);
    }
    JsonNode issue = result.path("issue");
    return new CreatedIssue(
        issue.path("id").asText(), issue.path("identifier").asText(), issue.path("url").asText());
  }

  /**
   * Stamps the fingerprint onto an issue. This is what makes the issue findable again.
   *
   * @param issueId the Linear issue UUID
   * @param fingerprintUrl the synthetic key URL
   * @throws LinearApiException on any API fault, or when Linear reports the mutation unsuccessful
   */
  public void attachFingerprint(final String issueId, final String fingerprintUrl) {
    attachUrl(issueId, fingerprintUrl, "factory fingerprint");
  }

  /** Attaches an arbitrary related URL to an issue. */
  public void attachUrl(final String issueId, final String url, final String title) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put(FIELD_ISSUE_ID, issueId);
    input.put("url", url);
    input.put("title", title);
    JsonNode result = execute(CREATE_ATTACHMENT, Map.of(VAR_INPUT, input)).path("attachmentCreate");
    if (!result.path(FIELD_SUCCESS).asBoolean(false)) {
      throw new LinearApiException("Linear attachmentCreate reported failure", false);
    }
  }

  /**
   * Adds a comment recording one more occurrence.
   *
   * @param issueId the Linear issue UUID
   * @param body the comment, in Markdown
   * @throws LinearApiException on any API fault, or when Linear reports the mutation unsuccessful
   */
  public void addComment(final String issueId, final String body) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put(FIELD_ISSUE_ID, issueId);
    input.put("body", body);
    JsonNode result = execute(CREATE_COMMENT, Map.of(VAR_INPUT, input)).path("commentCreate");
    if (!result.path(FIELD_SUCCESS).asBoolean(false)) {
      throw new LinearApiException("Linear commentCreate reported failure", false);
    }
  }

  /**
   * Rewrites an existing issue's description, and optionally moves it to another state.
   *
   * <p>This is what {@link com.simonrowe.factory.linear.domain.FilingMode#REFRESH} and
   * {@link com.simonrowe.factory.linear.domain.FilingMode#ROLLING} act through. Unlike
   * {@link #relateIssues} it is <strong>not</strong> best-effort: a failed update leaves the
   * ticket describing an old occurrence with nothing anywhere saying so, which is the exact
   * silent staleness those modes exist to remove.
   *
   * @param issueId the Linear issue UUID
   * @param description the new description, in Markdown
   * @param stateId the workflow state to move the issue to, or null to leave the state alone
   * @throws LinearApiException on any API fault, or when Linear reports the mutation unsuccessful
   */
  public void updateIssue(final String issueId, final String description, final String stateId) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put("description", description);
    if (stateId != null) {
      input.put("stateId", stateId);
    }
    JsonNode result =
        execute(UPDATE_ISSUE, Map.of("id", issueId, VAR_INPUT, input)).path("issueUpdate");
    if (!result.path(FIELD_SUCCESS).asBoolean(false)) {
      throw new LinearApiException("Linear issueUpdate reported failure", false);
    }
  }

  /**
   * Links a regression issue to the issue that claimed to fix it.
   *
   * <p><strong>Best effort by design.</strong> The regression issue's body always names its
   * predecessor, so the relation is a convenience. Losing the link must never lose the ticket, so
   * every fault here is swallowed.
   *
   * @param issueId the new regression issue
   * @param relatedIssueId the completed issue it regressed from
   */
  public void relateIssues(final String issueId, final String relatedIssueId) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put(FIELD_ISSUE_ID, issueId);
    input.put("relatedIssueId", relatedIssueId);
    input.put("type", "related");
    try {
      execute(CREATE_RELATION, Map.of(VAR_INPUT, input));
    } catch (LinearApiException exception) {
      log.warn("Could not link {} as a regression of {}", issueId, relatedIssueId, exception);
    }
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
    } catch (JacksonException exception) {
      throw new LinearApiException("Linear returned unparseable JSON", false, exception);
    }
    if (root.has(FIELD_ERRORS) && !root.path(FIELD_ERRORS).isEmpty()) {
      throw new LinearApiException("Linear GraphQL error: " + root.path(FIELD_ERRORS), false);
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

  /**
   * An issue Linear has just created.
   *
   * @param id the issue UUID
   * @param identifier the human identifier, e.g. {@code SIM-42}
   * @param url the issue's web URL
   */
  public record CreatedIssue(String id, String identifier, String url) {
  }
}
