package com.simonrowe.factory.codereview.github;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.AutoMergeState;
import com.simonrowe.factory.codereview.domain.ChangedFiles;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads what an auto-merge decision needs from GitHub, and arms or withdraws auto-merge.
 *
 * <p>Reads are REST, because one pull request read carries every field the decision needs,
 * including who armed any existing auto-merge. The two writes are GraphQL, because REST has no way
 * to arm auto-merge at all.
 *
 * <p>No new App permission: arming needs {@code contents: write} and {@code pull_requests: write},
 * which {@link GitHubCredentials} already requires on every token.
 *
 * <p>Duplicates the compact {@code HttpClient} plumbing in {@link GitHubGateway} and {@link
 * ReviewThreadGateway} rather than sharing it, following the call those classes record: the
 * gateways serve different concerns and are kept independently evolvable.
 */
@Component
public class AutoMergeGateway {

  private static final Logger LOGGER = LoggerFactory.getLogger(AutoMergeGateway.class);
  private static final String API_VERSION = "2026-03-10";
  private static final String NO_PERMISSION = "none";
  private static final int PAGE_SIZE = 100;

  /** GitHub serves at most 3000 files for a pull request; 30 pages of 100 reaches all of them. */
  private static final int MAX_PAGES = 30;

  /**
   * {@code expectedHeadOid} is what makes a slow or stale review harmless. If anything has been
   * pushed since the review read the head, GitHub refuses to arm, rather than arming a commit
   * nobody reviewed.
   */
  private static final String ENABLE_MUTATION =
      """
      mutation($pullRequestId: ID!, $expectedHeadOid: GitObjectID!) {
        enablePullRequestAutoMerge(input: {
            pullRequestId: $pullRequestId,
            mergeMethod: SQUASH,
            expectedHeadOid: $expectedHeadOid}) {
          pullRequest { autoMergeRequest { enabledAt } }
        }
      }
      """;

  private static final String DISABLE_MUTATION =
      """
      mutation($pullRequestId: ID!) {
        disablePullRequestAutoMerge(input: {pullRequestId: $pullRequestId}) {
          pullRequest { id }
        }
      }
      """;

