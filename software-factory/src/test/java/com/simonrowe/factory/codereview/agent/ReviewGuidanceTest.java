package com.simonrowe.factory.codereview.agent;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.config.ReviewGuidanceProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reviewer runs with --safe-mode and so loads no CLAUDE.md; this is the only way the written
 * standards, and the lessons the feedback loop writes into them, reach it.
 */
class ReviewGuidanceTest {

  private static final String CLAUDE_MD =
      """
      # Project

      ## Commands

      ./gradlew test

      ## Code Style

      - Java: Google style.
      - Keep null checks on third-party returns.

      ### Sub-rule

      Kept with its section.

      ## Recent Changes

      - A very long change log.
      """;

  private static final String GLOBAL_MD =
      """
      # simonjamesrowe

      ## Environment map

      - hosts

      ## Code quality

      ```markdown
      ## Not a heading, inside a fence
      ```

      - Clean up root causes.

      ## Installed skills

      - skills
      """;

  private static final String GLOBAL_PATH =
      "/repos/simonjamesrowe/agent-setup/contents/components/instructions/global.md";

  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/repos/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          requests.add(path + " " + exchange.getRequestHeaders().getFirst("Accept"));
          byte[] body = GLOBAL_MD.getBytes(StandardCharsets.UTF_8);
          int status = statuses.getOrDefault(path, 200);
          exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
          if (status == 200) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void takesOnlyTheNamedSectionsWithTheirSubSections() {
    String sections = ReviewGuidance.sections(CLAUDE_MD, List.of("code style"));

    assertThat(sections)
        .contains("## Code Style")
        .contains("Keep null checks")
        .contains("### Sub-rule")
        .doesNotContain("Commands")
        .doesNotContain("change log");
  }

  @Test
  void headingInsideFenceDoesNotStartOrEndSection() {
    String sections = ReviewGuidance.sections(GLOBAL_MD, List.of("Code quality"));

    assertThat(sections)
        .contains("## Not a heading, inside a fence")
        .contains("Clean up root causes.")
        .doesNotContain("skills");
  }

  @Test
  void capsAtLineBoundaryAndSaysSo() {
    String capped = ReviewGuidance.cap("first line\nsecond line\nthird line\n", 15);

    assertThat(capped).startsWith("first line\n[Truncated.]");
  }

  @Test
  void disabledGivesNoGuidanceAndReadsNothing() {
    String text =
        guidance(ReviewGuidanceProperties.disabled())
            .forReview(pullRequest("simonjamesrowe"), path -> CLAUDE_MD);

    assertThat(text).isEmpty();
    assertThat(requests).isEmpty();
  }

  @Test
  void readsTheRepositoryFileAndTheOrganisationFileForTheOrganisationsOwnRepositories() {
    List<String> read = new CopyOnWriteArrayList<>();
    String text =
        guidance(enabled())
            .forReview(
                pullRequest("simonjamesrowe"),
                path -> {
                  read.add(path);
                  return CLAUDE_MD;
                });

    assertThat(read).containsExactly("CLAUDE.md");
    assertThat(text)
        .contains("### From CLAUDE.md at the base commit of simonjamesrowe/project")
        .contains("Keep null checks")
        .contains("### From components/instructions/global.md in simonjamesrowe/agent-setup")
        .contains("Clean up root causes.");
    assertThat(requests).containsExactly(GLOBAL_PATH + " application/vnd.github.raw+json");
  }

  @Test
  void anotherOwnersPullRequestGetsOnlyItsOwnRepositoryFile() {
    String text = guidance(enabled()).forReview(pullRequest("someone-else"), path -> CLAUDE_MD);

    assertThat(text).contains("Keep null checks").doesNotContain("Clean up root causes.");
    assertThat(requests).isEmpty();
  }

  /** Guidance is best-effort: losing it costs the review its standards, never the review. */
  @Test
  void unreadableSourcesGiveNoGuidanceRatherThanFailing() {
    statuses.put(GLOBAL_PATH, 404);

    String text = guidance(enabled()).forReview(pullRequest("simonjamesrowe"), path -> null);

    assertThat(text).isEmpty();
  }

  private ReviewGuidance guidance(final ReviewGuidanceProperties guidance) {
    CodeReviewProperties properties = properties();
    return new ReviewGuidance(
        guidance, properties, new GitHubCredentials(properties, new ObjectMapper()));
  }

  private static ReviewGuidanceProperties enabled() {
    return new ReviewGuidanceProperties(
        true,
        "CLAUDE.md",
        List.of("Code Style"),
        "simonjamesrowe/agent-setup",
        "components/instructions/global.md",
        List.of("Code quality"),
        12_000);
  }

  private static PullRequestContext pullRequest(final String owner) {
    return new PullRequestContext(
        owner, "project", 7, "Title", "Body", "https://github.com/" + owner + "/project.git",
        "c".repeat(40), "d".repeat(40), null);
  }

  private CodeReviewProperties properties() {
    return new CodeReviewProperties(
        new CodeReviewProperties.Github(
            "http://localhost:" + server.getAddress().getPort(),
            "test-token",
            "",
            "",
            "",
            Duration.ofSeconds(30)),
        new CodeReviewProperties.Agent(
            "claude", "sonnet", "medium", 12, Duration.ofMinutes(15), Path.of("/tmp"), 2_097_152,
            80, "v1"),
        new CodeReviewProperties.Api("", null),
        "https://temporal.test");
  }
}
