package com.simonrowe.factory.staticanalysis.source;

import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth;
import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth.Status;
import com.simonrowe.factory.staticanalysis.source.BranchHeadClient.Head;
import com.simonrowe.factory.staticanalysis.source.SonarCloudClient.Analysis;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides whether SonarCloud's latest analysis of the branch can be believed.
 *
 * <p>Fresh means one of two things: the analysis is of the branch head, or the head is younger
 * than {@code analysisGrace}, in which case its analysis is most likely still running in CI and
 * the previous one is the best available statement of the code. Anything else is stale, and a
 * stale analysis is reported rather than read: see {@link AnalysisHealth} for why.
 *
 * <p>When GitHub cannot be read the analysis is still used. Freshness then cannot be confirmed
 * and the evidence says so, but the findings are a true statement about the revision they were
 * read from, and refusing to file them because a second, unrelated service blinked would make
 * GitHub's availability a dependency of this module's output.
 */
@Component
public class AnalysisFreshnessChecker {

  private final SonarCloudClient sonar;
  private final BranchHeadClient github;
  private final StaticAnalysisProperties properties;
  private final Clock clock;

  /**
   * Creates the checker on the system clock.
   *
   * @param sonar reads the latest analysis
   * @param github reads the branch head
   * @param properties the module's configuration
   */
  @Autowired
  public AnalysisFreshnessChecker(
      final SonarCloudClient sonar,
      final BranchHeadClient github,
      final StaticAnalysisProperties properties) {
    this(sonar, github, properties, Clock.systemUTC());
  }

  AnalysisFreshnessChecker(
      final SonarCloudClient sonar,
      final BranchHeadClient github,
      final StaticAnalysisProperties properties,
      final Clock clock) {
    this.sonar = sonar;
    this.github = github;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Reads both sources and judges the analysis.
   *
   * @return the verdict, never null
   */
  public AnalysisHealth check() {
    Optional<Analysis> analysis;
    try {
      analysis = sonar.latestAnalysis();
    } catch (IllegalStateException unreachable) {
      return new AnalysisHealth(Status.UNREACHABLE,
          "SonarCloud could not be read: " + unreachable.getMessage(), null, null, null);
    }
    Head head;
    String headFailure = null;
    try {
      head = github.head();
    } catch (IllegalStateException unreadable) {
      head = null;
      headFailure = unreadable.getMessage();
    }
    return judge(analysis.orElse(null), head, headFailure, clock.instant(),
        properties.analysisGrace(), properties.branch());
  }

  /**
   * The verdict, as a pure function of what was read.
   *
   * @param analysis the latest analysis, or null when there is none
   * @param head the branch head, or null when GitHub could not be read
   * @param headFailure why GitHub could not be read, when it could not
   * @param now the current time
   * @param grace how long the head may go unanalysed
   * @param branch the branch name, for the evidence sentence
   * @return the verdict
   */
  static AnalysisHealth judge(
      final Analysis analysis,
      final Head head,
      final String headFailure,
      final Instant now,
      final Duration grace,
      final String branch) {
    if (analysis == null || analysis.revision().isBlank()) {
      return new AnalysisHealth(Status.NO_ANALYSIS,
          """
          SonarCloud holds no analysis of %s. The Static Analysis CI job runs on every push to \
          %s; check that it ran and that its analysis step was not skipped for want of \
          SONAR_TOKEN."""
              .formatted(branch, branch),
          null, null, head == null ? null : head.sha());
    }
    if (head == null) {
      return new AnalysisHealth(Status.FRESH,
          "Read the analysis of %s from %s; could not confirm it is current (%s)."
              .formatted(shortSha(analysis.revision()), analysis.date(), headFailure),
          analysis.revision(), analysis.date(), null);
    }
    if (head.sha().equals(analysis.revision())) {
      return new AnalysisHealth(Status.FRESH,
          "The analysis from %s is of %s's head, %s."
              .formatted(analysis.date(), branch, shortSha(head.sha())),
          analysis.revision(), analysis.date(), head.sha());
    }
    Duration unanalysed = Duration.between(head.committedAt(), now);
    if (unanalysed.compareTo(grace) <= 0) {
      return new AnalysisHealth(Status.FRESH,
          """
          %s's head %s is %s old and not analysed yet, so this reads the previous analysis, of \
          %s."""
              .formatted(branch, shortSha(head.sha()), describe(unanalysed),
                  shortSha(analysis.revision())),
          analysis.revision(), analysis.date(), head.sha());
    }
    return new AnalysisHealth(Status.STALE,
        """
        %s's head %s has gone %s without an analysis; the latest is of %s, from %s. The Static \
        Analysis CI job is skipped whenever a build job fails on %s, so the CI run for %s is the \
        first place to look."""
            .formatted(branch, shortSha(head.sha()), describe(unanalysed),
                shortSha(analysis.revision()), analysis.date(), branch, shortSha(head.sha())),
        analysis.revision(), analysis.date(), head.sha());
  }

  private static String shortSha(final String sha) {
    return sha.length() > 8 ? sha.substring(0, 8) : sha;
  }

  private static String describe(final Duration duration) {
    long hours = duration.toHours();
    return hours >= 1 ? hours + "h" : Math.max(0, duration.toMinutes()) + "m";
  }
}