  private final CodeReviewProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public AutoMergeGateway(
      final CodeReviewProperties properties,
      final GitHubCredentials credentials,
      final ObjectMapper objectMapper) {
    this.properties = properties;
    this.credentials = credentials;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /**
   * The pull request as GitHub reports it right now, with its author's permission on the
   * repository.
   */
  public AutoMergeState readState(final PullRequestContext pullRequest) {
    String token = token(pullRequest);
    JsonNode pull = getJson(pullRequestPath(pullRequest), token);
    String login = pull.path("user").path("login").asText("");
    return toState(pull, authorPermission(pullRequest, login, token));
  }

  /**
   * The author's permission from {@code /collaborators/{login}/permission}, the one read GitHub
   * answers the same way for every viewer.
   *
   * <p>Any failure reads as {@code none}, which can only withhold an arm. It is not thrown: the
   * same state read decides whether to <em>withdraw</em> an arm, and that must not fail a review
   * because a permission lookup did.
   */
  private String authorPermission(
      final PullRequestContext pullRequest, final String login, final String token) {
    if (login.isEmpty()) {
      return NO_PERMISSION;
    }
    try {
      String permission =
          getJson(
                  "/repos/"
                      + pullRequest.owner()
                      + "/"
                      + pullRequest.repository()
                      + "/collaborators/"
                      + URLEncoder.encode(login, StandardCharsets.UTF_8)
                      + "/permission",
                  token)
              .path("permission")
              .asText("");
      return permission.isEmpty() ? NO_PERMISSION : permission;
    } catch (IllegalStateException exception) {
      LOGGER.warn(
          "Could not read {}'s permission on {}; treating it as none",
          login,
          pullRequest.slug(),
          exception);
      return NO_PERMISSION;
    }
  }

  /**
   * Every path the pull request touches, including the old side of every rename.
   *
   * <p>Complete only if the listing reaches GitHub's own {@code changed_files} count. A listing
   * that stops short cannot prove the paths it did not return are safe.
   */
  public ChangedFiles listFiles(final PullRequestContext pullRequest, final int expectedCount) {
    String token = token(pullRequest);
    List<String> paths = new ArrayList<>();
    int listed = 0;
    for (int page = 1; page <= MAX_PAGES; page++) {
      JsonNode batch =
          getJson(
              pullRequestPath(pullRequest) + "/files?per_page=" + PAGE_SIZE + "&page=" + page,
              token);
      if (!batch.isArray()) {
        break;
      }
      for (JsonNode file : batch) {
        listed++;
        addIfPresent(paths, file.path("filename").asText(""));
        addIfPresent(paths, file.path("previous_filename").asText(""));
      }
      if (batch.size() < PAGE_SIZE) {
        break;
      }
    }
    return new ChangedFiles(paths, listed == expectedCount);
  }

  /** Arms squash auto-merge on exactly {@code headSha}. */
  public void enable(
      final PullRequestContext pullRequest, final String nodeId, final String headSha) {
    mutate(
        ENABLE_MUTATION,
        objectMapper
            .createObjectNode()
            .put("pullRequestId", nodeId)
            .put("expectedHeadOid", headSha),
        token(pullRequest));
  }

  /** Withdraws auto-merge, whoever armed it. The caller decides whose arm to withdraw. */
  public void disable(final PullRequestContext pullRequest, final String nodeId) {
    mutate(
        DISABLE_MUTATION,
        objectMapper.createObjectNode().put("pullRequestId", nodeId),
        token(pullRequest));
  }

  /**
   * Maps a REST pull request.
   *
   * <p>A fork's head is compared by repository full name, and a deleted fork, whose {@code
   * head.repo} is null, counts as cross-repository. A head nobody can name is not a head this
   * repository controls.
   *
   * <p>"Armed by a bot" reads {@code enabled_by.type}, not a configured login. A mistyped login
   * would silently stop the reviewer withdrawing its own arms, and that is the one failure here
   * that merges something unreviewed. Matching every bot fails the other way, which is safe: no
   * other bot arms auto-merge on this repository, and one that did would simply be re-decided.
   */
  static AutoMergeState toState(final JsonNode pullRequest, final String authorPermission) {
    String baseRepository = pullRequest.path("base").path("repo").path("full_name").asText("");
    String headRepository = pullRequest.path("head").path("repo").path("full_name").asText("");
    List<String> labels = new ArrayList<>();
    for (JsonNode label : pullRequest.path("labels")) {
      addIfPresent(labels, label.path("name").asText(""));
    }
    JsonNode autoMerge = pullRequest.path("auto_merge");
    boolean armed = autoMerge.isObject();
    return new AutoMergeState(
        requiredText(pullRequest, "node_id"),
        requiredText(pullRequest.path("head"), "sha"),
        pullRequest.path("draft").asBoolean(false),
        headRepository.isEmpty() || !headRepository.equalsIgnoreCase(baseRepository),
        authorPermission,
        labels,
        pullRequest.path("changed_files").asInt(-1),
        armed,
        armed && "Bot".equals(autoMerge.path("enabled_by").path("type").asText("")));
  }

  private static void addIfPresent(final List<String> values, final String value) {
    if (!value.isEmpty()) {
      values.add(value);
    }
  }

  private static String pullRequestPath(final PullRequestContext pullRequest) {
    return "/repos/"
        + pullRequest.owner()
        + "/"
        + pullRequest.repository()
        + "/pulls/"
        + pullRequest.pullNumber();
  }

  private String token(final PullRequestContext pullRequest) {
    return credentials.accessToken(pullRequest.installationId());
  }

  private void mutate(final String query, final ObjectNode variables, final String token) {
    ObjectNode payload =
        objectMapper.createObjectNode().put("query", query).set("variables", variables);
    JsonNode root = send("POST", "/graphql", payload, token);
    JsonNode errors = root.path("errors");
    if (errors.isArray() && !errors.isEmpty()) {
      throw new IllegalStateException(
          "GitHub GraphQL mutation was rejected: " + errors.path(0).path("message").asText(""));
    }
  }

  private JsonNode getJson(final String path, final String token) {
    return send("GET", path, null, token);
  }

  private JsonNode send(
      final String method, final String path, final JsonNode payload, final String token) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.github().apiBaseUrl() + path))
              .timeout(properties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer");
      if (token != null && !token.isBlank()) {
        request.header("Authorization", "Bearer " + token);
      }
      if (payload == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request
            .header("Content-Type", "application/json")
            .method(
                method,
                HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
      }
      HttpResponse<String> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitHub API returned " + response.statusCode() + " for " + method + " " + path);
      }
      return response.body().isBlank()
          ? objectMapper.createObjectNode()
          : objectMapper.readTree(response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub request interrupted", exception);
    } catch (IOException | JacksonException exception) {
      throw new IllegalStateException("GitHub request failed", exception);
    }
  }

  private static String requiredText(final JsonNode node, final String field) {
    String value = node.path(field).asText("");
    if (value.isBlank()) {
      throw new IllegalStateException("GitHub response omitted " + field);
    }
    return value;
  }
}
