package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitHubGatewayTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void usesBaseRepositoryCloneUrlBecauseItOwnsThePullRequestRef() throws Exception {
    JsonNode payload =
        objectMapper.readTree(
            """
            {
              "title": "Review me",
              "body": "Description",
              "head": {
                "sha": "head-sha",
                "repo": {"clone_url": "https://github.com/contributor/fork.git"}
              },
              "base": {
                "sha": "base-sha",
                "repo": {"clone_url": "https://github.com/example/project.git"}
              }
            }
            """);

    PullRequestContext result =
        GitHubGateway.toPullRequestContext(
            new ReviewRequest("example", "project", 42, "head-sha", 123L, false), payload);

    assertThat(result.cloneUrl()).isEqualTo("https://github.com/example/project.git");
    assertThat(result.baseSha()).isEqualTo("base-sha");
    assertThat(result.headSha()).isEqualTo("head-sha");
    assertThat(result.installationId()).isEqualTo(123L);
  }

  @Test
  void buildsPullRequestReviewPayloadWithInlineComments() {
    GitHubGateway gateway = gateway();
    PullRequestContext pullRequest =
        new PullRequestContext(
            "example", "project", 42, "Title", "Body",
            "https://github.com/example/project.git", "base-sha", "head-sha", 123L);
    ReviewReport report =
        new ReviewReport(
            "Summary.",
            Verdict.COMMENT,
            List.of(
                new ReviewFinding(
                    Severity.WARNING, "src/App.java", 12, "Bad", "Because.", "Fix it.")));

    JsonNode payload = gateway.reviewPayload(pullRequest, report, "<!-- marker -->");

    assertThat(payload.path("commit_id").asText()).isEqualTo("head-sha");
    assertThat(payload.path("event").asText()).isEqualTo("COMMENT");
    assertThat(payload.path("body").asText()).contains("<!-- marker -->");
    assertThat(payload.path("comments")).hasSize(1);
    JsonNode comment = payload.path("comments").get(0);
    assertThat(comment.path("path").asText()).isEqualTo("src/App.java");
    assertThat(comment.path("line").asInt()).isEqualTo(12);
    assertThat(comment.path("side").asText()).isEqualTo("RIGHT");
    assertThat(comment.path("body").asText()).contains("Bad");
  }

  @Test
  void fallbackPayloadFoldsFindingsIntoTheBodyWithNoInlineComments() {
    GitHubGateway gateway = gateway();
    PullRequestContext pullRequest =
        new PullRequestContext(
            "example", "project", 42, "Title", "Body",
            "https://github.com/example/project.git", "base-sha", "head-sha", 123L);
    ReviewReport report =
        new ReviewReport(
            "Summary.",
            Verdict.COMMENT,
            List.of(
                new ReviewFinding(
                    Severity.WARNING, "src/App.java", 12, "Bad", "Because.", "Fix it.")));

    JsonNode payload = gateway.fallbackReviewPayload(pullRequest, report, "<!-- marker -->");

    assertThat(payload.has("comments")).isFalse();
    assertThat(payload.path("body").asText()).contains("`src/App.java:12`");
  }

  private GitHubGateway gateway() {
    CodeReviewProperties properties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "", "", "", java.time.Duration.ofSeconds(30)),
            new CodeReviewProperties.Agent(
                "claude", "sonnet", "medium", 12, java.time.Duration.ofMinutes(15),
                java.nio.file.Path.of("/tmp"), 2097152, 80, "v1"),
            new CodeReviewProperties.Api("token"));
    return new GitHubGateway(
        properties,
        new GitHubCredentials(properties, objectMapper),
        objectMapper,
        new ReviewMarkdownRenderer());
  }
}
