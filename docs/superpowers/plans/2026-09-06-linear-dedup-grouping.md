# Linear Ticket Dedup — Group by Emitting Source, Update In Place

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `logwatch` filing a new Linear ticket every time a log message's free text varies, and let the `linear` sink update an existing ticket in place instead of only commenting on it.

**Architecture:** Two independent changes. In `logwatch`, a new pure `SourceKeyExtractor` identifies the code that emitted a line (logger name, logfmt `component_id`, exception class), and `SignatureExtractor.group` groups on that instead of on the whole normalised line, carrying the distinct message templates along as *variants* so the coarser key loses nothing. In the `linear` sink, `IssueFiling.commentOnly` becomes a four-valued `FilingMode`, letting a producer ask for its ticket's description to be rewritten to current state, and letting a "rolling" producer reopen a completed ticket rather than file a linked replacement.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Temporal, JUnit 6, AssertJ, Mockito, Gradle 9.7.1. No new dependencies in any module.

## Global Constraints

- **Spec:** `specs/046-linear-dedup-grouping/spec.md`. Every requirement reference below (`FR-001` … `FR-010`) is to that file.
- **No new dependencies** in `software-factory` or anywhere else.
- **`Fingerprint.VERSION` MUST stay `"v1"`** (FR-004). Changing the logwatch key parts orphans logwatch fingerprints on its own; bumping the version would additionally orphan `deploy`, `cvefix` and `review-feedback`, which have no duplicate problem.
- **`FilingDecider` MUST stay pure, I/O-free and behaviourally unchanged** (FR-007). All mode handling lives in `IssueFiler`.
- **`deploy` and `review-feedback` filing behaviour MUST NOT change.** They keep `FilingMode.OCCURRENCE`, which is byte-for-byte today's behaviour.
- Java style is Google Java Style, enforced by Checkstyle against `config/checkstyle/google_checks.xml`. **Every public type and public method needs Javadoc**, including `@param` on every parameter and `@return` on every non-void return. Lines wrap at 100 columns.
- Test framework is JUnit 6 with AssertJ (`assertThat`) and Mockito. Existing tests under `software-factory/src/test/java/com/simonrowe/factory/` are the pattern to copy.
- Build and test commands, run from the repository root:
  - Everything: `./gradlew :software-factory:test`
  - One class: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.logwatch.signature.SourceKeyExtractorTest'`
  - Style: `./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest`
- **Three `software-factory` HTTP-stub test classes are known-flaky** on port and connection reuse, `LinearGatewayReadTest` and `LinearGatewayWriteTest` among them. If one fails on a connection error rather than an assertion, re-run it isolated three times before blaming your change.
- Commit messages are conventional commits with no Jira ticket and **no Claude attribution**.

---

## File Structure

**`logwatch` (Tasks 1–3)**

| File | Responsibility |
|---|---|
| `software-factory/src/main/java/com/simonrowe/factory/logwatch/signature/SourceKeyExtractor.java` | **New.** Pure. Raw line to the identity of the code that emitted it, or empty. |
| `software-factory/src/main/java/com/simonrowe/factory/logwatch/domain/LogSignature.java` | Modify. Gains `sourceKey`, `variants`, `distinctVariants` and a nested `Variant` record. |
| `software-factory/src/main/java/com/simonrowe/factory/logwatch/signature/SignatureExtractor.java` | Modify. `group` keys on `(container, severity, discriminatedSource)` and accumulates variants. `normalise` and `RULES` are untouched. |
| `software-factory/src/main/java/com/simonrowe/factory/logwatch/workflow/LogWatchReportRenderer.java` | Modify. Title names the source; body lists the variants. |
| `software-factory/src/main/java/com/simonrowe/factory/logwatch/workflow/LogWatchWorkflowImpl.java` | Modify. New fingerprint key parts; `FilingMode.REFRESH`. |

**`linear` sink (Tasks 4–6)**

| File | Responsibility |
|---|---|
| `software-factory/src/main/java/com/simonrowe/factory/linear/domain/FilingMode.java` | **New.** The four filing modes and the three predicates the filer branches on. |
| `software-factory/src/main/java/com/simonrowe/factory/linear/domain/IssueFiling.java` | Modify. `boolean commentOnly` becomes `FilingMode mode`. |
| `software-factory/src/main/java/com/simonrowe/factory/linear/domain/FilingDecision.java` | Modify. Gains `UPDATED_EXISTING` and `REOPENED_EXISTING`. |
| `software-factory/src/main/java/com/simonrowe/factory/linear/linear/LinearGateway.java` | Modify. Gains `updateIssue(issueId, description, stateId)`. |
| `software-factory/src/main/java/com/simonrowe/factory/linear/service/IssueFiler.java` | Modify. `commentOnlySafe` becomes `applyMode`; two new action arms; the repair path honours the mode. |

**Producers and docs (Tasks 7–8)**

| File | Responsibility |
|---|---|
| `software-factory/src/main/java/com/simonrowe/factory/cvefix/workflow/CveFixWorkflowImpl.java` | Modify. `ROLLING` and `STATUS_UPDATE`; outcome counters (FR-009). |
| `docs/runbooks/logwatch.md`, `docs/runbooks/linear.md`, `CLAUDE.md` | Modify. |

---

### Task 1: `SourceKeyExtractor`

Identify the code that emitted a log line. This is the whole fix; everything else is plumbing around it.

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/logwatch/signature/SourceKeyExtractor.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/logwatch/signature/SourceKeyExtractorTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static Optional<String> sourceKeyOf(String raw)` — the emitting source, or `Optional.empty()` when no handler recognises the format.

**The fixtures below are real production lines**, read from Grafana Cloud Loki and captured from the fourteen Linear tickets cancelled on 2026-09-06 (SIM-11 through SIM-25). Do not invent lines.

- [ ] **Step 1: Write the failing test**

Create `software-factory/src/test/java/com/simonrowe/factory/logwatch/signature/SourceKeyExtractorTest.java`:

```java
package com.simonrowe.factory.logwatch.signature;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every fixture here is a real production line, read from Grafana Cloud Loki and captured from
 * the fourteen Linear tickets cancelled on 2026-09-06. That provenance matters: {@code
 * SignatureExtractorTest}'s fixtures were captured with {@code docker logs} because Loki held
 * nothing when 042 was written, so the rules it pins were recorded as estimates.
 */
class SourceKeyExtractorTest {

  private static final String ECS_EMBABEL =
      "{\"@timestamp\":\"2026-09-05T08:18:45.175457758Z\",\"log\":{\"level\":\"ERROR\","
          + "\"logger\":\"com.embabel.agent.spi.validation.DefaultAgentValidationManager\"},"
          + "\"process\":{\"pid\":1,\"thread\":{\"name\":\"main\"}},\"service\":{\"name\":"
          + "\"simonrowe-backend\",\"version\":\"0.0.1-SNAPSHOT\",\"node\":{}},\"message\":"
          + "\"- MISSING_GOALS: Agent 'WeeklyDigest' must have at least one goal defined\","
          + "\"ecs\":{\"version\":\"8.11\"}}";

  private static final String ECS_SPRING_APPLICATION =
      "{\"@timestamp\":\"2026-09-01T19:41:13.215299316Z\",\"log\":{\"level\":\"ERROR\","
          + "\"logger\":\"org.springframework.boot.SpringApplication\"},\"process\":{\"pid\":1,"
          + "\"thread\":{\"name\":\"main\"}},\"message\":\"Application run failed\"}";

  private static final String TEMPORAL_JSON =
      "{\"level\":\"error\",\"ts\":\"2026-09-01T02:55:45.312Z\",\"msg\":\"Operation failed with "
          + "internal error.\",\"error\":\"GetTaskQueue operation failed. Failed to check if task "
          + "queue /_sys/temporal-sys-processor-parent-close-policy/3 of type Workflow existed. "
          + "Error: context canceled\",\"error-type\":\"serviceerror.Unavailable\",\"operation\":"
          + "\"GetTaskQueue\"}";

  private static final String LOGFMT_LOKI_WRITE =
      "ts=2026-09-03T05:59:25.342351851Z level=error msg=\"final error sending batch, no retries "
          + "left, dropping data\" component_path=/ component_id=loki.write.grafana_cloud "
          + "component=endpoint host=logs-prod-035.grafana.net status=-1 tenant=\"\"";

  private static final String LOGFMT_LOKI_SOURCE =
      "ts=2026-09-01T06:40:31.229962382Z level=error msg=\"could not fetch logs for container\" "
          + "component_path=/ component_id=loki.source.docker.default component=tailer";

  private static final String SPRING_PLAIN =
      "2026-09-04T00:00:09.047Z  WARN 1 --- [software-factory] [ce=\"default\": 1] "
          + "c.s.factory.linear.linear.LinearGateway  : Team SIM has no label named "
          + "factory:logwatch - filing this issue unlabelled.";

  private static final String JAVA_EXCEPTION =
      "java.lang.IllegalStateException: backup-platform.sh exited with 1: [backup-platform] "
          + "ERROR: python3 is required (JSON handling)";

  private static final String BARE_TEXT =
      "ERROR: Elasticsearch did not exit normally - check the logs at "
          + "/usr/share/elasticsearch/logs/docker-cluster.log";

  @Test
  @DisplayName("ECS JSON yields the log.logger value")
  void ecsJsonYieldsLogger() {
    assertThat(SourceKeyExtractor.sourceKeyOf(ECS_EMBABEL))
        .contains("com.embabel.agent.spi.validation.DefaultAgentValidationManager");
  }

