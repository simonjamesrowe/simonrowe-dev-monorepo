package com.simonrowe.factory.logwatch.config;

import com.simonrowe.factory.logwatch.domain.LogSignature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the scheduled log-watch scan.
 *
 * <p>Every flag defaults off, so merging this module cannot change production until an operator
 * opts in (Constitution II).
 *
 * @param enabled registers the activities, and so the Loki credential, in this container only
 * @param minimumOccurrences the floor below which a signature is not worth filing
 * @param maxPerRun how many signatures may be filed in one run
 * @param defaultWindow the window scanned when a trigger names none
 * @param lineBudget the most lines one scan will read
 * @param minimumContainers the coverage floor used when Alloy's component API is unreachable
 * @param loki where to read logs from
 * @param alloy where to ask whether the write path is healthy
 * @param resolveWhenClear whether a clean scan closes the tickets this module filed for problems
 *     it no longer sees
 * @param resolveAfter how long a problem must go unreported before its ticket is closed
 * @param ignore third-party log noise this module must not file tickets for
 */
@ConfigurationProperties("factory.logwatch")
public record LogWatchProperties(
    boolean enabled,
    int minimumOccurrences,
    int maxPerRun,
    Duration defaultWindow,
    int lineBudget,
    int minimumContainers,
    Loki loki,
    Alloy alloy,
    // Both last in the record on purpose: appended rather than inserted, so adding them did not
    // force an edit into the middle of every positional call site in the tests.
    //
    // Boxed, unlike `enabled`, because this one defaults ON and a primitive boolean cannot
    // express that - its zero value is false, so an operator who enables the module and
    // configures nothing else would get filing without resolving, which is the half-automation
    // this exists to remove. Boxing lets "absent" and "explicitly false" be told apart.
    Boolean resolveWhenClear,
    Duration resolveAfter,
    // Last, for the same reason as the two above: appended rather than inserted.
    List<Ignore> ignore) {

  /**
   * How long a problem must go unreported before the sweep closes its ticket.
   *
   * <p>Seven days, which is seven consecutive nightly scans, and the number is a trade between
   * two asymmetric costs. Too short and a genuinely intermittent problem — one that fires on a
   * weekly cron, or only when a particular job runs — gets closed and re-filed on a cycle, which
   * is the duplicate-ticket disease 046 was written to cure. Too long and a fixed problem's
   * ticket loiters in Triage, which is the manual cleanup this feature exists to remove.
   *
   * <p>Seven leans short because being early is cheap and recoverable: the fingerprint attachment
   * outlives the closure, so a recurrence files a linked regression rather than vanishing. Being
   * late is merely tedious, but it is tedious every single day.
   */
  private static final Duration DEFAULT_RESOLVE_AFTER = Duration.ofDays(7);

  public LogWatchProperties {
    // Two, not one: a single occurrence of anything is noise at this scale. Provisional - the
    // spec's open questions flag this and maxPerRun as estimates until real production log
    // volumes can be sampled, which needs Loki to have been ingesting for a while.
    minimumOccurrences = minimumOccurrences <= 0 ? 2 : minimumOccurrences;
    maxPerRun = maxPerRun <= 0 ? 5 : maxPerRun;
    defaultWindow = defaultWindow == null ? Duration.ofHours(24) : defaultWindow;
    lineBudget = lineBudget <= 0 ? 5000 : lineBudget;
    minimumContainers = minimumContainers <= 0 ? 3 : minimumContainers;
    loki = loki == null ? Loki.defaults() : loki;
    alloy = alloy == null ? Alloy.defaults() : alloy;
    resolveWhenClear = resolveWhenClear == null || resolveWhenClear;
    resolveAfter = resolveAfter == null ? DEFAULT_RESOLVE_AFTER : resolveAfter;
    // Unusable rules are dropped here rather than at match time, so `ignore` is a list of rules
    // that can actually mute something and `mutedBy` never has to re-check. A rule with no
    // `contains` would otherwise match every group in its container.
    ignore = ignore == null ? List.of() : ignore.stream().filter(Ignore::usable).toList();
  }

  /**
   * The rules that between them disown every distinct message in this group.
   *
   * <p>Muting is decided across the whole rule list rather than one rule at a time, because a
   * group is keyed on the emitting code and one logger emits more than one kind of somebody
   * else's noise. Temporal's {@code Operation failed with internal error.} is the case that
   * forced it: five distinct messages, three saying {@code context canceled} and two reporting
   * the same cancellation from the SQL driver's side. No single rule could ever cover all five,
   * so SIM-28 was re-filed every night by a mechanism that had already been told twice that its
   * contents were not ours.
   *
   * <p>The safety property is unchanged and is the whole point: <strong>every</strong> variant
   * must be disowned by <em>some</em> curated rule. One message no rule claims, and the group is
   * filed in full — so a real fault appearing under a muted logger un-mutes it on the next scan
   * rather than riding out of sight beside the noise.
   *
   * @param signature the grouped problem
   * @return the rules that mute it, in declaration order, or empty when the group should be filed
   */
  public List<Ignore> mutedBy(final LogSignature signature) {
    if (signature == null || ignore.isEmpty()) {
      return List.of();
    }
    List<Ignore> candidates = ignore.stream().filter(rule -> rule.appliesTo(signature)).toList();
    if (candidates.isEmpty()) {
      return List.of();
    }
    // MAX_VARIANTS limits what is listed, not what was seen. Beyond it no rule can vouch for the
    // group, however well the visible messages match. Same distinction, and the same reason, as
    // the per-run cap's veto over the absence sweep.
    if (signature.distinctVariants() > signature.variants().size()) {
      return List.of();
    }
    if (signature.variants().isEmpty()) {
      // A LogSignature replayed from a pre-ignore Temporal history carries no variants. Fall
      // back to the leader rather than refusing to mute, which would re-file the very noise the
      // operator has already disowned.
      return candidates.stream()
          .filter(rule -> rule.matchesLeaderOf(signature))
          .findFirst()
          .map(List::of)
          .orElseGet(List::of);
    }
    List<Ignore> used = new ArrayList<>();
    for (LogSignature.Variant variant : signature.variants()) {
      Ignore match =
          candidates.stream().filter(rule -> rule.matches(variant)).findFirst().orElse(null);
      if (match == null) {
        return List.of();
      }
      if (!used.contains(match)) {
        used.add(match);
      }
    }
    return List.copyOf(used);
  }

  /**
   * One class of third-party log noise this module must not file a ticket for.
   *
   * <p>Every other filter in this module is a property of the <em>volume</em> of a problem —
   * how often it occurred, how many made the cap. This one is a statement about a problem's
   * <em>owner</em>, and it exists because the alternative is worse. Temporal logs its own
   * shutdown and cancel churn at ERROR, Alloy logs an ERROR each time it tails a container that
   * is being removed, and Dependency-Track logs a WARN per malformed OSV version range it
   * mirrors. None of the three has a line in this repository to change; before this list they
   * were re-filed nightly, for ever, and the recorded disposition was "not fixed, deliberately"
   * — which is a backlog entry pretending to be a decision.
   *
   * <p>Muting is not silencing: every run reports how many groups were muted and which rules did
   * it, so a rule that has started matching more than it should is visible in the same place the
   * findings are. What it does mean is that the muted problem's ticket stops being refreshed, so
   * the absence sweep closes it after {@code resolveAfter} — the same path a genuinely fixed
   * problem takes.
   *
   * @param reason why this noise is somebody else's, in a sentence. Never matched on; it is what
   *     a future reader needs in order to delete the rule safely
   * @param container substring of the container name the rule is confined to. Blank matches any
   *     container, which is almost never what you want — the phrases below are generic enough
   *     that a first-party log could legitimately contain one
   * @param contains the literal substring that identifies the noise, matched case-sensitively
   *     against a variant's normalised signature and its example line
   */
  public record Ignore(String reason, String container, String contains) {

    public Ignore {
      reason = reason == null ? "" : reason.trim();
      container = container == null ? "" : container.trim();
      contains = contains == null ? "" : contains.trim();
    }

    /** Whether this rule is specific enough to be applied at all. */
    public boolean usable() {
      return !contains.isBlank();
    }

    /**
     * Whether this rule <em>on its own</em> mutes a whole group.
     *
     * <p>A scan asks {@link LogWatchProperties#mutedBy(LogSignature)} instead, which lets several
     * rules cover one group between them; this is the single-rule form of the same question.
     *
     * <p><strong>Every</strong> variant must match, not merely the group's leader. A group is
     * keyed on the emitting code, and one logger can emit two genuinely different faults — that
     * is the standing objection to grouping by source key, which {@code LogSignature.variants}
     * exists to answer. Muting on the leader alone would let a real failure ride out of sight
     * inside a group whose most frequent message happens to be noise.
     *
     * <p>And a group whose variants were capped is never muted, however well the visible ones
     * match: {@code MAX_VARIANTS} limits what is <em>listed</em>, not what was <em>seen</em>, so
     * beyond that point the rule cannot vouch for the group. Same distinction, and the same
     * reason, as the per-run cap's veto over the absence sweep.
     *
     * @param signature the grouped problem
     * @return whether it is this rule's noise, all of it
     */
    public boolean mutes(final LogSignature signature) {
      if (!appliesTo(signature)) {
        return false;
      }
      if (signature.distinctVariants() > signature.variants().size()) {
        return false;
      }
      if (signature.variants().isEmpty()) {
        return matchesLeaderOf(signature);
      }
      return signature.variants().stream().allMatch(this::matches);
    }

    /** Whether this rule is usable at all and confined to this group's container. */
    boolean appliesTo(final LogSignature signature) {
      return usable()
          && signature != null
          && (container.isBlank() || contains(signature.container()));
    }

    /**
     * Whether this rule claims a group's leading message, used only for a variant-less signature
     * replayed from a Temporal history serialized before variants existed.
     */
    boolean matchesLeaderOf(final LogSignature signature) {
      return containsPhrase(signature.signature()) || containsPhrase(signature.exampleLine());
    }

    /** Whether this rule claims one distinct message within a group. */
    boolean matches(final LogSignature.Variant variant) {
      return variant != null
          && (containsPhrase(variant.signature()) || containsPhrase(variant.exampleLine()));
    }

    private boolean contains(final String containerName) {
      return containerName != null && containerName.contains(container);
    }

    private boolean containsPhrase(final String text) {
      return text != null && text.contains(contains);
    }
  }

  /**
   * Grafana Cloud Loki endpoint and credential.
   *
   * @param endpoint the value of {@code GRAFANA_CLOUD_LOKI_ENDPOINT}, which is the <em>push</em>
   *     URL and already contains {@code /loki/api/v1}
   * @param user the numeric tenant id
   * @param apiKey the access-policy token, carrying both {@code logs:write} and {@code logs:read}
   * @param requestTimeout per-request timeout
   */
  public record Loki(String endpoint, String user, String apiKey, Duration requestTimeout) {

    public Loki {
      endpoint = endpoint == null ? "" : endpoint;
      user = user == null ? "" : user;
      apiKey = apiKey == null ? "" : apiKey;
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
    }

    static Loki defaults() {
      return new Loki(null, null, null, null);
    }

    /**
     * The query base: the configured endpoint with its trailing {@code /push} removed.
     *
     * <p>Load-bearing and easy to get wrong. {@code GRAFANA_CLOUD_LOKI_ENDPOINT} already ends in
     * {@code /loki/api/v1/push}, so appending {@code /api/v1/...} to the raw value produces
     * {@code /loki/api/v1/api/v1/...}, which returns a bare {@code 404 page not found} — plain
     * text, no JSON, and no hint that the path is doubled. Resolved once here rather than in
     * every caller.
     *
     * @return the base to append {@code /query_range} and friends to
     */
    public String queryBase() {
      String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1)
          : endpoint;
      return trimmed.endsWith("/push") ? trimmed.substring(0, trimmed.length() - "/push".length())
          : trimmed;
    }

    /** Whether enough is configured to attempt a read at all. */
    public boolean configured() {
      return !endpoint.isBlank() && !user.isBlank() && !apiKey.isBlank();
    }
  }

  /**
   * Alloy's own component API, used for the direct tier of the source-health check.
   *
   * <p>{@code alloy} publishes no host port and nginx routes nothing to it, but both containers
   * sit on the same compose network, so this needs no compose change. It is best-effort: when it
   * cannot be reached the health check falls back to inferring from container coverage.
   *
   * @param baseUrl Alloy's HTTP server, on the compose network
   * @param requestTimeout kept short, because this is an optional signal on the critical path
   */
  public record Alloy(String baseUrl, Duration requestTimeout) {

    public Alloy {
      baseUrl = baseUrl == null ? "http://alloy:12345" : baseUrl;
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
    }

    static Alloy defaults() {
      return new Alloy(null, null);
    }
  }
}
