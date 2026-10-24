package com.simonrowe.factory.ticketagent.npm;

import com.simonrowe.factory.cvefix.domain.NpmRepairTitle;
import com.simonrowe.factory.git.NpmLockPins;
import com.simonrowe.factory.ticketagent.config.NpmRepairProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One npm repair: GitHub Actions regenerates the lockfile, and this decides whether the result may
 * be pushed.
 *
 * <p>Nothing here builds, installs or runs npm. The workflow does that on GitHub's runners, on the
 * protected default branch, with no secret and no write permission. What comes back is checked
 * three ways before the ticket agent pushes it: the workflow's own report that every copy reached
 * the fixed release, {@link NpmLockPins#check} against the lockfile the agent cloned, and {@link
 * NpmLockPins#below} on the result, so the push never rests on the workflow's word alone.
 */
@Component
public class NpmLockfileRepair {

  /** Sleeps between polls; a field so tests need not wait. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final NpmRepairActions actions;
  private final NpmRepairProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Sleeper sleeper;

  @Autowired
  public NpmLockfileRepair(
      final NpmRepairActions actions,
      final NpmRepairProperties properties,
      final ObjectMapper objectMapper) {
    this(actions, properties, objectMapper, Clock.systemUTC(),
        duration -> Thread.sleep(duration.toMillis()));
  }

  NpmLockfileRepair(
      final NpmRepairActions actions,
      final NpmRepairProperties properties,
      final ObjectMapper objectMapper,
      final Clock clock,
      final Sleeper sleeper) {
    this.actions = actions;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /**
   * What the repair produced.
   *
   * @param pushable true when the lockfile may be pushed
   * @param reason why not, for the ticket; empty when pushable
   * @param lockfile the regenerated lockfile when pushable
   * @param changes every package that moved, when pushable
   * @param runUrl the workflow run, when one ran
   */
  public record Outcome(
      boolean pushable,
      String reason,
      String lockfile,
      List<NpmLockPins.Change> changes,
      String runUrl) {

    public Outcome {
      reason = reason == null ? "" : reason;
      changes = changes == null ? List.of() : List.copyOf(changes);
      runUrl = runUrl == null ? "" : runUrl;
    }

    static Outcome refused(final String reason, final String runUrl) {
      return new Outcome(false, reason, null, List.of(), runUrl);
    }
  }

  /**
   * Regenerates the lockfile for one package and checks the result.
   *
   * @param repair the package and the release that fixes it
   * @param baseLockfile the lockfile on the branch the repair starts from
   * @param installationId the App installation
   * @param heartbeat called while waiting
   * @return the outcome; a refusal is an outcome, not an exception
   * @throws NpmRepairUnavailableException when the App may not start the workflow
   */
  public Outcome run(
      final NpmRepairTitle.Parsed repair,
      final String baseLockfile,
      final Long installationId,
      final Consumer<String> heartbeat) {
    String name = repair.name();
    String fixed = repair.fixedVersion();
    List<String> before = NpmLockPins.below(baseLockfile, name, fixed);
    if (before.isEmpty()) {
      return Outcome.refused(
          "`%s` is already at %s or later on the default branch, so there is nothing to change."
              .formatted(name, fixed), null);
    }
    if (before.contains("(not installed)") || before.contains("(unreadable)")) {
      return Outcome.refused(
          "`%s` is not in `%s` %s.".formatted(name, NpmLockPins.LOCKFILE, before.get(0)), null);
    }

    String requestId = UUID.randomUUID().toString();
    heartbeat.accept("Starting the npm lockfile workflow for " + name);
    actions.dispatch(requestId, name + "@" + fixed, installationId);
    Instant deadline = clock.instant().plus(properties.runWait());

    Optional<NpmRepairActions.Run> found = Optional.empty();
    while (found.isEmpty()) {
      if (!clock.instant().isBefore(deadline)) {
        return Outcome.refused(
            "GitHub never listed the npm lockfile run within %s.".formatted(properties.runWait()),
            null);
      }
      pause(heartbeat, "Waiting for GitHub to list the npm lockfile run");
      found = actions.findRun(requestId, installationId);
    }
    NpmRepairActions.Run run = found.get();
    while (!run.completed()) {
      if (!clock.instant().isBefore(deadline)) {
        return Outcome.refused(
            "The npm lockfile run did not finish within %s: %s"
                .formatted(properties.runWait(), run.url()), run.url());
      }
      pause(heartbeat, "Waiting for the npm lockfile run: " + run.status());
      run = actions.run(run.id(), installationId);
    }
    if (!"success".equals(run.conclusion())) {
      return Outcome.refused(
          "The npm lockfile run ended `%s`: %s".formatted(run.conclusion(), run.url()), run.url());
    }

    Map<String, byte[]> files = actions.artifactFiles(run.id(), installationId);
    Optional<String> reported = report(files.get("result.json"), requestId, name);
    if (reported.isPresent()) {
      return Outcome.refused(reported.get() + " Run: " + run.url(), run.url());
    }
    String lockfile = NpmLockPins.text(files.get("package-lock.json"));
    NpmLockPins.Verdict verdict = NpmLockPins.check(baseLockfile, lockfile);
    if (!verdict.versionsOnly()) {
      return Outcome.refused(
          "The regenerated lockfile was refused: %s. Run: %s".formatted(verdict.reason(),
              run.url()), run.url());
    }
    List<String> after = NpmLockPins.below(lockfile, name, fixed);
    if (!after.isEmpty()) {
      return Outcome.refused(
          "`%s` is still below %s in the regenerated lockfile (%s). Run: %s"
              .formatted(name, fixed, String.join(", ", after), run.url()), run.url());
    }
    return new Outcome(true, "", lockfile, verdict.changes(), run.url());
  }

  /**
   * Why the workflow's own report refuses the result, or empty when it says every copy reached
   * the fixed release. A report for another request is refused: it is not this run's answer.
   */
  private Optional<String> report(final byte[] json, final String requestId, final String name) {
    if (json == null) {
      return Optional.of("The run's artifact had no `result.json`.");
    }
    JsonNode result;
    try {
      result = objectMapper.readTree(json);
    } catch (JacksonException exception) {
      return Optional.of("The run's `result.json` is not valid JSON.");
    }
    if (!requestId.equals(result.path("requestId").asText(""))) {
      return Optional.of("The run's `result.json` answers a different request.");
    }
    if (result.path("ok").asBoolean(false)) {
      return Optional.empty();
    }
    for (JsonNode pkg : result.path("packages")) {
      if (name.equals(pkg.path("name").asText("")) && !pkg.path("ok").asBoolean(false)) {
        List<String> versions = new ArrayList<>();
        for (JsonNode version : pkg.path("versions")) {
          versions.add(version.asText(""));
        }
        return Optional.of(
            """
            `npm update %s` could not move every copy to %s (installed: %s). A package that \
            depends on it does not allow that release, so the fix needs a person."""
                .formatted(name, pkg.path("fixed").asText(""),
                    versions.isEmpty() ? "none" : String.join(", ", versions)));
      }
    }
    return Optional.of("The run reported the update as incomplete.");
  }

  private void pause(final Consumer<String> heartbeat, final String detail) {
    heartbeat.accept(detail);
    try {
      sleeper.sleep(properties.pollInterval());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted waiting for the npm lockfile run", exception);
    }
  }
}