  @Test
  @DisplayName("Temporal JSON yields its msg, which is a literal template, never interpolated")
  void temporalJsonYieldsMessage() {
    assertThat(SourceKeyExtractor.sourceKeyOf(TEMPORAL_JSON))
        .contains("Operation failed with internal error.");
  }

  @Test
  @DisplayName("logfmt yields component_id")
  void logfmtYieldsComponentId() {
    assertThat(SourceKeyExtractor.sourceKeyOf(LOGFMT_LOKI_WRITE))
        .contains("loki.write.grafana_cloud");
  }

  @Test
  @DisplayName("Spring plain text yields the abbreviated logger, not the bracketed MDC context")
  void springPlainTextYieldsLogger() {
    assertThat(SourceKeyExtractor.sourceKeyOf(SPRING_PLAIN))
        .contains("c.s.factory.linear.linear.LinearGateway");
  }

  @Test
  @DisplayName("a bare stack-trace head yields the exception class")
  void javaExceptionYieldsClass() {
    assertThat(SourceKeyExtractor.sourceKeyOf(JAVA_EXCEPTION))
        .contains("java.lang.IllegalStateException");
  }

  @Test
  @DisplayName("an unrecognised format yields empty, so the caller falls back to the signature")
  void unrecognisedYieldsEmpty() {
    assertThat(SourceKeyExtractor.sourceKeyOf(BARE_TEXT)).isEmpty();
    assertThat(SourceKeyExtractor.sourceKeyOf("")).isEmpty();
    assertThat(SourceKeyExtractor.sourceKeyOf(null)).isEmpty();
  }

  @Test
  @DisplayName("SIM-11 and SIM-13 stay separate: one incident, two pieces of emitting code")
  void oneIncidentFromTwoLoggersStaysTwoSources() {
    assertThat(SourceKeyExtractor.sourceKeyOf(ECS_SPRING_APPLICATION))
        .isNotEqualTo(SourceKeyExtractor.sourceKeyOf(ECS_EMBABEL));
  }

