package com.simonrowe.factory.ticketagent.npm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.cvefix.domain.NpmRepairTitle;
import com.simonrowe.factory.ticketagent.config.NpmRepairProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class NpmLockfileRepairTest {

  private static final String INTEGRITY = "sha512-" + "abcdefghi+".repeat(8) + "abcdef==";
  private static final String BASE = """
      {"lockfileVersion": 3, "packages": {"": {"name": "f"},
        "node_modules/postcss": {"version": "8.5.6"},
        "node_modules/nanoid": {"version": "3.3.11"}}}""";
  private static final String HEAD = """
      {"lockfileVersion": 3, "packages": {"": {"name": "f"},
        "node_modules/postcss": {"version": "8.5.31",
          "resolved": "https://registry.npmjs.org/postcss/-/postcss-8.5.31.tgz",
          "integrity": "%1$s"},
        "node_modules/nanoid": {"version": "3.3.20",
          "resolved": "https://registry.npmjs.org/nanoid/-/nanoid-3.3.20.tgz",
          "integrity": "%1$s"}}}""".formatted(INTEGRITY);
  private static final NpmRepairTitle.Parsed POSTCSS =
      new NpmRepairTitle.Parsed("postcss", "8.5.10");
  private static final String RUN_URL = "https://github.com/o/r/actions/runs/9";

  private final NpmRepairActions actions = mock(NpmRepairActions.class);
  private final MovingClock clock = new MovingClock();
  private final NpmLockfileRepair repair =
      new NpmLockfileRepair(
          actions,
          new NpmRepairProperties(null, null, Duration.ofMinutes(15), Duration.ofSeconds(10), 0),
          new ObjectMapper(),
          clock,
          duration -> clock.advance(duration));
  private final AtomicReference<String> requestId = new AtomicReference<>();

  /** A clock the sleeper moves forward, so a timeout is reached without waiting for it. */
  private static final class MovingClock extends Clock {
    private Instant now = Instant.parse("2026-10-24T12:00:00Z");

    void advance(final Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @BeforeEach
  void dispatchRecordsTheRequest() {
    org.mockito.Mockito.doAnswer(invocation -> {
      requestId.set(invocation.getArgument(0));
      return null;
    }).when(actions).dispatch(anyString(), anyString(), any());
  }

  private static byte[] result(final String id, final boolean ok, final String versions) {
    return """
        {"requestId": "%s", "ok": %s, "packages": [
          {"name": "postcss", "fixed": "8.5.10", "versions": [%s], "ok": %s}]}"""
        .formatted(id, ok, versions, ok).getBytes(StandardCharsets.UTF_8);
  }

  private void runSucceedsWith(final String lockfile, final boolean ok) {
    when(actions.findRun(anyString(), any())).thenAnswer(invocation -> Optional.of(
        new NpmRepairActions.Run(9, "in_progress", "", RUN_URL)));
    when(actions.run(eq(9L), any()))
        .thenReturn(new NpmRepairActions.Run(9, "completed", "success", RUN_URL));
    when(actions.artifactFiles(eq(9L), any())).thenAnswer(invocation -> Map.of(
        "package-lock.json", lockfile.getBytes(StandardCharsets.UTF_8),
        "result.json", result(requestId.get(), ok, ok ? "\"8.5.31\"" : "\"8.5.6\"")));
  }

  @Test
  void regeneratedLockfileThatPassesEveryCheckIsPushable() {
    runSucceedsWith(HEAD, true);

    NpmLockfileRepair.Outcome outcome = repair.run(POSTCSS, BASE, 7L, detail -> { });

    assertThat(outcome.pushable()).as(outcome.reason()).isTrue();
    assertThat(outcome.lockfile()).isEqualTo(HEAD);
    assertThat(outcome.changes()).extracting(change -> change.name())
        .containsExactly("postcss", "nanoid");
    assertThat(outcome.runUrl()).isEqualTo(RUN_URL);
    ArgumentCaptor<String> packages = ArgumentCaptor.forClass(String.class);
    verify(actions).dispatch(anyString(), packages.capture(), eq(7L));
    assertThat(packages.getValue()).isEqualTo("postcss@8.5.10");
    assertThat(requestId.get()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
        .concat("[0-9a-f]{12}"));
  }

  @Test
  void alreadyFixedOnMainStartsNothing() {
    NpmLockfileRepair.Outcome outcome =
        repair.run(new NpmRepairTitle.Parsed("postcss", "8.5.6"), BASE, 7L, detail -> { });

    assertThat(outcome.pushable()).isFalse();
    assertThat(outcome.reason()).contains("already at 8.5.6 or later");
    verify(actions, never()).dispatch(anyString(), anyString(), any());
  }

  @Test
  void packageNotInTheLockfileStartsNothing() {
    NpmLockfileRepair.Outcome outcome =
        repair.run(new NpmRepairTitle.Parsed("left-pad", "1.0.0"), BASE, 7L, detail -> { });

    assertThat(outcome.reason()).contains("not in `frontend/package-lock.json`");
    verify(actions, never()).dispatch(anyString(), anyString(), any());
  }

  @Test
  void fixOutsideDependentRangeIsHandedBack() {
    runSucceedsWith(BASE, false);

    NpmLockfileRepair.Outcome outcome = repair.run(POSTCSS, BASE, 7L, detail -> { });

    assertThat(outcome.pushable()).isFalse();
    assertThat(outcome.reason())
        .contains("could not move every copy to 8.5.10", "installed: 8.5.6", "needs a person");
  }

  @Test
  void workflowReportIsNotTakenOnTrust() {
    String added = HEAD.replace("\"node_modules/nanoid\"",
        "\"node_modules/evil\": {\"version\": \"1.0.0\"}, \"node_modules/nanoid\"");
    runSucceedsWith(added, true);

    NpmLockfileRepair.Outcome outcome = repair.run(POSTCSS, BASE, 7L, detail -> { });

    assertThat(outcome.pushable()).isFalse();
    assertThat(outcome.reason()).contains("refused", "adds `node_modules/evil`");
  }

  @Test
  void lockfileStillBelowTheFixIsRefusedWhateverTheReportSays() {
    String partial = HEAD.replace("8.5.31", "8.5.9");
    runSucceedsWith(partial, true);

    NpmLockfileRepair.Outcome outcome = repair.run(POSTCSS, BASE, 7L, detail -> { });

    assertThat(outcome.reason()).contains("still below 8.5.10", "8.5.9");
  }

  @Test
  void reportForAnotherRequestIsRefused() {
    runSucceedsWith(HEAD, true);
    when(actions.artifactFiles(eq(9L), any())).thenReturn(Map.of(
        "package-lock.json", HEAD.getBytes(StandardCharsets.UTF_8),
        "result.json", result("someone-else", true, "\"8.5.31\"")));

    assertThat(repair.run(POSTCSS, BASE, 7L, detail -> { }).reason())
        .contains("different request");
  }

  @Test
  void failedRunIsHandedBackWithItsAddress() {
    when(actions.findRun(anyString(), any())).thenReturn(Optional.of(
        new NpmRepairActions.Run(9, "completed", "failure", RUN_URL)));

    NpmLockfileRepair.Outcome outcome = repair.run(POSTCSS, BASE, 7L, detail -> { });

    assertThat(outcome.reason()).contains("ended `failure`", RUN_URL);
    verify(actions, never()).artifactFiles(anyLong(), any());
  }

  @Test
  void runGitHubNeverListsTimesOut() {
    when(actions.findRun(anyString(), any())).thenReturn(Optional.empty());

    assertThat(repair.run(POSTCSS, BASE, 7L, detail -> { }).reason())
        .contains("never listed the npm lockfile run within PT15M");
  }

  @Test
  void runThatNeverFinishesTimesOut() {
    when(actions.findRun(anyString(), any())).thenReturn(Optional.of(
        new NpmRepairActions.Run(9, "queued", "", RUN_URL)));
    when(actions.run(eq(9L), any())).thenReturn(new NpmRepairActions.Run(9, "queued", "", RUN_URL));

    assertThat(repair.run(POSTCSS, BASE, 7L, detail -> { }).reason())
        .contains("did not finish within PT15M", RUN_URL);
  }

  @Test
  void missingGrantPropagatesForTheAttemptToReport() {
    doThrow(new NpmRepairUnavailableException("grant it `actions: write`"))
        .when(actions).dispatch(anyString(), anyString(), any());

    assertThatThrownBy(() -> repair.run(POSTCSS, BASE, 7L, detail -> { }))
        .isInstanceOf(NpmRepairUnavailableException.class)
        .hasMessageContaining("actions: write");
  }
}
