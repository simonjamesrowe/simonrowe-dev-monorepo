package com.simonrowe.factory.linear.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the Linear issue sink.
 *
 * <p>Defaults live in the record constructor, following {@link
 * com.simonrowe.factory.cvefix.config.CveFixProperties}, so an absent {@code factory.linear}
 * block still yields a valid, disabled configuration.
 */
@ConfigurationProperties("factory.linear")
public record LinearProperties(
    boolean enabled,
    String apiKey,
    String apiBaseUrl,
    String teamKey,
    String fingerprintBaseUrl,
    boolean dryRun,
    Duration requestTimeout,
    Map<String, Producer> producers) {

  private static final int NORMAL_PRIORITY = 3;

  /**
   * Per-HTTP-call timeout, and it is arithmetic rather than taste.
   *
   * <p>The worst case of one {@code IssueFiler.file} on a cold team cache is <strong>four
   * sequential Linear calls</strong>: the fingerprint lookup, the team/label/triage resolution,
   * {@code issueCreate} and {@code attachmentCreate}. Both producers give the {@code fileIssue}
   * activity a 90-second {@code startToCloseTimeout}, and Temporal does not interrupt a
   * non-heartbeating activity — it simply starts attempt 2 while attempt 1 runs on. Attempt 2
   * then sees an empty lookup and files a <strong>second ticket</strong>. At 15s the worst case
   * is 60s, comfortably inside 90s; at the original 30s it was 120s, and that duplicate was
   * reachable. <strong>Do not raise this without raising the activity timeout in
   * {@code DeployWorkflowImpl} and {@code CveFixWorkflowImpl} to match.</strong>
   */
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

  public LinearProperties {
    apiKey = apiKey == null ? "" : apiKey;
    apiBaseUrl = apiBaseUrl == null ? "https://api.linear.app/graphql" : apiBaseUrl;
    teamKey = teamKey == null ? "" : teamKey;
    fingerprintBaseUrl =
        fingerprintBaseUrl == null
            ? "https://factory.simonrowe.dev/fingerprint"
            : fingerprintBaseUrl;
    requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    Map<String, Producer> merged = new HashMap<>();
    merged.put("deploy", new Producer("factory:deploy", 1));
    merged.put("cvefix", new Producer("factory:cvefix", NORMAL_PRIORITY));
    if (producers != null) {
      merged.putAll(producers);
    }
    producers = Map.copyOf(merged);
  }

  /**
   * The filing policy for a producer.
   *
   * <p>An unknown producer gets a derived label rather than an exception: a producer shipping
   * ahead of its configuration entry must still file, because losing the finding is worse than
   * mislabelling it.
   *
   * @param producerKey the producer's key, e.g. {@code deploy}
   * @return the configured policy, or a derived default
   */
  public Producer producerFor(final String producerKey) {
    Producer configured = producers.get(producerKey);
    return configured == null
        ? new Producer("factory:" + producerKey, NORMAL_PRIORITY)
        : configured;
  }

  /**
   * Per-producer filing policy. This is the seam for treating issue types differently — a
   * different target state or priority for CVE tickets, say — without a code change.
   *
   * @param label the Linear label applied to every issue this producer files
   * @param priority the Linear priority integer; see
   *     specs/039-linear-issue-sink/research.md
   */
  public record Producer(String label, int priority) {

    public Producer {
      label = label == null ? "factory" : label;
      priority = priority == 0 ? NORMAL_PRIORITY : priority;
    }
  }
}