  @Test
  @DisplayName("SIM-15 and SIM-16 stay separate: two Alloy components, two problems")
  void twoAlloyComponentsStayTwoSources() {
    assertThat(SourceKeyExtractor.sourceKeyOf(LOGFMT_LOKI_SOURCE))
        .isNotEqualTo(SourceKeyExtractor.sourceKeyOf(LOGFMT_LOKI_WRITE));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.logwatch.signature.SourceKeyExtractorTest'`

Expected: **compilation failure** — `cannot find symbol: class SourceKeyExtractor`.

- [ ] **Step 3: Write the implementation**

Create `software-factory/src/main/java/com/simonrowe/factory/logwatch/signature/SourceKeyExtractor.java`:

```java
package com.simonrowe.factory.logwatch.signature;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces a log line to the identity of the code that emitted it.
 *
 * <p>This exists because grouping on the whole normalised line does not work.
 * {@link SignatureExtractor#normalise} masks timestamps, identifiers, numbers and paths, but not
 * free text inside a message, so {@code Agent 'ContentAggregation' must have...} and
 * {@code Agent 'WeeklyDigest' must have...} fingerprinted differently and filed two Linear tickets
 * for one startup failure. Six such forks were live in Triage on 2026-09-06.
 *
 * <p>Pure over strings, with no clock, no client and no Spring, on the same terms and for the same
 * reason as {@link SignatureExtractor}.
 *
 * <p>Handlers are tried in declaration order and each is guarded, so a line only reaches a handler
 * whose format it plausibly is. Returning empty is a first-class answer: the caller falls back to
 * the normalised whole line, which is exactly the behaviour that shipped before this class
 * existed, so an unrecognised format is never worse off than it was.
 */
public final class SourceKeyExtractor {

  /** The ECS JSON the backend emits. The {@code log.logger} value is the emitting class. */
  private static final Pattern ECS_LOGGER = Pattern.compile("\"logger\"\\s*:\\s*\"([^\"]+)\"");

  /**
   * Temporal's zap JSON. Its {@code msg} is a literal and is never interpolated — the variable
   * part of the event lives in the sibling {@code error} and {@code operation} fields — so it
   * identifies the emitting call site as well as a logger name would. {@code logging-call-at} is
   * the more obvious choice and is deliberately not used: it carries a source line number, so a
   * Temporal upgrade that shifts the file by one line would fork every ticket it emits.
   */
  private static final Pattern TEMPORAL_MSG =
      Pattern.compile("\"msg\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

  /** Alloy's logfmt. {@code component_id} names the pipeline component. */
  private static final Pattern LOGFMT_COMPONENT = Pattern.compile("(?:^|\\s)component_id=(\\S+)");

  /**
   * Spring Boot's default console layout. The logger is right-padded to a fixed width and then
   * separated by {@code " : "}, which is what makes whitespace-colon-whitespace a reliable
   * delimiter: the bracketed MDC context on the same line spells its colon as {@code "default": 1},
   * with no space before it, so it cannot match.
   */
  private static final Pattern SPRING_LOGGER = Pattern.compile("(?:^|\\s)([\\w.$]+)\\s+:\\s");

  /** A bare stack-trace head, as the deployer emits when a script fails. */
  private static final Pattern EXCEPTION_CLASS =
      Pattern.compile("^([a-zA-Z_$][\\w$]*(?:\\.[\\w$]+)+)\\s*:");

  private static final List<Handler> HANDLERS =
      List.of(
          new Handler(raw -> raw.startsWith("{") && raw.contains("\"logger\""), ECS_LOGGER),
          new Handler(raw -> raw.startsWith("{") && !raw.contains("\"logger\""), TEMPORAL_MSG),
          new Handler(raw -> raw.startsWith("ts=") || raw.contains(" level="), LOGFMT_COMPONENT),
          new Handler(raw -> raw.contains(" --- ["), SPRING_LOGGER),
          new Handler(raw -> !raw.startsWith("{"), EXCEPTION_CLASS));

  private SourceKeyExtractor() {
  }

  /**
   * Identifies the code that emitted a line.
   *
   * @param raw the line as shipped, before signature normalisation; may be null
   * @return the emitting source, or empty when no handler recognises the format
   */
  public static Optional<String> sourceKeyOf(final String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String line = raw.strip();
    for (Handler handler : HANDLERS) {
      if (!handler.guard().test(line)) {
        continue;
      }
      Matcher matcher = handler.pattern().matcher(line);
      if (matcher.find()) {
        String key = matcher.group(1).strip();
        if (!key.isEmpty()) {
          return Optional.of(key);
        }
      }
    }
    return Optional.empty();
  }

  private record Handler(Predicate<String> guard, Pattern pattern) {
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.logwatch.signature.SourceKeyExtractorTest'`

Expected: PASS, 8 tests.

If `temporalJsonYieldsMessage` fails, check the guard order — the ECS handler must be tried first, and its guard requires `"logger"` to be present, which Temporal's JSON does not contain.

- [ ] **Step 5: Run Checkstyle**

Run: `./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/logwatch/signature/SourceKeyExtractor.java \
        software-factory/src/test/java/com/simonrowe/factory/logwatch/signature/SourceKeyExtractorTest.java
git commit -m "feat: identify the code that emitted a log line"
```

---

### Task 2: Two-level grouping with variants

Group on the emitting source, and carry the distinct message templates along so nothing is lost to the coarser key.

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/logwatch/domain/LogSignature.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/logwatch/signature/SignatureExtractor.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/logwatch/signature/SignatureExtractorTest.java` (existing — add to it)

**Interfaces:**
- Consumes: `SourceKeyExtractor.sourceKeyOf(String)` from Task 1.
- Produces:
  - `LogSignature`'s canonical constructor, in this exact order: `(String signature, Severity severity, String container, int occurrences, Instant firstSeen, Instant lastSeen, String exampleLine, String sourceKey, List<LogSignature.Variant> variants, int distinctVariants)`. **The three new components are appended, not inserted** — the same reasoning `DeployProperties` records for its last-position flag, so existing positional call sites in tests keep compiling until they are deliberately updated.
  - `LogSignature.Variant` — `(String signature, int occurrences, String exampleLine)`.
  - `LogSignature.MAX_VARIANTS` — `public static final int`, value `5`.
  - `SignatureExtractor.group(List<LogLine>)` — unchanged signature, new grouping.

- [ ] **Step 1: Write the failing test**

Append to the existing `SignatureExtractorTest` class. It already declares `T0`, and already imports `LogLine`, `LogSignature`, `Severity`, `Instant`, `ArrayList` and `List`.

```java
  private static LogLine line(final String container, final Severity severity, final String raw) {
    return new LogLine(container, T0, severity, raw);
  }

  @Test
  @DisplayName("SIM-13/24/25: three phrasings from one logger become one group")
  void oneLoggerIsOneGroup() {
    String prefix =
        "{\"@timestamp\":\"2026-09-05T08:18:45.175457758Z\",\"log\":{\"level\":\"ERROR\","
            + "\"logger\":\"com.embabel.agent.spi.validation.DefaultAgentValidationManager\"},"
            + "\"message\":\"";
    List<LogSignature> grouped =
        SignatureExtractor.group(
            List.of(
                line("backend", Severity.ERROR, prefix + "Validation failed with 1 errors:\"}"),
                line("backend", Severity.ERROR, prefix
                    + "- MISSING_GOALS: Agent 'ContentAggregation' must have one goal\"}"),
                line("backend", Severity.ERROR, prefix
                    + "- MISSING_GOALS: Agent 'WeeklyDigest' must have one goal\"}")));

    assertThat(grouped).hasSize(1);
    assertThat(grouped.getFirst().occurrences()).isEqualTo(3);
    assertThat(grouped.getFirst().sourceKey())
        .isEqualTo("logger:com.embabel.agent.spi.validation.DefaultAgentValidationManager");
    assertThat(grouped.getFirst().distinctVariants()).isEqualTo(3);
    assertThat(grouped.getFirst().variants()).hasSize(3);
  }

  @Test
  @DisplayName("SIM-16/23: one Alloy component with two different error payloads is one group")
  void oneAlloyComponentIsOneGroup() {
    String prefix =
        "ts=2026-09-03T05:59:25.342351851Z level=error msg=\"final error sending batch\" "
            + "component_id=loki.write.grafana_cloud error=\"";
    List<LogSignature> grouped =
        SignatureExtractor.group(
            List.of(
                line("alloy", Severity.ERROR, prefix + "dial tcp: server misbehaving\""),
                line("alloy", Severity.ERROR, prefix + "server returned HTTP status 400\"")));

    assertThat(grouped).hasSize(1);
    assertThat(grouped.getFirst().sourceKey()).isEqualTo("logger:loki.write.grafana_cloud");
    assertThat(grouped.getFirst().distinctVariants()).isEqualTo(2);
  }

  @Test
  @DisplayName("a line with no identifiable source still groups on its normalised text")
  void unrecognisedFormatFallsBackToTheSignature() {
    String raw = "ERROR: Elasticsearch did not exit normally - check the logs at /var/log/es.log";
    List<LogSignature> grouped =
        SignatureExtractor.group(List.of(line("elasticsearch", Severity.ERROR, raw)));

    assertThat(grouped).hasSize(1);
    assertThat(grouped.getFirst().sourceKey())
        .isEqualTo("line:" + SignatureExtractor.normalise(raw));
  }

  @Test
  @DisplayName("severity is part of the group key, so WARN and ERROR never merge")
  void severitySplitsGroups() {
    String raw = "{\"log\":{\"logger\":\"com.example.Thing\"},\"message\":\"slow query\"}";
    List<LogSignature> grouped =
        SignatureExtractor.group(
            List.of(line("backend", Severity.ERROR, raw), line("backend", Severity.WARN, raw)));

    assertThat(grouped).hasSize(2);
  }

  @Test
  @DisplayName("the same source in two containers stays two problems")
  void containerStillSplitsGroups() {
    String raw = "{\"log\":{\"logger\":\"com.example.Thing\"},\"message\":\"boom\"}";
    List<LogSignature> grouped =
        SignatureExtractor.group(
            List.of(line("backend", Severity.ERROR, raw), line("deployer", Severity.ERROR, raw)));

    assertThat(grouped).hasSize(2);
  }

  @Test
  @DisplayName("variants are most-frequent-first and capped, with the true total kept")
  void variantsAreOrderedAndCapped() {
    String prefix = "{\"log\":{\"logger\":\"com.example.Chatty\"},\"message\":\"variant ";
    List<LogLine> lines = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      lines.add(line("backend", Severity.ERROR, prefix + (char) ('a' + i) + "\"}"));
    }
    // Make one variant clearly dominant so the ordering assertion is not a tie-break.
    lines.add(line("backend", Severity.ERROR, prefix + "a\"}"));
    lines.add(line("backend", Severity.ERROR, prefix + "a\"}"));

    LogSignature only = SignatureExtractor.group(lines).getFirst();

    assertThat(only.distinctVariants()).isEqualTo(8);
    assertThat(only.variants()).hasSize(LogSignature.MAX_VARIANTS);
    assertThat(only.variants().getFirst().occurrences()).isEqualTo(3);
    assertThat(only.variants().getFirst().signature()).contains("variant a");
    assertThat(only.signature()).isEqualTo(only.variants().getFirst().signature());
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.logwatch.signature.SignatureExtractorTest'`

Expected: **compilation failure** — `cannot find symbol: method sourceKey()`.

- [ ] **Step 3: Rewrite `LogSignature`**

Replace `software-factory/src/main/java/com/simonrowe/factory/logwatch/domain/LogSignature.java` entirely:

```java
package com.simonrowe.factory.logwatch.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * A group of log lines that are the same problem.
 *
 * <p>{@code sourceKey} is what the Linear fingerprint is computed from — never the generated
 * title, and since 046 no longer the whole normalised line either. {@code Fingerprint}'s javadoc
 * records the first half of that reasoning: the same problem phrased differently on two runs must
 * not file twice. {@code SourceKeyExtractor}'s records the second: the message <em>is</em> a
 * phrasing, so it cannot be the key.
 *
 * <p>{@code variants} is what makes the coarser key safe. The standing objection to grouping by
 * emitting code is that one logger may emit two genuinely different faults; listing the distinct
 * message templates in the ticket answers it, and is also what makes an updated ticket worth
 * reading.
 *
 * @param signature the normalised form of the most frequent variant; the title is built from it
 * @param severity the severity of the group, which is part of its key so cannot vary within it
 * @param container the container the lines came from
 * @param occurrences how many lines collapsed into this group
 * @param firstSeen the earliest occurrence in the window
 * @param lastSeen the latest occurrence in the window
 * @param exampleLine one real line from the most frequent variant, unnormalised, for the body
 * @param sourceKey the discriminated grouping key: {@code logger:<source>} when the emitting code
 *     could be identified, {@code line:<normalisedLine>} when it could not. The prefix is
 *     load-bearing — without it a source key whose text happened to equal a normalised line would
 *     silently merge two unrelated groups
 * @param variants the distinct normalised signatures in the group, most frequent first, capped at
 *     {@link #MAX_VARIANTS}
 * @param distinctVariants how many distinct signatures the group actually held, before capping
 */
public record LogSignature(
    String signature,
    Severity severity,
    String container,
    int occurrences,
    Instant firstSeen,
    Instant lastSeen,
    String exampleLine,
    String sourceKey,
    List<Variant> variants,
    int distinctVariants) {

  /**
   * How many variants a ticket lists. A body is a scanning aid; beyond a handful the list stops
   * being one, and {@link #distinctVariants} still reports the true total.
   */
  public static final int MAX_VARIANTS = 5;

  /**
   * The order the per-run cap applies: severity first, then occurrence count, both descending.
   *
   * <p>A constant rather than an inline lambda so the ordering is testable on its own — it decides
   * which findings are dropped when there are more than the cap allows. The final tie-break is
   * the source key rather than the signature, because the source key is now the group's identity
   * and is therefore what guarantees a total order.
   */
  public static final Comparator<LogSignature> MOST_SEVERE_FIRST =
      Comparator.comparing(LogSignature::severity)
          .thenComparing(Comparator.comparingInt(LogSignature::occurrences).reversed())
          .thenComparing(LogSignature::sourceKey);

  public LogSignature {
    variants = variants == null ? List.of() : List.copyOf(variants);
  }

  /**
   * One distinct message template within a group.
   *
   * @param signature the normalised form
   * @param occurrences how many lines had it
   * @param exampleLine one real line with it, unnormalised
   */
  public record Variant(String signature, int occurrences, String exampleLine) {
  }
}
```

- [ ] **Step 4: Rewrite `SignatureExtractor.group` and its accumulator**

In `SignatureExtractor`, replace the `group` method's Javadoc and body, and replace the `Accumulator` class. **Leave `normalise` and `RULES` completely untouched** — they are still correct and still exhaustively tested by the pre-existing tests in the same file.

Add the imports `java.util.Comparator` and `com.simonrowe.factory.logwatch.domain.Severity` if Checkstyle asks for them.

```java
  /**
   * Groups lines by the code that emitted them, keeping one example, the observed time range, and
   * the distinct message templates seen.
   *
   * <p>Grouping is per {@code (container, severity, discriminatedSource)}.
   *
   * <p><strong>Container</strong>: the same fault in two containers is two places to look.
   *
   * <p><strong>Severity</strong>: {@code WARN slow query} and {@code ERROR slow query} are two
   * problems, as {@code docs/runbooks/logwatch.md} records. It used to be implicit, since the
   * level word sits inside the normalised text of most formats, and is now explicit — so a format
   * that logs its level out of band cannot merge them by accident.
   *
   * <p><strong>Source</strong>: {@link SourceKeyExtractor} where it can identify the emitting
   * code, and the normalised whole line where it cannot, prefixed to keep the two spaces disjoint.
   * Falling back to the line is exactly the behaviour that shipped before 046, so an unrecognised
   * format is no worse off than it was.
   *
   * <p>Insertion order is preserved so the result is deterministic for a given input, which
   * matters because the caller's cap decides what is dropped.
   *
   * @param lines the severity-classified lines in the window
   * @return one entry per distinct {@code (container, severity, source)} triple
   */
  public static List<LogSignature> group(final List<LogLine> lines) {
    Map<String, Accumulator> byKey = new LinkedHashMap<>();
    for (LogLine line : lines) {
      String signature = normalise(line.raw());
      String sourceKey =
          SourceKeyExtractor.sourceKeyOf(line.raw())
              .map(key -> "logger:" + key)
              .orElse("line:" + signature);
      String key = line.container() + " " + line.severity() + " " + sourceKey;
      byKey.computeIfAbsent(key, ignored -> new Accumulator(sourceKey, line)).add(line, signature);
    }
    List<LogSignature> grouped = new ArrayList<>(byKey.size());
    for (Accumulator accumulator : byKey.values()) {
      grouped.add(accumulator.toSignature());
    }
    return grouped;
  }

  private record Rule(Pattern pattern, String replacement) {
  }

  /** Mutable while grouping; converted to the immutable {@link LogSignature} at the end. */
  private static final class Accumulator {

    private final String sourceKey;
    private final LogLine first;
    private final Map<String, VariantAccumulator> variants = new LinkedHashMap<>();
    private java.time.Instant firstSeen;
    private java.time.Instant lastSeen;
    private int occurrences;

    private Accumulator(final String sourceKey, final LogLine first) {
      this.sourceKey = sourceKey;
      this.first = first;
      this.firstSeen = first.timestamp();
      this.lastSeen = first.timestamp();
    }

    private void add(final LogLine line, final String signature) {
      occurrences++;
      variants.computeIfAbsent(signature, key -> new VariantAccumulator(key, line.raw())).count++;
      if (line.timestamp().isBefore(firstSeen)) {
        firstSeen = line.timestamp();
      }
      if (line.timestamp().isAfter(lastSeen)) {
        lastSeen = line.timestamp();
      }
    }

    private LogSignature toSignature() {
      // Most frequent first, ties broken on the signature text, so the list — and therefore the
      // title, which is built from the leader — is deterministic for a given input.
      List<LogSignature.Variant> ordered =
          variants.values().stream()
              .map(VariantAccumulator::toVariant)
              .sorted(
                  Comparator.comparingInt(LogSignature.Variant::occurrences)
                      .reversed()
                      .thenComparing(LogSignature.Variant::signature))
              .toList();
      LogSignature.Variant leader = ordered.getFirst();
      return new LogSignature(
          leader.signature(),
          first.severity(),
          first.container(),
          occurrences,
          firstSeen,
          lastSeen,
          leader.exampleLine(),
          sourceKey,
          ordered.stream().limit(LogSignature.MAX_VARIANTS).toList(),
          ordered.size());
    }
  }

  /** One distinct message template, while grouping. */
  private static final class VariantAccumulator {

    private final String signature;
    private final String exampleLine;
    private int count;

    private VariantAccumulator(final String signature, final String exampleLine) {
      this.signature = signature;
      this.exampleLine = exampleLine;
    }

    private LogSignature.Variant toVariant() {
      return new LogSignature.Variant(signature, count, exampleLine);
    }
  }
```

Two behaviour changes to be deliberate about while editing:

1. The old `Accumulator` narrowed `severity` to the most severe line in the group. That logic is now **dead** — severity is part of the key, so a group holds exactly one — and must be deleted, not kept "just in case". Use `first.severity()`.
2. `signature` and `exampleLine` now come from the *most frequent* variant rather than from the first line seen. That is deliberate: a ticket should lead with the dominant phrasing.

- [ ] **Step 5: Fix the existing tests that construct `LogSignature` positionally**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.logwatch.*'`

Several existing tests construct `LogSignature` directly. For each compilation error, append the three new arguments. A single-variant group is written:

```java
new LogSignature(
    "sig", Severity.ERROR, "backend", 3, T0, T0, "raw line",
    "logger:com.example.Thing", List.of(new LogSignature.Variant("sig", 3, "raw line")), 1);
```

- [ ] **Step 6: Run the full logwatch suite**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.logwatch.*'`

Expected: PASS. The six new tests pass, and every pre-existing `SignatureExtractorTest` test on `normalise` still passes untouched. **If one of those `normalise` tests fails you have edited `normalise` or `RULES`, which this task must not do** — revert that part.

- [ ] **Step 7: Run Checkstyle**

Run: `./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/logwatch/domain/LogSignature.java \
        software-factory/src/main/java/com/simonrowe/factory/logwatch/signature/SignatureExtractor.java \
        software-factory/src/test/java/com/simonrowe/factory/logwatch/
git commit -m "feat: group log findings by the code that emitted them"
```

---

### Task 3: Report the source and the variants, and file on the new key

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/logwatch/workflow/LogWatchReportRenderer.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/logwatch/workflow/LogWatchWorkflowImpl.java` (the `fileSignature` method, around line 218)
- Test: `software-factory/src/test/java/com/simonrowe/factory/logwatch/workflow/LogWatchReportRendererTest.java` (create it if absent; append to it if present)

**Interfaces:**
- Consumes: `LogSignature.sourceKey()`, `.variants()`, `.distinctVariants()` from Task 2.
- Produces: `LogWatchReportRenderer.title(LogSignature)` and `.body(LogSignature, Instant, Instant)` unchanged in signature; new `public static String shortSource(LogSignature)` returning the readable source, or `null` when the group is keyed on its line.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.logwatch.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.Severity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogWatchReportRendererTest {

  private static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");

  private static LogSignature signature(final String sourceKey, final int distinctVariants) {
    return new LogSignature(
        "sig one", Severity.ERROR, "simonrowe-dev-monorepo-backend-1", 9, T0, T0, "raw one",
        sourceKey,
        List.of(
            new LogSignature.Variant("sig one", 6, "raw one"),
            new LogSignature.Variant("sig two", 3, "raw two")),
        distinctVariants);
  }

  @Test
  @DisplayName("the title names the emitting class, abbreviated to its last segment")
  void titleNamesTheSource() {
    String title =
        LogWatchReportRenderer.title(
            signature("logger:com.embabel.agent.spi.validation.DefaultAgentValidationManager", 2));

    assertThat(title).contains("DefaultAgentValidationManager");
    assertThat(title).contains("simonrowe-dev-monorepo-backend-1");
    assertThat(title).doesNotContain("com.embabel.agent.spi");
  }

  @Test
  @DisplayName("a line-keyed group keeps the old title shape, with no empty parentheses")
  void titleOmitsTheSourceWhenThereIsNone() {
    assertThat(LogWatchReportRenderer.title(signature("line:ERROR: something broke", 1)))
        .doesNotContain("()");
  }

  @Test
  @DisplayName("a Temporal msg source is a sentence and is not abbreviated at its full stop")
  void sentenceSourcesAreNotAbbreviated() {
    assertThat(LogWatchReportRenderer.shortSource(signature("logger:Operation failed.", 1)))
        .isEqualTo("Operation failed.");
  }

  @Test
  @DisplayName("the body lists every variant with its count")
  void bodyListsVariants() {
    String body = LogWatchReportRenderer.body(signature("logger:com.example.Thing", 2), T0, T0);

    assertThat(body).contains("sig one");
    assertThat(body).contains("sig two");
    assertThat(body).contains("6");
    assertThat(body).contains("3");
  }

  @Test
  @DisplayName("the body says how many variants were withheld, so a cap is never silent")
  void bodyReportsTruncation() {
    String body = LogWatchReportRenderer.body(signature("logger:com.example.Thing", 11), T0, T0);

    assertThat(body).contains("9 further");
  }

  @Test
  @DisplayName("the body names the source key as what deduplicates the issue")
  void bodyNamesTheDedupKey() {
    String body = LogWatchReportRenderer.body(signature("logger:com.example.Thing", 2), T0, T0);

    assertThat(body).contains("com.example.Thing");
    assertThat(body).contains("deduplicates");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.logwatch.workflow.LogWatchReportRendererTest'`

Expected: FAIL — `titleNamesTheSource` fails because the title contains no source, and `bodyListsVariants` fails because the body has no variants section.

- [ ] **Step 3: Update the renderer**

Replace `title` and `body`, and add `shortSource` and `variantsSection`. Leave `sourceHealthTitle`, `sourceHealthBody`, `occurrenceDetail` and the private `summarise` unchanged.

```java
  /**
   * Title for one signature's issue.
   *
   * @param signature the grouped problem
   * @return a one-line title naming the container, the severity and the emitting source
   */
  public static String title(final LogSignature signature) {
    String source = shortSource(signature);
    String where =
        source == null ? signature.container() : signature.container() + " (" + source + ")";
    return signature.severity() + " in " + where + ": " + summarise(signature);
  }

  /**
   * The readable form of the group's source, for a title.
   *
   * @param signature the grouped problem
   * @return the last dot-separated segment of the emitting source, or null when the group is
   *     keyed on its normalised line because no source could be identified
   */
  public static String shortSource(final LogSignature signature) {
    String key = signature.sourceKey();
    if (key == null || !key.startsWith("logger:")) {
      return null;
    }
    String source = key.substring("logger:".length());
    int lastDot = source.lastIndexOf('.');
    // A Temporal msg is a sentence and often ends in a full stop, so abbreviating at the last dot
    // would leave nothing. Only abbreviate something that looks like a dotted identifier.
    if (lastDot < 0 || lastDot == source.length() - 1 || source.contains(" ")) {
      return source;
    }
    return source.substring(lastDot + 1);
  }

  /**
   * Body for one signature's issue.
   *
   * @param signature the grouped problem
   * @param windowStart the scanned window's start
   * @param windowEnd the scanned window's end
   * @return a Markdown description
   */
  public static String body(
      final LogSignature signature, final Instant windowStart, final Instant windowEnd) {
    return """
        **%s** in `%s`, seen **%d time(s)** between %s and %s.

        Window scanned: %s to %s.

        Emitted by (this is what deduplicates the issue):

        ```
        %s
        ```

        Example line:

        ```
        %s
        ```

        %s"""
        .formatted(
            signature.severity(),
            signature.container(),
            signature.occurrences(),
            signature.firstSeen(),
            signature.lastSeen(),
            windowStart,
            windowEnd,
            signature.sourceKey(),
            signature.exampleLine(),
            variantsSection(signature));
  }

  /**
   * The distinct message templates in the group.
   *
   * <p>This is what keeps grouping by emitting code honest. One logger can emit two genuinely
   * different faults, and without this the coarser key would hide the second inside the first.
   *
   * @param signature the grouped problem
   * @return a Markdown section, always non-empty
   */
  private static String variantsSection(final LogSignature signature) {
    StringBuilder section = new StringBuilder("Distinct messages seen (");
    section.append(signature.distinctVariants()).append(" in total):\n\n");
    for (LogSignature.Variant variant : signature.variants()) {
      section
          .append("* **")
          .append(variant.occurrences())
          .append("x** `")
          .append(variant.signature())
          .append("`\n");
    }
    int withheld = signature.distinctVariants() - signature.variants().size();
    if (withheld > 0) {
      section.append("\nand ").append(withheld).append(" further variant(s) not listed.\n");
    }
    return section.toString();
  }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.logwatch.workflow.LogWatchReportRendererTest'`

