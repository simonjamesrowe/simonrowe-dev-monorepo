package com.simonrowe.factory.feedback.github;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.feedback.domain.ConversationComment;
import com.simonrowe.factory.feedback.domain.ConversationReview;
import com.simonrowe.factory.feedback.domain.ConversationThread;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.ReviewConversation;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Fetches a closed pull request's full review conversation from GitHub's GraphQL API in one round
 * trip: reviews, review threads with resolved state, and issue comments.
 *
 * <p>Deliberately duplicates the compact {@code HttpClient} plumbing in {@code GitHubGateway}
 * rather than extending it; the two gateways serve different modules (code review vs. feedback)
 * and this task keeps them independently evolvable.
 */
@Component
public class ConversationGateway {

  private static final String API_VERSION = "2026-03-10";

  private static final String CONVERSATION_QUERY =
      """
      query($owner: String!, $name: String!, $number: Int!) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            title url merged
            author { login __typename }
            reviews(first: 50) { nodes { author { login __typename } state body url } }
            reviewThreads(first: 100) {
              nodes {
                isResolved
                comments(first: 50) {
                  nodes { author { login __typename } body path line diffHunk url }
                }
              }
            }
            comments(first: 100) { nodes { author { login __typename } body url } }
          }
        }
      }
      """;

  private final CodeReviewProperties properties;
  private final GitHubCredentials credentials;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public ConversationGateway(
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

  /** Fetches the full review conversation for a closed PR in one GraphQL round trip. */
  public ReviewConversation fetchConversation(final FeedbackRequest request) {
    ObjectNode variables =
        objectMapper
            .createObjectNode()
            .put("owner", request.owner())
            .put("name", request.repository())
            .put("number", request.pullNumber());
    ObjectNode payload =
        objectMapper
            .createObjectNode()
            .put("query", CONVERSATION_QUERY)
            .set("variables", variables);

    JsonNode root = postJson(payload, credentials.accessToken(request.installationId()));

    JsonNode errors = root.path("errors");
    JsonNode pullRequest = root.path("data").path("repository").path("pullRequest");
    boolean hasErrors = errors.isArray() && !errors.isEmpty();
    if (hasErrors || pullRequest.isMissingNode() || pullRequest.isNull()) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Pull request not found or query rejected", "PULL_REQUEST_NOT_FOUND");
    }
    return toConversation(pullRequest);
  }

  static ReviewConversation toConversation(final JsonNode pullRequest) {
    List<ConversationReview> reviews = new ArrayList<>();
    for (JsonNode node : pullRequest.path("reviews").path("nodes")) {
      reviews.add(
          new ConversationReview(
              authorLogin(node), isBot(node),
              node.path("state").asText(""), node.path("body").asText(""),
              node.path("url").asText("")));
    }
    List<ConversationThread> threads = new ArrayList<>();
    for (JsonNode threadNode : pullRequest.path("reviewThreads").path("nodes")) {
      List<ConversationComment> comments = new ArrayList<>();
      for (JsonNode commentNode : threadNode.path("comments").path("nodes")) {
        comments.add(toComment(commentNode));
      }
      boolean resolved = threadNode.path("isResolved").asBoolean(false);
      threads.add(new ConversationThread(resolved, comments));
    }
    List<ConversationComment> issueComments = new ArrayList<>();
    for (JsonNode commentNode : pullRequest.path("comments").path("nodes")) {
      issueComments.add(toComment(commentNode));
    }
    return new ReviewConversation(
        pullRequest.path("title").asText(""),
        pullRequest.path("url").asText(""),
        authorLogin(pullRequest),
        pullRequest.path("merged").asBoolean(false),
        reviews, threads, issueComments);
  }

  private static ConversationComment toComment(final JsonNode node) {
    return new ConversationComment(
        authorLogin(node), isBot(node), node.path("body").asText(""),
        node.path("path").isMissingNode() || node.path("path").isNull()
            ? null : node.path("path").asText(),
        node.path("line").isNumber() ? node.path("line").asInt() : null,
        node.path("diffHunk").isMissingNode() || node.path("diffHunk").isNull()
            ? null : node.path("diffHunk").asText(),
        node.path("url").asText(""));
  }

  private static String authorLogin(final JsonNode node) {
    JsonNode author = node.path("author");
    if (author.isNull() || author.isMissingNode()) {
      return "ghost";
    }
    return author.path("login").asText("ghost");
  }

  private static boolean isBot(final JsonNode node) {
    return "Bot".equals(node.path("author").path("__typename").asText(""));
  }

  private JsonNode postJson(final ObjectNode payload, final String accessToken) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.github().apiBaseUrl() + "/graphql"))
              .timeout(properties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer")
              .header("Content-Type", "application/json");
      if (accessToken != null && !accessToken.isBlank()) {
        request.header("Authorization", "Bearer " + accessToken);
      }
      request.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

      HttpResponse<String> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitHub GraphQL API returned " + response.statusCode() + " for POST /graphql");
      }
      return response.body().isBlank()
          ? objectMapper.createObjectNode()
          : objectMapper.readTree(response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub GraphQL request interrupted", exception);
    } catch (IOException | JacksonException exception) {
      throw new IllegalStateException("GitHub GraphQL request failed", exception);
    }
  }
}
