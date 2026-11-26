package com.simonrowe.dashboard;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the admin dashboard ({@code /admin/dashboard}).
 *
 * @param uptime the in-stack uptime prober
 * @param dependencyTrack where vulnerability counts are read from
 * @param alerts where the off-Pi heartbeat files its outage issues
 */
@ConfigurationProperties("dashboard")
public record DashboardProperties(
    Uptime uptime,
    DependencyTrack dependencyTrack,
    Alerts alerts
) {

  /**
   * Fills every missing block with its defaults, so a partial configuration still binds.
   *
   * @param uptime the in-stack uptime prober
   * @param dependencyTrack where vulnerability counts are read from
   * @param alerts where the off-Pi heartbeat files its outage issues
   */
  public DashboardProperties {
    uptime = uptime == null ? new Uptime(false, null, null) : uptime;
    dependencyTrack = dependencyTrack == null
        ? new DependencyTrack(null, null, null) : dependencyTrack;
    alerts = alerts == null ? new Alerts(null, null, null, null) : alerts;
  }

  /**
   * The prober that requests every public hostname once a minute.
   *
   * @param enabled whether it probes at all; off everywhere but production, so a laptop's
   *     backend does not add its own traffic to the production nginx logs
   * @param timeout how long one probe may take before it counts as a failure
   * @param targets what to probe
   */
  public record Uptime(boolean enabled, Duration timeout, List<Target> targets) {

    /**
     * Applies defaults.
     *
     * @param enabled whether it probes at all
     * @param timeout how long one probe may take
     * @param targets what to probe
     */
    public Uptime {
      timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
      targets = targets == null ? List.of() : List.copyOf(targets);
    }
  }

  /**
   * One hostname to probe. A probe passes on a {@code 200} and nothing else: redirects are not
   * followed, matching {@code scripts/prod-heartbeat.sh}.
   *
   * @param name the label shown on the dashboard, and the key the results are stored under
   * @param url the address to request
   */
  public record Target(String name, String url) {
  }

  /**
   * The Dependency-Track API.
   *
   * @param baseUrl the API server's origin, without {@code /api}
   * @param apiKey the key software-factory's CVE scan already uses; blank means not configured
   * @param timeout per-request timeout
   */
  public record DependencyTrack(String baseUrl, String apiKey, Duration timeout) {

    /**
     * Applies defaults.
     *
     * @param baseUrl the API server's origin
     * @param apiKey the API key
     * @param timeout per-request timeout
     */
    public DependencyTrack {
      baseUrl = baseUrl == null || baseUrl.isBlank()
          ? "https://dependency-track.simonrowe.dev" : stripTrailingSlash(baseUrl.trim());
      apiKey = apiKey == null ? "" : apiKey.trim();
      timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
    }

    /**
     * Whether there is a key to send.
     *
     * @return true when a key is set
     */
    public boolean configured() {
      return !apiKey.isEmpty();
    }
  }

  /**
   * The GitHub repository whose {@code prod-down} issues are the incident list.
   *
   * @param apiBaseUrl the GitHub API base URL
   * @param repository {@code owner/name}
   * @param label the label the heartbeat puts on its outage issues
   * @param cacheFor how long a fetched list is reused before GitHub is asked again
   */
  public record Alerts(String apiBaseUrl, String repository, String label, Duration cacheFor) {

    /**
     * Applies defaults.
     *
     * @param apiBaseUrl the GitHub API base URL
     * @param repository {@code owner/name}
     * @param label the outage label
     * @param cacheFor how long a fetched list is reused
     */
    public Alerts {
      apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank()
          ? "https://api.github.com" : stripTrailingSlash(apiBaseUrl.trim());
      repository = repository == null || repository.isBlank()
          ? "simonjamesrowe/simonrowe-dev-monorepo" : repository.trim();
      label = label == null || label.isBlank() ? "prod-down" : label.trim();
      cacheFor = cacheFor == null ? Duration.ofMinutes(5) : cacheFor;
    }
  }

  private static String stripTrailingSlash(final String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