Expected: PASS, 6 tests.

- [ ] **Step 5: Change the fingerprint key parts (FR-004)**

In `LogWatchWorkflowImpl.fileSignature`, replace the key-parts argument and the comment above it:

```java
                // The source key, never the generated title and — since 046 — never the whole
                // normalised line either. Both are phrasings of the problem, and a phrasing that
                // varies files a second ticket: three phrasings from one Embabel logger became
                // SIM-13, SIM-24 and SIM-25 for one startup failure. Severity is explicit here
                // rather than left implicit inside the message text.
                List.of(
                    signature.container(),
                    signature.severity().name(),
                    signature.sourceKey()),
```

**Leave the source-health filing's key parts alone** (`SOURCE_HEALTH_KEY` plus the status name, around line 187). They are already structural and already deduplicate correctly.

- [ ] **Step 6: Run the full logwatch suite**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.logwatch.*'`

Expected: PASS. `LogWatchWorkflowTest` may assert on the filed key parts; update those assertions to the new triple.

- [ ] **Step 7: Run Checkstyle and commit**

```bash
./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest
git add software-factory/src/main/java/com/simonrowe/factory/logwatch/ \
        software-factory/src/test/java/com/simonrowe/factory/logwatch/
git commit -m "feat: fingerprint log findings on their emitting source"
```

