package com.simonrowe.factory.staticanalysis.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the static-analysis module.
 *
 * <p>Defaults live in the record constructor, following {@code CveFixProperties}, so an absent
 * {@code factory.staticanalysis} block still yields a valid, disabled configuration.
 *
 * <p>There is no SonarCloud credential by default, deliberately: the project is public, and every
 * read this module makes ({@code project_analyses}, {@code issues}, {@code qualitygates},
 * {@code rules}) answers anonymously. {@code sonarToken} exists for the day the project is made
 * private; it is sent only when set.
 *
 * @param enabled registers the activity, the schedule and the run index; true on
 *     {@code software-factory} only
 * @param sonarBaseUrl SonarCloud's API root
 * @param sonarToken an optional SonarCloud user token, sent as a bearer token when non-blank
 * @param organization the SonarCloud organisation, needed by the rules lookup
 * @param projectKey the SonarCloud project key
 * @param branch the analysed branch; findings are read from this branch's latest analysis
 * @param githubApiBaseUrl GitHub's API root, used once per run to read the branch's head
 * @param owner the repository owner on GitHub
 * @param repository the repository name on GitHub
 * @param maxPerRun how many finding groups one run files; the rest are named to the absence sweep
 *     rather than lost
 * @param minimumSeverity the lowest Sonar severity filed for a code smell; bugs and
 *     vulnerabilities are filed at any severity
 * @param analysisGrace how long the branch head may go unanalysed before the analysis is
 *     treated as stale. The {@code main} analysis lands about twenty minutes after a merge, so
 *     anything much past that means the {@code Static Analysis} CI job did not run
 * @param resolveWhenClear whether a fresh analysis closes the tickets for findings it no longer
 *     reports
 * @param resolveAfter how long a finding must go unreported before its ticket is closed
 * @param requestTimeout per-HTTP-call timeout
 */
@ConfigurationProperties("factory.staticanalysis")
public record StaticAnalysisProperties(
    boolean enabled,
    String sonarBaseUrl,
    String sonarToken,
    String organization,
    String projectKey,
    String branch,
    String githubApiBaseUrl,
    String owner,
    String repository,
    int maxPerRun,
    String minimumSeverity,
    Duration analysisGrace,
    Boolean resolveWhenClear,
    Duration resolveAfter,
    Duration requestTimeout) {

  /**
   * Twelve hours, which is short for a sweep and is right for this source. Log watch waits seven
   * days because logs are intermittent and a quiet day proves little. A Sonar analysis is not: a
   * finding missing from a fresh, complete analysis of {@code main} has been fixed or accepted,
   * and every finding still present is refreshed by the same run before the sweep reads anything.
   * Under the daily interval so the next scheduled run closes what this one stopped seeing.
   */
  private static final Duration DEFAULT_RESOLVE_AFTER = Duration.ofHours(12);

  public StaticAnalysisProperties {
    sonarBaseUrl = sonarBaseUrl == null ? "https://sonarcloud.io" : sonarBaseUrl;
    sonarToken = sonarToken == null ? "" : sonarToken;
    organization = organization == null ? "simonjamesrowe" : organization;
    projectKey = projectKey == null ? "simonjamesrowe_simonrowe-dev-monorepo" : projectKey;
    branch = branch == null ? "main" : branch;
    githubApiBaseUrl = githubApiBaseUrl == null ? "https://api.github.com" : githubApiBaseUrl;
    owner = owner == null ? "simonjamesrowe" : owner;
    repository = repository == null ? "simonrowe-dev-monorepo" : repository;
    maxPerRun = maxPerRun <= 0 ? 5 : maxPerRun;
    minimumSeverity =
        minimumSeverity == null || minimumSeverity.isBlank() ? "MAJOR" : minimumSeverity;
    analysisGrace = analysisGrace == null ? Duration.ofHours(6) : analysisGrace;
    resolveWhenClear = resolveWhenClear == null || resolveWhenClear;
    resolveAfter = resolveAfter == null ? DEFAULT_RESOLVE_AFTER : resolveAfter;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
  }
}
