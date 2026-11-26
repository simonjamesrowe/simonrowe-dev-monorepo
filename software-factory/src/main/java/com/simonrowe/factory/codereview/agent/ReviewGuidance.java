package com.simonrowe.factory.codereview.agent;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.config.ReviewGuidanceProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Assembles the written standards the reviewer is given, from sources a pull request cannot edit.
 *
 * <p>Brunel gives its reviewers a repository's {@code AGENTS.md} and {@code CLAUDE.md}, capped at
 * 4 KB each. This reviewer cannot simply let Claude Code load {@code CLAUDE.md}: it runs with
 * {@code --safe-mode} so the pull request's own copy cannot steer it, and this repository's file
 * is mostly a change log hundreds of kilobytes long. So it takes named sections instead, from the
 * base commit and from the organisation's instructions, and states where each came from.
 *
 * <p>Every read is best-effort. A missing file or an unreachable repository costs the review its
 * guidance, logged, never the review itself.
 */
@Component
public class ReviewGuidance {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReviewGuidance.class);

  private static final String API_VERSION = "2026-03-10";

  private static final String TRUNCATED = "\n[Truncated.]\n";

  private final ReviewGuidanceProperties guidance;
  private final CodeReviewProperties properties;
  private final GitHubCredentials credentials;
  private final HttpClient httpClient;

  public ReviewGuidance(
      final ReviewGuidanceProperties guidance,
      final CodeReviewProperties properties,
      final GitHubCredentials credentials) {
    this.guidance = guidance;
    this.properties = properties;
    this.credentials = credentials;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /**
   * The guidance for one review, or an empty string when there is none.
   *
   * @param readAtBase reads a repository file at the pull request's base commit, null if absent
   */
  public String forReview(
      final PullRequestContext pullRequest, final Function<String, String> readAtBase) {
    if (!guidance.enabled()) {
      return "";
    }
    List<String> blocks = new ArrayList<>();
    if (hasText(guidance.repositoryFile())) {
      String excerpt =
          excerpt(readAtBase.apply(guidance.repositoryFile()), guidance.repositorySections());
      if (!excerpt.isEmpty()) {
        blocks.add(
            block(
                "%s at the base commit of %s/%s"
                    .formatted(
                        guidance.repositoryFile(), pullRequest.owner(), pullRequest.repository()),
                excerpt));
      }
    }
    if (appliesToOrganisation(pullRequest)) {
      String excerpt =
          excerpt(fetchOrganisationFile(pullRequest), guidance.organisationSections());
      if (!excerpt.isEmpty()) {
        blocks.add(
            block(
                "%s in %s"
                    .formatted(guidance.organisationFile(), guidance.organisationRepository()),
                excerpt));
      }
    }
    return String.join("\n", blocks);
  }

  private String excerpt(final String markdown, final List<String> headings) {
    return cap(sections(markdown, headings), guidance.maxChars());
  }

  private static String block(final String source, final String text) {
    return "### From %s\n\n%s\n".formatted(source, text.strip());
  }

  private boolean appliesToOrganisation(final PullRequestContext pullRequest) {
    String repository = guidance.organisationRepository();
    if (!hasText(repository) || !hasText(guidance.organisationFile())) {
      return false;
    }
    int slash = repository.indexOf('/');
    return slash > 0
        && repository.substring(0, slash).equalsIgnoreCase(pullRequest.owner());
  }

  /**
   * The level-two sections of {@code markdown} whose headings are in {@code headings}, in document
   * order, each with its own sub-sections.
   *
   * <p>A heading inside a fenced code block is not a heading, so fences are tracked. Plain string
   * work, no regex, because the input is a whole file.
   */
  static String sections(final String markdown, final List<String> headings) {
    if (markdown == null || markdown.isBlank() || headings.isEmpty()) {
      return "";
    }
    List<String> wanted =
        headings.stream().map(heading -> heading.strip().toLowerCase(Locale.ROOT)).toList();
    StringBuilder out = new StringBuilder();
    boolean inFence = false;
    boolean including = false;
    for (String line : markdown.lines().toList()) {
      String stripped = line.strip();
      if (stripped.startsWith("```")) {
        inFence = !inFence;
      } else if (!inFence && line.startsWith("## ")) {
        including = wanted.contains(line.substring(3).strip().toLowerCase(Locale.ROOT));
      } else if (!inFence && line.startsWith("# ")) {
        including = false;
      }
      if (including) {
        out.append(line).append('\n');
      }
    }
    return out.toString();
  }

  /** At most {@code maxChars} characters, cut at a line boundary and marked as cut. */
  static String cap(final String text, final int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    int cut = text.lastIndexOf('\n', maxChars);
    return text.substring(0, cut > 0 ? cut : maxChars) + TRUNCATED;
  }

  private String fetchOrganisationFile(final PullRequestContext pullRequest) {
    String path =
        "/repos/%s/contents/%s"
            .formatted(guidance.organisationRepository(), guidance.organisationFile());
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.github().apiBaseUrl() + path))
              .timeout(properties.github().requestTimeout())
              .header("Accept", "application/vnd.github.raw+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer")
              .GET();
      // The comment token: no permissions override, so it can never be refused for asking for
      // more than the installation holds, and reading contents is within any grant.
      String token = credentials.commentToken(pullRequest.installationId());
      if (token != null && !token.isBlank()) {
        request.header("Authorization", "Bearer " + token);
      }
      HttpResponse<String> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        LOGGER.warn(
            "Review guidance {} returned {}; reviewing {} without it",
            path,
            response.statusCode(),
            pullRequest.slug());
        return null;
      }
      return response.body();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return null;
    } catch (IOException | RuntimeException exception) {
      LOGGER.warn(
          "Could not read review guidance {}; reviewing {} without it",
          path,
          pullRequest.slug(),
          exception);
      return null;
    }
  }

  private static boolean hasText(final String value) {
    return value != null && !value.isBlank();
  }
}