---

### Task 4: `FilingMode`

Replace the `commentOnly` boolean with a four-valued mode. Behaviour-preserving: every existing producer maps onto a mode that does exactly what it does today.

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/domain/FilingMode.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/linear/domain/IssueFiling.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/linear/domain/FilingDecision.java`
- Modify (call site only): `software-factory/src/main/java/com/simonrowe/factory/cvefix/workflow/CveFixWorkflowImpl.java`, the clean-transition filing around line 98
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/domain/FilingModeTest.java`

**Interfaces:**
- Produces:
  - `enum FilingMode { OCCURRENCE, REFRESH, ROLLING, STATUS_UPDATE }` with `public boolean mayCreate()`, `public boolean rewritesBody()`, `public boolean reopensCompleted()`.
  - `IssueFiling`'s canonical constructor: `(String producer, List<String> keyParts, String title, String body, String occurrenceDetail, String occurrenceId, String workflowId, FilingMode mode)`.
  - `IssueFiling`'s seven-argument convenience constructor, unchanged in signature, now defaulting to `FilingMode.OCCURRENCE`.
  - `FilingDecision.UPDATED_EXISTING` and `FilingDecision.REOPENED_EXISTING`.

- [ ] **Step 1: Write the failing test**

Create `software-factory/src/test/java/com/simonrowe/factory/linear/domain/FilingModeTest.java`:

```java
package com.simonrowe.factory.linear.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FilingModeTest {

  @Test
  @DisplayName("a status update never creates an issue; every other mode may")
  void onlyStatusUpdateNeverCreates() {
    assertThat(FilingMode.STATUS_UPDATE.mayCreate()).isFalse();
    assertThat(FilingMode.OCCURRENCE.mayCreate()).isTrue();
    assertThat(FilingMode.REFRESH.mayCreate()).isTrue();
    assertThat(FilingMode.ROLLING.mayCreate()).isTrue();
  }

  @Test
  @DisplayName("refresh and rolling rewrite the body; occurrence and status update comment")
  void bodyRewritingModes() {
    assertThat(FilingMode.REFRESH.rewritesBody()).isTrue();
    assertThat(FilingMode.ROLLING.rewritesBody()).isTrue();
    assertThat(FilingMode.OCCURRENCE.rewritesBody()).isFalse();
    assertThat(FilingMode.STATUS_UPDATE.rewritesBody()).isFalse();
  }

  @Test
  @DisplayName("only rolling reopens a completed issue instead of filing a replacement")
  void onlyRollingReopens() {
    assertThat(FilingMode.ROLLING.reopensCompleted()).isTrue();
    assertThat(FilingMode.OCCURRENCE.reopensCompleted()).isFalse();
    assertThat(FilingMode.REFRESH.reopensCompleted()).isFalse();
    assertThat(FilingMode.STATUS_UPDATE.reopensCompleted()).isFalse();
  }

  @Test
  @DisplayName("the seven-argument constructor still means today's behaviour")
  void sevenArgumentConstructorDefaultsToOccurrence() {
    IssueFiling filing = new IssueFiling("deploy", List.of("a"), "t", "b", "d", "run-1", "wf-1");

    assertThat(filing.mode()).isEqualTo(FilingMode.OCCURRENCE);
  }

  @Test
  @DisplayName("a null mode is never allowed to mean 'no behaviour'")
  void nullModeDefaultsToOccurrence() {
    IssueFiling filing =
        new IssueFiling("deploy", List.of("a"), "t", "b", "d", "run-1", "wf-1", null);

    assertThat(filing.mode()).isEqualTo(FilingMode.OCCURRENCE);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.linear.domain.FilingModeTest'`

Expected: **compilation failure** — `cannot find symbol: class FilingMode`.

- [ ] **Step 3: Create `FilingMode`**

```java
package com.simonrowe.factory.linear.domain;

/**
 * How a producer wants its occurrences handled once an issue for the problem already exists.
 *
 * <p>One axis rather than three independent booleans, deliberately: {@code commentOnly} plus a
 * hypothetical {@code refreshBody} plus a hypothetical {@code rolling} admits combinations that
 * contradict each other, and there is no sensible behaviour for "comment only, and also rewrite
 * the body, and also reopen".
 *
 * <p>The mode is a property of the <em>producer</em>, not of the tracker's state. That is why
 * {@code FilingDecider} knows nothing about it and stays pure: it answers what Linear currently
 * says about a fingerprint, and the mode decides what to do about the answer.
 */
public enum FilingMode {

  /**
   * A new occurrence of a problem. Comments on an open issue, and files a linked replacement when
   * the only issue found was completed. This is what shipped in 039, and is what {@code deploy}
   * and {@code review-feedback} still want: a deploy that fails twice really is two events.
   */
  OCCURRENCE(true, false, false),

  /**
   * A restatement of a problem's current state. Rewrites the open issue's description and posts
   * no comment, so a nightly scan on a persistent problem stops adding a comment a night.
   *
   * <p>Used by {@code logwatch}. The occurrence history is not lost — it is in
   * {@code LinearIssueRecord.decisions} — but it is no longer surfaced in Linear, which is the
   * accepted cost: a problem recurring after its ticket has been moved out of Triage now produces
   * no notification.
   */
  REFRESH(true, true, false),

  /**
   * A long-lived rolling report whose title describes a standing question rather than an event,
   * such as {@code Current vulnerabilities in <repo>}. Behaves as {@link #REFRESH}, and
   * additionally reopens a completed issue into Triage rather than filing a replacement.
   *
   * <p>Used by {@code cvefix}. Without it, closing the report once caused the next scan to file
   * SIM-10 beside the completed SIM-9 — two tickets asking the same standing question.
   */
  ROLLING(true, true, true),

  /**
   * A status update about a known problem, not an occurrence of one. Comments on an open issue
   * using the occurrence detail verbatim, and otherwise does nothing at all.
   *
   * <p><strong>Never creates an issue.</strong> Used by {@code cvefix} to say a repository has
   * become clean; filing a ticket whose content is the <em>absence</em> of a problem would be
   * worse than silence. This is the {@code commentOnly} flag from 040, unchanged in behaviour.
   */
  STATUS_UPDATE(false, false, false);

  private final boolean mayCreate;
  private final boolean rewritesBody;
  private final boolean reopensCompleted;

  FilingMode(
      final boolean mayCreate, final boolean rewritesBody, final boolean reopensCompleted) {
    this.mayCreate = mayCreate;
    this.rewritesBody = rewritesBody;
    this.reopensCompleted = reopensCompleted;
  }

  /**
   * Whether this mode is allowed to create an issue that does not yet exist.
   *
   * @return false only for {@link #STATUS_UPDATE}
   */
  public boolean mayCreate() {
    return mayCreate;
  }

  /**
   * Whether a recurrence rewrites the existing issue's description instead of commenting on it.
   *
   * @return true for {@link #REFRESH} and {@link #ROLLING}
   */
  public boolean rewritesBody() {
    return rewritesBody;
  }

  /**
   * Whether a completed issue is reopened rather than replaced by a linked new one.
   *
   * @return true only for {@link #ROLLING}
   */
  public boolean reopensCompleted() {
    return reopensCompleted;
  }
}
```

- [ ] **Step 4: Replace `commentOnly` on `IssueFiling`**

Change the record's final component from `boolean commentOnly` to `FilingMode mode`. Replace its `@param commentOnly` Javadoc with a `@param mode` that points at `FilingMode` for the detail rather than restating it. Default a null mode in the compact constructor, beside the existing `keyParts` defaulting:

```java
  public IssueFiling {
    keyParts = keyParts == null ? List.of() : List.copyOf(keyParts);
    mode = mode == null ? FilingMode.OCCURRENCE : mode;
  }
```

and change the seven-argument convenience constructor's delegation from `false` to `FilingMode.OCCURRENCE`.

- [ ] **Step 5: Add the two new decisions**

Append to `FilingDecision`:

```java
  /**
   * An open issue already carried the fingerprint, and the producer asked for its description to
   * be rewritten to current state rather than for a comment. Distinct from
   * {@link #COMMENTED_EXISTING} because "we rewrote the ticket" and "we added a comment" are
   * different acts, and the audit trail in {@code linear_issues} must be able to say which.
   */
  UPDATED_EXISTING,
  /**
   * A completed issue carried the fingerprint and the producer files a rolling report, so it was
   * reopened into Triage and rewritten rather than replaced by a linked new issue. Distinct from
   * {@link #FILED_REGRESSION} for the same reason.
   */
  REOPENED_EXISTING
```

- [ ] **Step 6: Fix the one call site that used the eight-argument form**

`CveFixWorkflowImpl`'s clean-transition filing passes a trailing `true`. Change it to `FilingMode.STATUS_UPDATE` and add the import. This is behaviour-preserving: that is exactly what `commentOnly = true` meant.

- [ ] **Step 7: Run the whole module**

Run: `./gradlew :software-factory:test`

Expected: PASS, including `FilingModeTest`. Any test referring to `commentOnly()` needs updating to `mode()`; an `isTrue()` assertion becomes `isEqualTo(FilingMode.STATUS_UPDATE)`.

- [ ] **Step 8: Run Checkstyle and commit**

```bash
./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest
git add software-factory/src/main/java/com/simonrowe/factory/linear/domain/ \
        software-factory/src/main/java/com/simonrowe/factory/cvefix/ \
        software-factory/src/test/java/com/simonrowe/factory/linear/
git commit -m "refactor: replace the commentOnly flag with an explicit filing mode"
```

---

### Task 5: `LinearGateway.updateIssue`

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/linear/linear/LinearGateway.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/linear/LinearGatewayWriteTest.java` (existing — add to it)

**Interfaces:**
- Produces: `public void updateIssue(String issueId, String description, String stateId)` — rewrites the description, and moves the issue when `stateId` is non-null. Throws `LinearApiException` on any fault or when Linear reports the mutation unsuccessful, matching `createIssue` and `addComment`.

**Note:** the `issueUpdate` mutation shape below was exercised against the live Linear API on 2026-09-06 during the ticket cleanup, so the field names are verified rather than assumed.

- [ ] **Step 1: Write the failing test**

Append to the existing `LinearGatewayWriteTest` class. It already provides everything these tests need: a `gateway()` factory pointing at a local `HttpServer`, a `byOperation` map whose keys are matched against the request body to select a canned response, a `bodies` list capturing every request sent, and static imports for `assertThat` and `assertThatThrownBy`.

```java
  private static final String UPDATE_OK =
      "{\"data\":{\"issueUpdate\":{\"success\":true,\"issue\":"
          + "{\"id\":\"i1\",\"identifier\":\"SIM-1\",\"url\":\"https://linear.app/i/1\"}}}}";

  @Test
  @DisplayName("updateIssue rewrites the description and leaves the state alone when null")
  void updatesDescriptionOnly() {
    byOperation.put("issueUpdate", UPDATE_OK);

    gateway().updateIssue("i1", "new body", null);

    String sent = bodies.getLast();
    assertThat(sent).contains("issueUpdate");
    assertThat(sent).contains("new body");
    assertThat(sent).doesNotContain("stateId");
  }

  @Test
  @DisplayName("updateIssue sends stateId when one is given, which is how a reopen happens")
  void updatesStateWhenGiven() {
    byOperation.put("issueUpdate", UPDATE_OK);

    gateway().updateIssue("i1", "new body", "triage-state-id");

    assertThat(bodies.getLast()).contains("triage-state-id");
  }

  @Test
  @DisplayName("a mutation reporting success=false is a failure, not a silent no-op")
  void unsuccessfulUpdateThrows() {
    byOperation.put("issueUpdate", "{\"data\":{\"issueUpdate\":{\"success\":false}}}");

    assertThatThrownBy(() -> gateway().updateIssue("i1", "b", null))
        .isInstanceOf(LinearApiException.class);
  }
```

`bodies.getLast()` is the request just sent: `updateIssue` never calls `teamContext()`, so nothing else is on the wire during these three tests.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.linear.linear.LinearGatewayWriteTest'`

Expected: **compilation failure** — `cannot find symbol: method updateIssue`.

- [ ] **Step 3: Implement it**

Add the constant beside the other mutation constants:

```java
  private static final String UPDATE_ISSUE =
      "mutation($id:String!,$input:IssueUpdateInput!){issueUpdate(id:$id,input:$input)"
          + "{success issue{id identifier url}}}";
```

and the method beside `addComment`:

```java
  /**
   * Rewrites an existing issue's description, and optionally moves it to another state.
   *
   * <p>This is what {@link com.simonrowe.factory.linear.domain.FilingMode#REFRESH} and
   * {@link com.simonrowe.factory.linear.domain.FilingMode#ROLLING} act through. Unlike
   * {@link #relateIssues} it is <strong>not</strong> best-effort: a failed update leaves the
   * ticket describing an old occurrence with nothing anywhere saying so, which is the exact
   * silent staleness those modes exist to remove.
   *
   * @param issueId the Linear issue UUID
   * @param description the new description, in Markdown
   * @param stateId the workflow state to move the issue to, or null to leave the state alone
   * @throws LinearApiException on any API fault, or when Linear reports the mutation unsuccessful
   */
  public void updateIssue(final String issueId, final String description, final String stateId) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put("description", description);
    if (stateId != null) {
      input.put("stateId", stateId);
    }
    JsonNode result =
        execute(UPDATE_ISSUE, Map.of("id", issueId, VAR_INPUT, input)).path("issueUpdate");
    if (!result.path(FIELD_SUCCESS).asBoolean(false)) {
      throw new LinearApiException("Linear issueUpdate reported failure", false);
    }
  }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.linear.linear.LinearGatewayWriteTest'`

Expected: PASS. **This is one of the known-flaky HTTP-stub classes** — if it fails on a connection or port error rather than an assertion, re-run it isolated three times before investigating.

- [ ] **Step 5: Run Checkstyle and commit**

```bash
./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest
git add software-factory/src/main/java/com/simonrowe/factory/linear/linear/LinearGateway.java \
        software-factory/src/test/java/com/simonrowe/factory/linear/linear/LinearGatewayWriteTest.java
git commit -m "feat: let the Linear gateway update an issue in place"
```

---

### Task 6: `IssueFiler` honours the mode

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/linear/service/IssueFiler.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/service/IssueFilerTest.java` (existing — add to it)

**Interfaces:**
- Consumes: `FilingMode` and the two new `FilingDecision` values (Task 4), `LinearGateway.updateIssue` (Task 5).
- Produces: no new public API. `IssueFiler.file(IssueFiling)` returns the new decisions for the new modes.

- [ ] **Step 1: Write the failing test**

Add to `IssueFilerTest`, reusing its existing `issue(...)`, `filer()`, `records` and `properties` members. Add the imports `com.simonrowe.factory.linear.domain.FilingMode` and `java.util.Map`.

```java
  private static IssueFiling filing(final FilingMode mode) {
    return new IssueFiling(
        "logwatch",
        List.of("backend", "ERROR", "logger:com.example.Thing"),
        "ERROR in backend (Thing): boom",
        "fresh body describing the current state",
        "scan run-2 saw this 4 time(s)",
        "run-2",
        "logwatch-1",
        mode);
  }

  @Test
  @DisplayName("REFRESH rewrites the open issue's body and posts no comment")
  void refreshRewritesTheBody() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.TRIAGE)));

    FiledIssue filed = filer().file(filing(FilingMode.REFRESH));

    assertThat(filed.decision()).isEqualTo(FilingDecision.UPDATED_EXISTING);
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-1");
    verify(gateway).updateIssue("i1", "fresh body describing the current state", null);
    verify(gateway, never()).addComment(anyString(), anyString());
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
  }

  @Test
  @DisplayName("ROLLING reopens a completed issue into Triage rather than filing a replacement")
  void rollingReopensACompletedIssue() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.COMPLETED)));
    when(gateway.teamContext())
        .thenReturn(new LinearGateway.TeamContext("t1", "triage-state", Map.of()));

    FiledIssue filed = filer().file(filing(FilingMode.ROLLING));

    assertThat(filed.decision()).isEqualTo(FilingDecision.REOPENED_EXISTING);
    verify(gateway).updateIssue("i1", "fresh body describing the current state", "triage-state");
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
  }

  @Test
  @DisplayName("REFRESH still files a linked replacement for a completed issue")
  void refreshDoesNotReopen() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.COMPLETED)));
    when(gateway.createIssue(anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(new LinearGateway.CreatedIssue("i9", "SIM-9", "https://linear.app/i/9"));

    assertThat(filer().file(filing(FilingMode.REFRESH)).decision())
        .isEqualTo(FilingDecision.FILED_REGRESSION);
  }

  @Test
  @DisplayName("a cancelled issue suppresses every mode, including the rolling one")
  void cancellationStillSuppressesEveryMode() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.CANCELED)));

    for (FilingMode mode : FilingMode.values()) {
      FiledIssue filed = filer().file(filing(mode));
      assertThat(filed.decision()).isEqualTo(FilingDecision.SUPPRESSED);
      assertThat(filed.issueUrl()).isNull();
    }
    verify(gateway, never()).updateIssue(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("STATUS_UPDATE still never creates, and comments without the recurrence prefix")
  void statusUpdateIsUnchanged() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.TRIAGE)));

    FiledIssue filed = filer().file(filing(FilingMode.STATUS_UPDATE));

    assertThat(filed.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    verify(gateway).addComment("i1", "scan run-2 saw this 4 time(s)");
    verify(gateway, never()).updateIssue(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("a dry run reports the decision a real run would take, for every mode")
  void dryRunReportsTheRealDecisionForEveryMode() {
    properties = new LinearProperties(true, "k", null, "SIM", null, true, null, null);
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.TRIAGE)));

    assertThat(filer().file(filing(FilingMode.REFRESH)).decision())
        .isEqualTo(FilingDecision.UPDATED_EXISTING);
    assertThat(filer().file(filing(FilingMode.ROLLING)).decision())
        .isEqualTo(FilingDecision.UPDATED_EXISTING);
    verify(gateway, never()).updateIssue(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("a half-completed REFRESH filing repairs by rewriting, not by commenting")
  void pendingAttachmentRepairHonoursTheMode() {
    LinearIssueRecord pending =
        LinearIssueRecord.first("fp", "logwatch", List.of("a"), NOW)
            .withPendingAttachment("i1", "SIM-1", "https://linear.app/i/1");
    when(records.findById(anyString())).thenReturn(Optional.of(pending));

    FiledIssue filed = filer().file(filing(FilingMode.REFRESH));

    assertThat(filed.decision()).isEqualTo(FilingDecision.UPDATED_EXISTING);
    verify(gateway).attachFingerprint(eq("i1"), anyString());
    verify(gateway).updateIssue("i1", "fresh body describing the current state", null);
    verify(gateway, never()).addComment(anyString(), anyString());
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.linear.service.IssueFilerTest'`

Expected: FAIL — `refreshRewritesTheBody` gets `COMMENTED_EXISTING`.

- [ ] **Step 3: Replace `commentOnlySafe` with `applyMode`**

```java
  /**
   * Maps the decision {@link FilingDecider} reached onto the one this producer's mode asks for.
   *
   * <p>This is the only place a mode is interpreted. {@code FilingDecider} answers what Linear
   * currently says about the fingerprint and stays pure; the mode decides what to do about that
   * answer.
   *
   * @param decided the decision {@link FilingDecider} reached
   * @param mode the producer's filing mode
   * @return the decision the sink will honour
   */
  private static FilingDecision applyMode(final FilingDecision decided, final FilingMode mode) {
    return switch (decided) {
      case COMMENTED_EXISTING ->
          mode.rewritesBody()
              ? FilingDecision.UPDATED_EXISTING
              : FilingDecision.COMMENTED_EXISTING;
      case FILED_REGRESSION -> {
        if (mode.reopensCompleted()) {
          yield FilingDecision.REOPENED_EXISTING;
        }
        yield mode.mayCreate() ? FilingDecision.FILED_REGRESSION : creationRefused();
      }
      case FILED_NEW -> mode.mayCreate() ? FilingDecision.FILED_NEW : creationRefused();
      default -> decided;
    };
  }

  /**
   * What a mode that must never create an issue does when the decider says one is needed.
   *
   * <p>Reducing to {@code SKIPPED_NO_ISSUE} rather than creating is deliberate: no open issue
   * exists to comment on, and creating one — or filing a "recurrence" whose actual content is the
   * ABSENCE of the problem — is worse than silence. Unchanged from 040.
   *
   * @return {@link FilingDecision#SKIPPED_NO_ISSUE}
   */
  private static FilingDecision creationRefused() {
    return FilingDecision.SKIPPED_NO_ISSUE;
  }
```

**Switch on the decision, not on the mode.** The earlier draft of this plan switched on the mode and hard-coded each arm, which left `mayCreate()` and `reopensCompleted()` defined but never called — two unused public methods that SonarQube would flag, and, worse, a second copy of each rule sitting next to the enum that claims to own it. This shape has exactly one statement of each rule, in `FilingMode`.

Verify it against the FR-005 table by hand before moving on; all four modes are covered by three predicate reads:

| decided | OCCURRENCE (T,F,F) | REFRESH (T,T,F) | ROLLING (T,T,T) | STATUS_UPDATE (F,F,F) |
|---|---|---|---|---|
| `COMMENTED_EXISTING` | `COMMENTED_EXISTING` | `UPDATED_EXISTING` | `UPDATED_EXISTING` | `COMMENTED_EXISTING` |
| `FILED_REGRESSION` | `FILED_REGRESSION` | `FILED_REGRESSION` | `REOPENED_EXISTING` | `SKIPPED_NO_ISSUE` |
| `FILED_NEW` | `FILED_NEW` | `FILED_NEW` | `FILED_NEW` | `SKIPPED_NO_ISSUE` |
| `SUPPRESSED` | `SUPPRESSED` | `SUPPRESSED` | `SUPPRESSED` | `SUPPRESSED` |

Change the call site in `file()` from `commentOnlySafe(outcome.decision(), filing)` to `applyMode(outcome.decision(), filing.mode())`.

- [ ] **Step 4: Add the two action arms**

In `file()`'s `switch (effective)`, add before `default`:

```java
      case UPDATED_EXISTING -> {
        gateway.updateIssue(outcome.subject().id(), filing.body(), null);
        record =
            record.withIssue(
                outcome.subject().id(), outcome.subject().identifier(), outcome.subject().url());
      }
      case REOPENED_EXISTING -> {
        // The Triage state, not "whatever state it was in before": Linear does not record that,
        // and Triage is where a newly filed ticket lands — so a reopened rolling report re-enters
        // the queue a human actually watches rather than appearing somewhere nobody looks.
        gateway.updateIssue(
            outcome.subject().id(), filing.body(), gateway.teamContext().triageStateId());
        record =
            record.withIssue(
                outcome.subject().id(), outcome.subject().identifier(), outcome.subject().url());
      }
```

**Leave `reported(...)` alone.** `UPDATED_EXISTING` and `REOPENED_EXISTING` are real acts on a real ticket and must report the issue; only `SUPPRESSED` and `SKIPPED_NO_ISSUE` clear the issue fields.

- [ ] **Step 5: Make the pending-attachment repair honour the mode**

In `repairPendingAttachment`, replace the fixed comment-and-report with:

```java
    FilingDecision decision =
        filing.mode().rewritesBody()
            ? FilingDecision.UPDATED_EXISTING
            : FilingDecision.COMMENTED_EXISTING;
    if (properties.dryRun()) {
      return finish(record, decision, filing, now, null, fingerprint);
    }
    gateway.attachFingerprint(record.issueId(), fingerprintUrl);
    if (filing.mode().rewritesBody()) {
      gateway.updateIssue(record.issueId(), filing.body(), null);
    } else {
      gateway.addComment(record.issueId(), occurrenceComment(filing));
    }
    LinearIssueRecord repaired = record.withAttachmentWritten();
    return finish(repaired, decision, filing, now, null, fingerprint);
```

Note the dry-run early return moves below the `decision` computation and now uses it, rather than the hard-coded `COMMENTED_EXISTING` it used before.

- [ ] **Step 6: Update `occurrenceComment`**

```java
  private static String occurrenceComment(final IssueFiling filing) {
    // A status update is not a recurrence, so it must not be announced as one: "Seen again: no
    // current vulnerabilities" says the opposite of what it means.
    return filing.mode() == FilingMode.STATUS_UPDATE
        ? filing.occurrenceDetail()
        : "Seen again: " + filing.occurrenceDetail();
  }
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.linear.*'`

Expected: PASS — the seven new tests plus every pre-existing `IssueFilerTest` test, unchanged. **If a pre-existing test now fails, `OCCURRENCE`'s arm of `applyMode` has stopped being the identity**, which it must be.

- [ ] **Step 8: Run Checkstyle and commit**

```bash
./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest
git add software-factory/src/main/java/com/simonrowe/factory/linear/service/IssueFiler.java \
        software-factory/src/test/java/com/simonrowe/factory/linear/service/IssueFilerTest.java
git commit -m "feat: update an existing Linear issue in place instead of only commenting"
```

---

### Task 7: Producers opt in, and the cvefix counters (FR-009)

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/logwatch/workflow/LogWatchWorkflowImpl.java` (both `IssueFiling` constructions)
- Modify: `software-factory/src/main/java/com/simonrowe/factory/cvefix/workflow/CveFixWorkflowImpl.java` (the dirty-report filing, and `finish`'s counters)
- Test: `software-factory/src/test/java/com/simonrowe/factory/cvefix/workflow/CveFixWorkflowTest.java` (existing)

**Interfaces:**
- Consumes: `FilingMode` from Task 4, `FilingDecision.UPDATED_EXISTING` / `.REOPENED_EXISTING` from Task 4.
- Produces: no new API.

**`deploy` and `review-feedback` are deliberately not touched.** They use the seven-argument constructor, which now means `OCCURRENCE`, which is byte-for-byte their existing behaviour.

- [ ] **Step 1: Write the failing test for the counters**

FR-009 is the trap in this whole change. `CveFixWorkflowImpl.finish` counts `COMMENTED_EXISTING` to report "updated", and once cvefix files as `ROLLING` the sink returns `UPDATED_EXISTING` instead — so the counter reports zero on a run that did exactly what it was asked to, with a green build and no error anywhere. Pin it:

```java
  @Test
  @DisplayName("FR-009: a rolling update counts as updated, not as nothing")
  void updatedCounterIncludesTheRollingDecisions() {
    List<FiledIssue> outcomes =
        List.of(
            new FiledIssue(FilingDecision.UPDATED_EXISTING, "i1", "SIM-1", "url", "fp"),
            new FiledIssue(FilingDecision.COMMENTED_EXISTING, "i2", "SIM-2", "url", "fp"));

    assertThat(runWorkflowProducing(outcomes).updated()).isEqualTo(2);
  }

  @Test
  @DisplayName("FR-009: a reopened rolling report counts as a regression, which is what it is")
  void regressedCounterIncludesAReopen() {
    List<FiledIssue> outcomes =
        List.of(new FiledIssue(FilingDecision.REOPENED_EXISTING, "i1", "SIM-1", "url", "fp"));

    assertThat(runWorkflowProducing(outcomes).regressed()).isEqualTo(1);
  }
```

`CveFixWorkflowTest` exists — read it and adapt `runWorkflowProducing` to however it already drives the workflow and stubs `linear.fileIssue`, asserting on the returned `CveFixResult`'s `updated()` and `regressed()` accessors.

**If driving the workflow to produce a chosen list of outcomes turns out to need more scaffolding than these two assertions justify**, extract the four counter computations out of `finish` into a package-private `static CveFixCounts countsOf(List<FiledIssue> outcomes)` on `CveFixWorkflowImpl` — a record of `(filed, updated, suppressed, regressed)` — and test that directly. The arithmetic is the thing under test; the Temporal environment is not.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.cvefix.*'`

Expected: FAIL — `updated` is 1, not 2.

- [ ] **Step 3: Fix the counters**

In `CveFixWorkflowImpl.finish`:

```java
    int filed = count(outcomes, FilingDecision.FILED_NEW);
    // UPDATED_EXISTING is what ROLLING returns where OCCURRENCE would have returned
    // COMMENTED_EXISTING, and REOPENED_EXISTING is what it returns where OCCURRENCE would have
    // returned FILED_REGRESSION. Each new value is counted with the old one it replaced, so both
    // fields keep meaning what their names say.
    int updated =
        count(outcomes, FilingDecision.COMMENTED_EXISTING)
            + count(outcomes, FilingDecision.UPDATED_EXISTING);
    int suppressed = count(outcomes, FilingDecision.SUPPRESSED);
    int regressed =
        count(outcomes, FilingDecision.FILED_REGRESSION)
            + count(outcomes, FilingDecision.REOPENED_EXISTING);
```

- [ ] **Step 4: Opt the producers in**

- `CveFixWorkflowImpl`, the dirty consolidated report (the filing whose body is `report`): add `FilingMode.ROLLING` as the eighth argument. Its clean-transition sibling already became `FilingMode.STATUS_UPDATE` in Task 4.
- `LogWatchWorkflowImpl`: add `FilingMode.REFRESH` as the eighth argument to **both** filings — the per-signature one in `fileSignature`, and the source-health one. The source-health body carries the scanned window, so it goes stale in exactly the same way a signature body does.

- [ ] **Step 5: Run the whole module**

Run: `./gradlew :software-factory:test`

Expected: PASS, all tests.

- [ ] **Step 6: Run Checkstyle and commit**

```bash
./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest
git add software-factory/src/
git commit -m "feat: file log findings as refreshes and the CVE report as a rolling ticket"
```

---

### Task 8: Documentation

**Files:**
- Modify: `docs/runbooks/logwatch.md`
- Modify: `docs/runbooks/linear.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update `docs/runbooks/logwatch.md`**

Add a section, "Grouping: what makes two lines the same problem", covering:

- The `(container, severity, discriminatedSource)` key.
- The six formats `SourceKeyExtractor` handles, and that an unrecognised format falls back to the normalised line — which is the pre-046 behaviour, so nothing regresses.
- That variants are listed in the body, capped at five, with the true total always reported.
- The two deliberate limits: SIM-11 versus SIM-13 (one incident, two loggers) and SIM-19 versus SIM-20 (`MailHealthIndicator` and `HealthEndpointSupport`).
- The operational fact: **changing this key orphaned every pre-046 logwatch fingerprint**, so the first scan after deploy re-files each live problem once, and any pre-046 cancellation stopped suppressing anything.

- [ ] **Step 2: Update `docs/runbooks/linear.md`**

Add the `FilingMode` table from FR-005, and next to it the three facts an operator needs:

- `REFRESH` and `ROLLING` post **no comment**, so a recurring problem whose ticket has been moved out of Triage produces no notification at all. The history is in `linear_issues.decisions`.
- `ROLLING` reopens a completed ticket **into Triage**, because Linear does not record what state a ticket was in before it was completed.
- Cancelling a ticket still suppresses its fingerprint in every mode. That is the decline gesture and it is unchanged.

- [ ] **Step 3: Add a `CLAUDE.md` "Recent Changes" entry**

Add at the top of the Recent Changes list, in the established voice. It must carry:

- The diagnosis: the sink's dedup was never broken; `logwatch`'s fingerprint key was `(container, whole-normalised-line)` and free text inside a message forked it. Name SIM-13/24/25 and SIM-16/23.
- **`SourceKeyExtractor`'s Temporal handler uses `msg`, not `logging-call-at`** — the latter carries a source line number, so a Temporal upgrade would fork every ticket it emits.
- **The `logger:` / `line:` prefix is load-bearing** — without it a source key equal to a normalised line silently merges two unrelated groups.
- **FR-009**: `CveFixWorkflowImpl`'s counters had to move in the same commit, or a correct run reports zero updates with no error anywhere.
- **The one-time re-file was chosen over migrating fingerprints**, and the fourteen tickets were cancelled first so the nightly scan stayed quiet in the interval.
- **`REFRESH` posts no comment**, with the notification consequence stated.
- The `factory:logwatch` and `factory:feedback` labels were missing in Linear and were created by hand on 2026-09-06. `teamContext()` caches labels positively for the process lifetime, so the container needs restarting before a new label is picked up.

**Do not run `.specify/scripts/bash/update-agent-context.sh`** — `CLAUDE.md` records that it fails with `grep: repetition-operator operand invalid` and silently strips the lead line from eight existing entries.

- [ ] **Step 4: Commit**

```bash
git add docs/runbooks/logwatch.md docs/runbooks/linear.md CLAUDE.md
git commit -m "docs: record the source-key grouping and the Linear filing modes"
```

---

### Task 9: Full verification and pull request

- [ ] **Step 1: Run everything**

```bash
./gradlew :software-factory:test :software-factory:checkstyleMain :software-factory:checkstyleTest
./scripts/test/run-tests.sh
```

Expected: PASS. `run-tests.sh` is inside the required `Software Factory Build & Test` check, so a failure here fails CI.

Record the actual test count and compare it with the pre-change baseline of **582** `software-factory` tests. It must go **up**, by roughly the 27 tests this plan adds. If it went down, a test was deleted rather than updated.

- [ ] **Step 2: Confirm the deliberate limits still hold**

Read the test report and confirm that `oneIncidentFromTwoLoggersStaysTwoSources` and `twoAlloyComponentsStayTwoSources` are both **passing**, not skipped. They pin the two places this change deliberately does not merge, so that a future "improvement" merging them fails the build instead of shipping.

- [ ] **Step 3: Open the pull request**

**Use the `pr-review-loop` skill.** `CLAUDE.md` is explicit that creating a pull request in this org means using that skill, which owns the whole sequence — pre-flight, open, wait on all three signals (CI, the reviewer bot, SonarQube Cloud), triage, push, re-wait. Do not improvise it.

The PR description must state that **the first `logwatch` scan after deploy will re-file every live problem once**, so that a reviewer or operator seeing roughly ten new tickets the next morning knows it is the expected changeover and not a regression.

---

## Post-merge operator actions

Not part of any task, because they happen against production after the deploy:

1. **Restart `software-factory`** so `teamContext()` picks up the `factory:logwatch` and `factory:feedback` labels created on 2026-09-06. The cache is positive-only and lives for the process lifetime, so without a restart the new tickets file unlabelled exactly as the old ones did.
2. **Expect roughly ten new logwatch tickets** on the first scan after deploy, correctly grouped. Triage them normally.
3. **Check one ticket's body** for the "Distinct messages seen" section. Its absence means the renderer change did not ship even though the grouping did.
