# Linear Issue Sink Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the software factory one place to file a finding a human needs to see — Linear — filing exactly once per distinct problem, and knowing when a human has already declined it.

**Architecture:** A new module, `com.simonrowe.factory.linear`, is a *sink*: no webhook, no schedule, no trigger of its own. It exposes one Temporal activity, `fileIssue`, on a new `linear` task queue that only the `software-factory` container polls — so the `deployer`, which holds the Docker socket, never receives a Linear credential. Identity is a deterministic fingerprint stamped onto the Linear issue as an attachment; issue *state* is always read back from Linear, and Mongo holds only an audit trail.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Temporal Java SDK (`io.temporal:temporal-spring-boot-starter`), Spring Data MongoDB, `java.net.http.HttpClient` against Linear's GraphQL API. **No new dependencies in `software-factory/build.gradle.kts`.**

**Approved design:** `docs/superpowers/specs/2026-08-27-linear-issue-sink-design.md`. Read it before Task 1.

## Global Constraints

- **No new Gradle dependencies.** HTTP is `java.net.http.HttpClient` (the `DependencyTrackClient` pattern); HTTP stubs in tests are the JDK's `com.sun.net.httpserver.HttpServer` (the `DependencyTrackClientTest` pattern).
- **Google Java Style, enforced by Checkstyle.** Every public type and public method needs Javadoc, including `@param` and `@return`. Run `./gradlew :software-factory:check` before every commit.
- **Every new bean is off by default.** `factory.linear.enabled` defaults to `false`. A disabled module must make **no** Mongo or network call at startup — an unreachable dependency must never fail the application context, because that context also serves the GitHub webhook receiver and the `code-review` worker.
- **Class-level `@ConditionalOnProperty` is evaluated by the component scanner.** Declaring the same class through an explicit `@Bean` method registers it unconditionally and silently ignores the annotation. Registration tests therefore component-scan — see `DeployWorkerRegistrationTest`.
- **`@WorkflowImpl` classes are instantiated by the Temporal SDK, not Spring.** They cannot inject configuration. Every configured value a workflow needs arrives on its request record.
- **Mongock is backend-only.** The factory's own database manages indexes in code, via an `ApplicationRunner` — see `CveFixIndexInitializer`.
- **`software-factory`'s JaCoCo is report-only with no floor.** Coverage is a judgement call, not a gate. Do not treat CI as proof the tests are adequate.
- Fingerprint version literal: `v1`. Bumping it orphans every existing ticket.
- Attachment URL shape: `https://factory.simonrowe.dev/fingerprint/<fingerprint>` — synthetic, deliberately non-resolving.
- Producer keys, exactly: `deploy`, `cvefix`. Labels, exactly: `factory:deploy`, `factory:cvefix`.

## File Structure

**New — `software-factory/src/main/java/com/simonrowe/factory/linear/`**

| File | Responsibility |
| --- | --- |
| `config/LinearProperties.java` | Bound `factory.linear` configuration, with defaults in the record constructor |
| `config/LinearTaskQueues.java` | The `linear` task queue name constant |
| `domain/Fingerprint.java` | Deterministic fingerprint and its attachment URL. Pure |
| `domain/IssueStateType.java` | Linear's `state.type` values plus the open/canceled/completed precedence |
| `domain/TrackedIssue.java` | An issue found by fingerprint: id, identifier, url, state type, createdAt |
| `domain/FilingDecision.java` | `FILED_NEW`, `COMMENTED_EXISTING`, `SUPPRESSED`, `FILED_REGRESSION` |
| `domain/IssueFiling.java` | The filing request: producer, key parts, title, body, occurrence detail, occurrenceId |
| `domain/FiledIssue.java` | The filing result: decision, issue identifier, issue url |
| `service/FilingDecider.java` | Pure: a set of `TrackedIssue` plus precedence → a `FilingDecision`. **The feature** |
| `service/IssueFiler.java` | Orchestration: fingerprint → look up → decide → mutate Linear → write the audit record |
| `linear/LinearGateway.java` | Linear GraphQL over `HttpClient`: lookup, create, attach, comment, link, team resolution |
| `linear/LinearApiException.java` | Carries whether the fault is retryable |
| `persistence/LinearIssueRecord.java` | `linear_issues` document, `_id` = fingerprint |
| `persistence/LinearIssueRepository.java` | Spring Data repository |
| `persistence/LinearIssueDecision.java` | One entry in the capped decision log |
| `persistence/LinearIndexInitializer.java` | `{producer, lastSeenAt}` index, gated on the flag |
| `workflow/LinearActivities.java` | The activity interface |
| `workflow/LinearActivitiesImpl.java` | `@ActivityImpl(taskQueues = LINEAR)`, gated on the flag |

**Modified**

| File | Change |
| --- | --- |
| `software-factory/src/main/resources/application.yml` | `factory.linear` block; a comment recording that `linear` is the first activity-only task queue (**no** `workflow-packages` entry — see research item 7) |
| `deploy/domain/DeployRequest.java` | `+ boolean linearFilingEnabled` |
| `deploy/api/DeployWorkflowService.java` | Sets that flag from `LinearProperties` |
| `deploy/workflow/DeployWorkflowImpl.java` | Files to Linear, then reports; passes the issue URL into the report |
| `deploy/workflow/DeployActivities.java` | `report` gains the Linear URL parameter; `Report` loses `issueUrl` |
| `deploy/workflow/DeployActivitiesImpl.java` | Stops creating a GitHub issue |
| `deploy/github/DeployReportGateway.java` | Loses issue creation |
| `deploy/github/DeployReportRenderer.java` | Commit comment carries the Linear URL |
| `cvefix/domain/CveFixRequest.java` | `+ boolean linearFilingEnabled` |
| `cvefix/schedule/CveFixScheduleInitializer.java` | Sets that flag from `LinearProperties` |
| `cvefix/workflow/CveFixActivities.java` | `recordUnfixable` returns the components it newly recorded |
| `cvefix/workflow/CveFixActivitiesImpl.java` | Returns that list |
| `cvefix/workflow/CveFixWorkflowImpl.java` | Files one issue per newly-recorded component |
| `docker-compose.prod.yml` | Linear env on `software-factory` **only** |
| `docs/runbooks/software-factory.md` | Fix the stale "hosts only `codereview`" opening; add the module table |
| `docs/runbooks/software-factory-manual-actions.md` | The Linear human prerequisites |
| `docs/runbooks/linear.md` | New runbook |
| `CLAUDE.md` | Recent Changes entry for this feature, plus the missing `feedback` entry |

---

### Task 1: Research spike — settle the API questions before writing code

Two of these can invalidate the design. Nothing else in this plan should be written until this task is committed.

**Preconditions a human must satisfy first** (they are also Task 12's runbook content):

1. A Linear team exists, and **Triage is enabled on it** (Linear → team settings → Triage). The suppression design depends on it.
2. Labels `factory:deploy` and `factory:cvefix` exist on that team.
3. A Linear personal API key is exported locally as `LINEAR_API_KEY`, and the team key (e.g. `SIM`) as `LINEAR_TEAM_KEY`.

**Files:**
- Create: `specs/039-linear-issue-sink/research.md`

- [ ] **Step 1: Resolve the team and capture its workflow states**

```bash
q() { curl -s https://api.linear.app/graphql -H "Authorization: $LINEAR_API_KEY" \
  -H 'Content-Type: application/json' -d "$1"; }

q '{"query":"query($k:String!){teams(filter:{key:{eq:$k}}){nodes{id key name
     states{nodes{id name type position}}}}}","variables":{"k":"'"$LINEAR_TEAM_KEY"'"}}' | jq .
```

Record the team UUID and every state's `id`, `name` and `type`. **Answers research item 5:** confirm a state of `type: "triage"` exists and note its id. If no `triage` state is returned, Triage is not enabled on the team — stop and fix the precondition.

- [ ] **Step 2: Create a throwaway issue in Triage and attach a fingerprint**

```bash
q '{"query":"mutation($t:String!,$s:String!,$title:String!){issueCreate(input:{
     teamId:$t,stateId:$s,title:$title,priority:3}){success issue{id identifier url}}}",
   "variables":{"t":"<TEAM_UUID>","s":"<TRIAGE_STATE_ID>","title":"factory research probe A"}}' | jq .

q '{"query":"mutation($i:String!,$u:String!){attachmentCreate(input:{
     issueId:$i,url:$u,title:\"fingerprint\"}){success attachment{id url}}}",
   "variables":{"i":"<ISSUE_A_ID>","u":"https://factory.simonrowe.dev/fingerprint/probe"}}' | jq .
```

**Answers research items 3 and 4:** whether `attachmentCreate` accepts a URL on a host that does not resolve, and whether a personal API key may call `issueCreate` and `attachmentCreate`.

- [ ] **Step 3: Attach the same URL to a second issue**

Create `factory research probe B` exactly as in Step 2, then run the same `attachmentCreate` against issue B with the **same** URL.

**Answers research item 2 — this gates the regression path.** If the second attachment is rejected, the regression path cannot reuse one URL: record that the design must switch to the item-1 fallback (Mongo as index, state fetched per issue id).

- [ ] **Step 4: Look up by URL, then cancel one issue and look up again**

```bash
q '{"query":"query($u:String!){attachmentsForURL(url:$u){nodes{id issue{
     id identifier url createdAt state{name type}}}}}",
   "variables":{"u":"https://factory.simonrowe.dev/fingerprint/probe"}}' | jq .
```

Then move issue A to a `canceled`-type state (`issueUpdate` with that `stateId`) and re-run the query.

**Answers research item 1 — the one that can invalidate the whole design.** The cancelled issue MUST still be returned. If `attachmentsForURL` filters it out, suppression silently stops working and declined bugs are re-filed forever: **stop, and revise the design to the fallback before continuing.**

- [ ] **Step 5: Confirm the priority scale and the comment mutation**

```bash
q '{"query":"mutation($i:String!,$b:String!){commentCreate(input:{issueId:$i,body:$b}){
     success comment{id}}}","variables":{"i":"<ISSUE_B_ID>","b":"occurrence probe"}}' | jq .
q '{"query":"{issuePriorityValues{priority label}}"}' | jq .
```

**Answers research item 6.** Record the exact integer→label mapping rather than assuming `0 none, 1 urgent, 2 high, 3 normal, 4 low`.

- [ ] **Step 6: Settle the activity-only task queue question**

This one is about Temporal, not Linear, and it decides Task 9's shape. Read `software-factory/src/main/resources/application.yml` — the `workers-auto-discovery.workflow-packages` comments — then determine from the `temporal-spring-boot-starter` version on the classpath whether `@ActivityImpl(taskQueues = "linear")` alone causes a worker to be created for a queue that **no** `@WorkflowImpl` names.

```bash
./gradlew :software-factory:dependencies --configuration runtimeClasspath | grep -i temporal
```

Read `WorkersTemplate` in that version's sources. Record the answer. If activity-only queues are not created, Task 9 additionally needs a no-op `FileIssueWorkflow` on the `linear` queue purely to make the queue exist.

- [ ] **Step 7: Delete the probe issues and write up**

Delete both probe issues in Linear. Write `specs/039-linear-issue-sink/research.md` with one section per item: the question, the exact query run, the raw answer, and the decision it forces. Where an answer contradicts the design, state which task changes and how.

- [ ] **Step 8: Commit**

```bash
git add specs/039-linear-issue-sink/research.md
git commit -m "docs: settle the Linear API and Temporal questions for the issue sink"
```

---

### Task 2: Configuration properties and the feature flag

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/config/LinearProperties.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/config/LinearTaskQueues.java`
- Modify: `software-factory/src/main/resources/application.yml`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/config/LinearPropertiesTest.java`

**Interfaces:**
- Produces: `LinearProperties(boolean enabled, String apiKey, String apiBaseUrl, String teamKey, String fingerprintBaseUrl, boolean dryRun, Duration requestTimeout, Map<String, Producer> producers)` where `Producer(String label, int priority)`; `LinearProperties.producerFor(String producerKey)` returning `Producer`; `LinearTaskQueues.LINEAR` = `"linear"`.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.linear.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinearPropertiesTest {

  private static LinearProperties defaults() {
    return new LinearProperties(false, null, null, null, null, false, null, null);
  }

  @Test
  void isDisabledAndCredentiallessByDefault() {
    LinearProperties properties = defaults();
    assertThat(properties.enabled()).isFalse();
    assertThat(properties.apiKey()).isEmpty();
    assertThat(properties.dryRun()).isFalse();
  }

  @Test
  void appliesTheDocumentedEndpointAndFingerprintDefaults() {
    LinearProperties properties = defaults();
    assertThat(properties.apiBaseUrl()).isEqualTo("https://api.linear.app/graphql");
    assertThat(properties.fingerprintBaseUrl())
        .isEqualTo("https://factory.simonrowe.dev/fingerprint");
    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void carriesAPolicyForEachKnownProducer() {
    LinearProperties properties = defaults();
    assertThat(properties.producerFor("deploy").label()).isEqualTo("factory:deploy");
    assertThat(properties.producerFor("deploy").priority()).isEqualTo(1);
    assertThat(properties.producerFor("cvefix").label()).isEqualTo("factory:cvefix");
    assertThat(properties.producerFor("cvefix").priority()).isEqualTo(3);
  }

  @Test
  void anUnconfiguredProducerFallsBackToAGenericLabelRatherThanFailing() {
    // A future producer that ships before its config entry must still file, not throw:
    // losing the finding is worse than mislabelling it.
    LinearProperties.Producer fallback = defaults().producerFor("bughunter");
    assertThat(fallback.label()).isEqualTo("factory:bughunter");
    assertThat(fallback.priority()).isEqualTo(3);
  }

  @Test
  void configuredProducersOverrideTheDefaults() {
    LinearProperties properties =
        new LinearProperties(
            true, "k", null, "SIM", null, false, null,
            Map.of("deploy", new LinearProperties.Producer("urgent:deploy", 2)));
    assertThat(properties.producerFor("deploy").label()).isEqualTo("urgent:deploy");
    assertThat(properties.producerFor("deploy").priority()).isEqualTo(2);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.config.LinearPropertiesTest"`
Expected: FAIL — compilation error, `LinearProperties` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.simonrowe.factory.linear.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the Linear issue sink.
 *
 * <p>Defaults live in the record constructor, following {@link
 * com.simonrowe.factory.cvefix.config.CveFixProperties}, so an absent {@code factory.linear} block
 * still yields a valid, disabled configuration.
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

  public LinearProperties {
    apiKey = apiKey == null ? "" : apiKey;
    apiBaseUrl = apiBaseUrl == null ? "https://api.linear.app/graphql" : apiBaseUrl;
    teamKey = teamKey == null ? "" : teamKey;
    fingerprintBaseUrl =
        fingerprintBaseUrl == null
            ? "https://factory.simonrowe.dev/fingerprint"
            : fingerprintBaseUrl;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
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
   * @param priority the Linear priority integer; see specs/039-linear-issue-sink/research.md
   */
  public record Producer(String label, int priority) {

    public Producer {
      label = label == null ? "factory" : label;
      priority = priority == 0 ? NORMAL_PRIORITY : priority;
    }
  }
}
```

```java
package com.simonrowe.factory.linear.config;

/** Temporal task queue names for the Linear issue sink. */
public final class LinearTaskQueues {

  /**
   * Task queue polled only by {@code software-factory}, which alone holds {@code LINEAR_API_KEY}.
   *
   * <p>The {@code deployer} runs the same image but leaves {@code factory.linear.enabled} false,
   * so it registers no activity implementation and never receives the credential. That is the
   * same confinement {@code DeployTaskQueues} documents in the other direction.
   */
  public static final String LINEAR = "linear";

  private LinearTaskQueues() {
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.config.LinearPropertiesTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Add the configuration block to `application.yml`**

Append to the `factory:` block, after `deploy:`:

```yaml
  linear:
    # The issue sink. Off by default like every other module: with this false nothing polls the
    # `linear` task queue, no Mongo index is touched, and no credential is read.
    #
    # True on `software-factory` ONLY. The `deployer` runs the same image and must stay false —
    # that is what keeps a tracker credential out of the JVM holding the Docker socket.
    enabled: ${FACTORY_LINEAR_ENABLED:false}
    api-key: ${LINEAR_API_KEY:}
    api-base-url: ${LINEAR_API_URL:https://api.linear.app/graphql}
    # The human team key (e.g. SIM), not a UUID. Resolved to a team id lazily on first filing and
    # cached — never at boot, for the reason CveFixScheduleInitializer documents: an unreachable
    # third party must not fail the context and take the webhook receiver down with it.
    team-key: ${FACTORY_LINEAR_TEAM_KEY:}
    # Synthetic and deliberately non-resolving. This is the dedup key, not a link: it is stamped
    # on the issue as an attachment and looked up with attachmentsForURL.
    fingerprint-base-url: https://factory.simonrowe.dev/fingerprint
    # Reads Linear and writes linear_issues; performs no issueCreate/attachmentCreate/
    # commentCreate. Proves the lookup and the decision table against the real tracker without
    # leaving anything in it.
    dry-run: ${FACTORY_LINEAR_DRY_RUN:false}
    request-timeout: 30s
    producers:
      deploy:
        label: "factory:deploy"
        priority: 1
      cvefix:
        label: "factory:cvefix"
        priority: 3
```

- [ ] **Step 6: Verify Checkstyle and commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/linear/config \
        software-factory/src/test/java/com/simonrowe/factory/linear/config \
        software-factory/src/main/resources/application.yml
git commit -m "feat: add Linear issue sink configuration, disabled by default"
```

---

### Task 3: The fingerprint

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/domain/Fingerprint.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/domain/FingerprintTest.java`

**Interfaces:**
- Produces: `Fingerprint.of(String producer, List<String> keyParts)` → lowercase hex SHA-256 `String`; `Fingerprint.urlFor(String baseUrl, String fingerprint)` → `String`; `Fingerprint.VERSION` = `"v1"`.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.linear.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class FingerprintTest {

  @Test
  void isStableAcrossCalls() {
    assertThat(Fingerprint.of("deploy", List.of("recreate", "backend")))
        .isEqualTo(Fingerprint.of("deploy", List.of("recreate", "backend")));
  }

  @Test
  void isASixtyFourCharacterLowercaseHexDigest() {
    assertThat(Fingerprint.of("cvefix", List.of("pkg:maven/com.foo/bar@1.0")))
        .hasSize(64)
        .matches("[0-9a-f]{64}");
  }

  @Test
  void differsByProducerEvenWithIdenticalKeyParts() {
    assertThat(Fingerprint.of("deploy", List.of("x")))
        .isNotEqualTo(Fingerprint.of("cvefix", List.of("x")));
  }

  @Test
  void differsByKeyPartOrderAndBoundary() {
    // Joining without a separator would make ("ab","c") and ("a","bc") collide, so the
    // separator is load-bearing, not cosmetic.
    assertThat(Fingerprint.of("deploy", List.of("ab", "c")))
        .isNotEqualTo(Fingerprint.of("deploy", List.of("a", "bc")));
    assertThat(Fingerprint.of("deploy", List.of("a", "b")))
        .isNotEqualTo(Fingerprint.of("deploy", List.of("b", "a")));
  }

  @Test
  void buildsTheAttachmentUrlWithoutDoubleSlashes() {
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    assertThat(Fingerprint.urlFor("https://factory.simonrowe.dev/fingerprint/", fingerprint))
        .isEqualTo("https://factory.simonrowe.dev/fingerprint/" + fingerprint);
    assertThat(Fingerprint.urlFor("https://factory.simonrowe.dev/fingerprint", fingerprint))
        .isEqualTo("https://factory.simonrowe.dev/fingerprint/" + fingerprint);
  }

  @Test
  void refusesAnEmptyKeyPartSetRatherThanFilingEverythingAsOneProblem() {
    assertThatThrownBy(() -> Fingerprint.of("deploy", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key parts");
  }

  @Test
  void refusesABlankProducer() {
    assertThatThrownBy(() -> Fingerprint.of(" ", List.of("x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("producer");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.domain.FingerprintTest"`
Expected: FAIL — `Fingerprint` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.simonrowe.factory.linear.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The dedup key: what makes two occurrences the same problem.
 *
 * <p>Computed from structured fields only, never from agent prose. {@code DeployActivities}
 * generates a {@code headline} that makes a good issue title and a terrible fingerprint — the same
 * failure phrased differently on two runs would file twice.
 */
public final class Fingerprint {

  /**
   * Version prefix, following the codebase's {@code FORMAT_VERSION} idiom.
   *
   * <p><strong>Bumping this orphans every existing ticket</strong>, so the next occurrence of a
   * known problem files a duplicate. It is a deliberate, documented one-time cost — not something
   * to change casually. Same warning as {@code NarrationScriptBuilder.FORMAT_VERSION}.
   */
  public static final String VERSION = "v1";

  private static final String SEPARATOR = "|";

  private Fingerprint() {
  }

  /**
   * Computes the fingerprint for a producer's key parts.
   *
   * @param producer the producer key, e.g. {@code deploy}
   * @param keyParts the structured parts identifying the problem, in a stable order
   * @return the lowercase hex SHA-256 digest
   * @throws IllegalArgumentException if the producer is blank or there are no key parts
   */
  public static String of(final String producer, final List<String> keyParts) {
    if (producer == null || producer.isBlank()) {
      throw new IllegalArgumentException("producer must not be blank");
    }
    if (keyParts == null || keyParts.isEmpty()) {
      throw new IllegalArgumentException(
          "at least one key part is required, or every finding would share one fingerprint");
    }
    String canonical = VERSION + ":" + producer + ":" + String.join(SEPARATOR, keyParts);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /**
   * Builds the attachment URL that carries a fingerprint onto a Linear issue.
   *
   * @param baseUrl the configured base, with or without a trailing slash
   * @param fingerprint the digest from {@link #of}
   * @return the synthetic, deliberately non-resolving key URL
   */
  public static String urlFor(final String baseUrl, final String fingerprint) {
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return trimmed + "/" + fingerprint;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.domain.FingerprintTest"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/linear/domain/Fingerprint.java \
        software-factory/src/test/java/com/simonrowe/factory/linear/domain/FingerprintTest.java
git commit -m "feat: add the Linear issue fingerprint"
```

---

### Task 4: The filing decision — the heart of the feature

The decision table *is* the feature. It is pure, so it is tested exhaustively with no I/O.

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/domain/IssueStateType.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/domain/TrackedIssue.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/domain/FilingDecision.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/service/FilingDecider.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/service/FilingDecisionTest.java`

**Interfaces:**
- Produces: `IssueStateType.from(String linearType)` → enum of `TRIAGE, BACKLOG, UNSTARTED, STARTED, COMPLETED, CANCELED, UNKNOWN`, with `boolean open()`; `TrackedIssue(String id, String identifier, String url, IssueStateType stateType, Instant createdAt)`; `FilingDecision` enum `FILED_NEW, COMMENTED_EXISTING, SUPPRESSED, FILED_REGRESSION`; `FilingDecider.decide(List<TrackedIssue>)` → `FilingDecider.Outcome(FilingDecision decision, TrackedIssue subject)` where `subject` is null for `FILED_NEW`.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.linear.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FilingDecisionTest {

  private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant NEW = Instant.parse("2026-06-01T00:00:00Z");

  private static TrackedIssue issue(final String id, final IssueStateType type, final Instant at) {
    return new TrackedIssue(id, "SIM-" + id, "https://linear.app/i/" + id, type, at);
  }

  @Test
  void filesNewWhenNothingCarriesTheFingerprint() {
    FilingDecider.Outcome outcome = new FilingDecider().decide(List.of());
    assertThat(outcome.decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(outcome.subject()).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = IssueStateType.class,
      names = {"TRIAGE", "BACKLOG", "UNSTARTED", "STARTED"})
  void commentsOnAnyOpenIssue(final IssueStateType openType) {
    FilingDecider.Outcome outcome =
        new FilingDecider().decide(List.of(issue("1", openType, OLD)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(outcome.subject().id()).isEqualTo("1");
  }

  @Test
  void suppressesWhenTheOnlyIssueWasCancelled() {
    assertThat(new FilingDecider().decide(List.of(issue("1", IssueStateType.CANCELED, OLD))))
        .extracting(FilingDecider.Outcome::decision)
        .isEqualTo(FilingDecision.SUPPRESSED);
  }

  @Test
  void filesARegressionWhenTheOnlyIssueWasCompleted() {
    FilingDecider.Outcome outcome =
        new FilingDecider().decide(List.of(issue("1", IssueStateType.COMPLETED, OLD)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.FILED_REGRESSION);
    assertThat(outcome.subject().id()).isEqualTo("1");
  }

  @Test
  void openOutranksCancelled() {
    // This is the un-suppress gesture: reopening a cancelled issue makes the sink resume
    // reporting, with no config flag.
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("cancelled", IssueStateType.CANCELED, OLD),
                    issue("open", IssueStateType.STARTED, NEW)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(outcome.subject().id()).isEqualTo("open");
  }

  @Test
  void cancelledOutranksCompleted() {
    // A regression was filed for a completed issue, then declined. "Never tell me again" is a
    // more deliberate human statement than "this was once fixed".
    assertThat(
            new FilingDecider()
                .decide(
                    List.of(
                        issue("done", IssueStateType.COMPLETED, OLD),
                        issue("declined", IssueStateType.CANCELED, NEW))))
        .extracting(FilingDecider.Outcome::decision)
        .isEqualTo(FilingDecision.SUPPRESSED);
  }

  @Test
  void picksTheNewestAmongSeveralOpenIssues() {
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("older", IssueStateType.BACKLOG, OLD),
                    issue("newer", IssueStateType.TRIAGE, NEW)));
    assertThat(outcome.subject().id()).isEqualTo("newer");
  }

  @Test
  void picksTheNewestAmongSeveralCompletedIssues() {
    // The regression path leaves two issues sharing one fingerprint, so this is reachable in
    // production, not a hypothetical.
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("first", IssueStateType.COMPLETED, OLD),
                    issue("second", IssueStateType.COMPLETED, NEW)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.FILED_REGRESSION);
    assertThat(outcome.subject().id()).isEqualTo("second");
  }

  @Test
  void treatsAnUnrecognisedStateAsOpenRatherThanIgnoringIt() {
    // A state type Linear adds later must not silently become "file another one".
    FilingDecider.Outcome outcome =
        new FilingDecider().decide(List.of(issue("1", IssueStateType.UNKNOWN, OLD)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
  }

  @Test
  void mapsLinearStateTypeStrings() {
    assertThat(IssueStateType.from("triage")).isEqualTo(IssueStateType.TRIAGE);
    assertThat(IssueStateType.from("started")).isEqualTo(IssueStateType.STARTED);
    assertThat(IssueStateType.from("completed")).isEqualTo(IssueStateType.COMPLETED);
    assertThat(IssueStateType.from("canceled")).isEqualTo(IssueStateType.CANCELED);
    assertThat(IssueStateType.from("cancelled")).isEqualTo(IssueStateType.CANCELED);
    assertThat(IssueStateType.from("something-new")).isEqualTo(IssueStateType.UNKNOWN);
    assertThat(IssueStateType.from(null)).isEqualTo(IssueStateType.UNKNOWN);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.service.FilingDecisionTest"`
Expected: FAIL — none of these types exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.simonrowe.factory.linear.domain;

/**
 * Linear's {@code WorkflowState.type} values, plus the open/closed distinction the filing decision
 * turns on.
 *
 * <p>Declining an issue from Triage sets {@code canceled}; finishing it sets {@code completed}.
 * Using Linear's own semantics means the way a human triages tickets <em>is</em> the control
 * surface, with no extra concepts to configure.
 */
public enum IssueStateType {
  /** Machine-filed, awaiting a human's accept or decline. */
  TRIAGE(true),
  /** Accepted, not scheduled. */
  BACKLOG(true),
  /** Scheduled, not started. */
  UNSTARTED(true),
  /** In progress. */
  STARTED(true),
  /** Fixed. A recurrence is a regression. */
  COMPLETED(false),
  /** Declined — "not a bug, never tell me again". */
  CANCELED(false),
  /**
   * A state type this code does not recognise.
   *
   * <p>Treated as open on purpose: if Linear adds a type, the safe failure is "comment on the
   * existing issue", not "file another one".
   */
  UNKNOWN(true);

  private final boolean open;

  IssueStateType(final boolean open) {
    this.open = open;
  }

  /**
   * Whether an issue in this state is still being tracked.
   *
   * @return true when the issue is open
   */
  public boolean open() {
    return open;
  }

  /**
   * Maps a Linear state type string onto this enum.
   *
   * @param linearType the {@code state.type} value from the API, may be null
   * @return the matching constant, or {@link #UNKNOWN}
   */
  public static IssueStateType from(final String linearType) {
    if (linearType == null) {
      return UNKNOWN;
    }
    // Linear spells it "canceled"; accept the British spelling too so a hand-written fixture or a
    // future API change cannot silently downgrade a suppression to a regression.
    String normalised = linearType.trim().toLowerCase(java.util.Locale.ROOT);
    if ("cancelled".equals(normalised)) {
      return CANCELED;
    }
    for (IssueStateType candidate : values()) {
      if (candidate != UNKNOWN && candidate.name().toLowerCase(java.util.Locale.ROOT)
          .equals(normalised)) {
        return candidate;
      }
    }
    return UNKNOWN;
  }
}
```

```java
package com.simonrowe.factory.linear.domain;

import java.time.Instant;

/**
 * A Linear issue found by fingerprint.
 *
 * @param id the Linear issue UUID
 * @param identifier the human identifier, e.g. {@code SIM-42}
 * @param url the issue's web URL
 * @param stateType the issue's current workflow state type, read from Linear rather than cached
 * @param createdAt when the issue was created, used to break ties within a precedence band
 */
public record TrackedIssue(
    String id, String identifier, String url, IssueStateType stateType, Instant createdAt) {
}
```

```java
package com.simonrowe.factory.linear.domain;

/** What the sink did about one occurrence. */
public enum FilingDecision {
  /** No issue carried the fingerprint; a new one was created in Triage. */
  FILED_NEW,
  /** An open issue already carried it; the occurrence was added as a comment. */
  COMMENTED_EXISTING,
  /** A human declined it. Nothing was done, and nothing will be until they reopen it. */
  SUPPRESSED,
  /** It was marked fixed and came back; a new issue was filed, linked to the old one. */
  FILED_REGRESSION
}
```

```java
package com.simonrowe.factory.linear.service;

import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decides what to do about one occurrence, given every issue carrying its fingerprint.
 *
 * <p>Pure and I/O-free: this is the feature, so it is exhaustively testable without a tracker.
 *
 * <p><strong>Precedence is open &gt; canceled &gt; completed.</strong> It has to be defined rather
 * than assumed, because the regression path deliberately leaves two issues sharing one
 * fingerprint. Two consequences are load-bearing:
 *
 * <ul>
 *   <li>Reopening a cancelled issue un-suppresses it, because open outranks canceled. That is the
 *       reversal gesture, and it needs no configuration.
 *   <li>Canceled outranks completed, because "never tell me again" is a more deliberate statement
 *       than "this was once fixed".
 * </ul>
 */
@Component
public class FilingDecider {

  /**
   * Applies the precedence rules.
   *
   * @param carryingFingerprint every issue found carrying the occurrence's fingerprint, in any
   *     order; empty when none was found
   * @return the decision, and the issue it concerns — null for {@link FilingDecision#FILED_NEW}
   */
  public Outcome decide(final List<TrackedIssue> carryingFingerprint) {
    Optional<TrackedIssue> open =
        newest(carryingFingerprint.stream().filter(i -> i.stateType().open()).toList());
    if (open.isPresent()) {
      return new Outcome(FilingDecision.COMMENTED_EXISTING, open.get());
    }
    Optional<TrackedIssue> cancelled =
        newest(
            carryingFingerprint.stream()
                .filter(i -> i.stateType() == com.simonrowe.factory.linear.domain.IssueStateType
                    .CANCELED)
                .toList());
    if (cancelled.isPresent()) {
      return new Outcome(FilingDecision.SUPPRESSED, cancelled.get());
    }
    Optional<TrackedIssue> completed = newest(carryingFingerprint);
    return completed
        .map(issue -> new Outcome(FilingDecision.FILED_REGRESSION, issue))
        .orElseGet(() -> new Outcome(FilingDecision.FILED_NEW, null));
  }

  private static Optional<TrackedIssue> newest(final List<TrackedIssue> issues) {
    return issues.stream().max(Comparator.comparing(TrackedIssue::createdAt));
  }

  /**
   * The decision and its subject.
   *
   * @param decision what to do
   * @param subject the issue the decision concerns; null only for {@link FilingDecision#FILED_NEW}
   */
  public record Outcome(FilingDecision decision, TrackedIssue subject) {
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.service.FilingDecisionTest"`
Expected: PASS — 10 test methods, 14 executions (the parameterised one runs four times).

- [ ] **Step 5: Commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/linear/domain \
        software-factory/src/main/java/com/simonrowe/factory/linear/service \
        software-factory/src/test/java/com/simonrowe/factory/linear/service
git commit -m "feat: add the Linear filing decision with open/canceled/completed precedence"
```

---

### Task 5: The audit record

Linear is truth; Mongo answers "what has this filed, and did it dedup correctly?" without paging through the tracker — the same role `deploy_runs` and `cve_fix_runs` play outside Temporal's retention window.

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/persistence/LinearIssueDecision.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/persistence/LinearIssueRecord.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/persistence/LinearIssueRepository.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/persistence/LinearIndexInitializer.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/persistence/LinearIssueRecordTest.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/persistence/LinearIssueRepositoryTest.java`

**Interfaces:**
- Produces: `LinearIssueRecord(String id, String producer, String fingerprintVersion, List<String> keyParts, String issueId, String issueIdentifier, String issueUrl, boolean attachmentPending, Instant firstFiledAt, Instant lastSeenAt, int occurrences, IssueStateType lastKnownStateType, List<LinearIssueDecision> decisions)`; instance methods `boolean hasOccurrence(String occurrenceId)` and `LinearIssueRecord withDecision(LinearIssueDecision decision, Instant seenAt, IssueStateType stateType)`; static `LinearIssueRecord.MAX_DECISIONS` = `20`. `LinearIssueDecision(Instant at, FilingDecision decision, String occurrenceId, String workflowId, String detail)`. `LinearIssueRepository extends MongoRepository<LinearIssueRecord, String>` with `List<LinearIssueRecord> findByProducerOrderByLastSeenAtDesc(String producer)`.

- [ ] **Step 1: Write the failing pure test**

```java
package com.simonrowe.factory.linear.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueStateType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearIssueRecordTest {

  private static final Instant T0 = Instant.parse("2026-08-27T10:00:00Z");

  private static LinearIssueRecord fresh() {
    return LinearIssueRecord.first(
        "fp", "deploy", List.of("recreate", "backend"), T0);
  }

  private static LinearIssueDecision decision(final String occurrenceId) {
    return new LinearIssueDecision(
        T0, FilingDecision.COMMENTED_EXISTING, occurrenceId, "deploy-prod", "recreate failed");
  }

  @Test
  void aFirstRecordHasNoIssueYetAndOneOccurrence() {
    LinearIssueRecord record = fresh();
    assertThat(record.id()).isEqualTo("fp");
    assertThat(record.issueId()).isNull();
    assertThat(record.attachmentPending()).isFalse();
    assertThat(record.occurrences()).isEqualTo(1);
    assertThat(record.decisions()).isEmpty();
  }

  @Test
  void recognisesAReplayedOccurrence() {
    LinearIssueRecord record =
        fresh().withDecision(decision("run-1"), T0, IssueStateType.TRIAGE);
    assertThat(record.hasOccurrence("run-1")).isTrue();
    assertThat(record.hasOccurrence("run-2")).isFalse();
  }

  @Test
  void aNullOccurrenceIdNeverMatches() {
    // Belt and braces: a producer that forgets to pass one must get "not seen", not a
    // silent no-op that loses the occurrence.
    assertThat(fresh().withDecision(decision(null), T0, IssueStateType.TRIAGE)
            .hasOccurrence(null))
        .isFalse();
  }

  @Test
  void countsOccurrencesAndAdvancesLastSeen() {
    Instant later = T0.plusSeconds(3600);
    LinearIssueRecord record =
        fresh().withDecision(decision("run-1"), later, IssueStateType.STARTED);
    assertThat(record.occurrences()).isEqualTo(2);
    assertThat(record.lastSeenAt()).isEqualTo(later);
    assertThat(record.firstFiledAt()).isEqualTo(T0);
    assertThat(record.lastKnownStateType()).isEqualTo(IssueStateType.STARTED);
  }

  @Test
  void capsTheDecisionLogAtTwentyKeepingTheNewest() {
    LinearIssueRecord record = fresh();
    for (int i = 0; i < 25; i++) {
      record = record.withDecision(decision("run-" + i), T0, IssueStateType.TRIAGE);
    }
    assertThat(record.decisions()).hasSize(LinearIssueRecord.MAX_DECISIONS);
    assertThat(record.decisions().get(0).occurrenceId()).isEqualTo("run-5");
    assertThat(record.decisions().get(19).occurrenceId()).isEqualTo("run-24");
    // The counter is not capped, only the log.
    assertThat(record.occurrences()).isEqualTo(26);
  }

  @Test
  void aCappedOutOccurrenceIsNoLongerRecognised() {
    // Known and accepted: beyond 20 decisions a replay could post a duplicate comment. The
    // window is far wider than any activity retry, and one duplicate comment is a benign
    // failure compared with the cost of an unbounded array.
    LinearIssueRecord record = fresh();
    for (int i = 0; i < 25; i++) {
      record = record.withDecision(decision("run-" + i), T0, IssueStateType.TRIAGE);
    }
    assertThat(record.hasOccurrence("run-0")).isFalse();
    assertThat(record.hasOccurrence("run-24")).isTrue();
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.persistence.LinearIssueRecordTest"`
Expected: FAIL — `LinearIssueRecord` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.simonrowe.factory.linear.persistence;

import com.simonrowe.factory.linear.domain.FilingDecision;
import java.time.Instant;

/**
 * One entry in a problem's decision log.
 *
 * @param at when the decision was taken
 * @param decision what the sink did
 * @param occurrenceId the producing workflow's run id, used to recognise an activity replay
 * @param workflowId the producing workflow's id, for tracing back to the run
 * @param detail one line of human context, e.g. the commit that tripped it
 */
public record LinearIssueDecision(
    Instant at,
    FilingDecision decision,
    String occurrenceId,
    String workflowId,
    String detail) {
}
```

```java
package com.simonrowe.factory.linear.persistence;

import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueStateType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The audit trail for one problem, keyed by its fingerprint.
 *
 * <p><strong>Linear is truth; this is the trail.</strong> Identity and state are always read back
 * from Linear, so closing or deleting a ticket by hand cannot leave the factory believing it is
 * still open. This document exists to answer "what has the sink filed, and did it dedup
 * correctly?" outside Temporal's retention window.
 *
 * <p>{@code attachmentPending} closes the one real duplicate risk: an {@code issueCreate} that
 * succeeds followed by an {@code attachmentCreate} that fails. Without it a retry finds no
 * attachment and files a second ticket; with it, the retry repairs by attaching.
 */
@Document(collection = "linear_issues")
public record LinearIssueRecord(
    @Id String id,
    String producer,
    String fingerprintVersion,
    List<String> keyParts,
    String issueId,
    String issueIdentifier,
    String issueUrl,
    boolean attachmentPending,
    Instant firstFiledAt,
    Instant lastSeenAt,
    int occurrences,
    IssueStateType lastKnownStateType,
    List<LinearIssueDecision> decisions) {

  /**
   * How many decisions are retained.
   *
   * <p>Capped because an unbounded array in a Mongo document grows without limit. The accepted
   * cost is that a replay older than this window could post one duplicate comment — far wider
   * than any activity retry, and a benign failure.
   */
  public static final int MAX_DECISIONS = 20;

  public LinearIssueRecord {
    keyParts = keyParts == null ? List.of() : List.copyOf(keyParts);
    decisions = decisions == null ? List.of() : List.copyOf(decisions);
  }

  /**
   * The record for a problem seen for the first time.
   *
   * @param fingerprint the problem's fingerprint, which is also the document id
   * @param producer the producer key
   * @param keyParts the structured parts the fingerprint was computed from, kept for readability
   * @param seenAt when this occurrence arrived
   * @return a record with no issue yet and one occurrence counted
   */
  public static LinearIssueRecord first(
      final String fingerprint,
      final String producer,
      final List<String> keyParts,
      final Instant seenAt) {
    return new LinearIssueRecord(
        fingerprint,
        producer,
        Fingerprint.VERSION,
        keyParts,
        null,
        null,
        null,
        false,
        seenAt,
        seenAt,
        1,
        null,
        List.of());
  }

  /**
   * Whether this occurrence has already been handled — an activity replay rather than a new event.
   *
   * @param occurrenceId the producing run id, may be null
   * @return true when the id appears in the retained decision log
   */
  public boolean hasOccurrence(final String occurrenceId) {
    if (occurrenceId == null) {
      return false;
    }
    return decisions.stream().anyMatch(d -> occurrenceId.equals(d.occurrenceId()));
  }

  /**
   * Appends a decision, advancing the counters and truncating the log to {@link #MAX_DECISIONS}.
   *
   * @param decision the decision taken
   * @param seenAt when this occurrence arrived
   * @param stateType the state Linear reported, or null when nothing was found
   * @return a new record; this type is immutable
   */
  public LinearIssueRecord withDecision(
      final LinearIssueDecision decision, final Instant seenAt, final IssueStateType stateType) {
    List<LinearIssueDecision> appended = new ArrayList<>(decisions);
    appended.add(decision);
    if (appended.size() > MAX_DECISIONS) {
      appended = new ArrayList<>(appended.subList(appended.size() - MAX_DECISIONS, appended.size()));
    }
    return new LinearIssueRecord(
        id,
        producer,
        fingerprintVersion,
        keyParts,
        issueId,
        issueIdentifier,
        issueUrl,
        attachmentPending,
        firstFiledAt,
        seenAt,
        occurrences + 1,
        stateType,
        appended);
  }

  /**
   * Records the issue this problem now points at, with its attachment not yet written.
   *
   * @param newIssueId the Linear issue UUID
   * @param newIssueIdentifier the human identifier, e.g. {@code SIM-42}
   * @param newIssueUrl the issue's web URL
   * @return a new record with {@code attachmentPending} set
   */
  public LinearIssueRecord withPendingAttachment(
      final String newIssueId, final String newIssueIdentifier, final String newIssueUrl) {
    return new LinearIssueRecord(
        id, producer, fingerprintVersion, keyParts,
        newIssueId, newIssueIdentifier, newIssueUrl, true,
        firstFiledAt, lastSeenAt, occurrences, lastKnownStateType, decisions);
  }

  /**
   * Clears the pending flag once the fingerprint attachment is written.
   *
   * @return a new record with {@code attachmentPending} cleared
   */
  public LinearIssueRecord withAttachmentWritten() {
    return new LinearIssueRecord(
        id, producer, fingerprintVersion, keyParts,
        issueId, issueIdentifier, issueUrl, false,
        firstFiledAt, lastSeenAt, occurrences, lastKnownStateType, decisions);
  }
}
```

```java
package com.simonrowe.factory.linear.persistence;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link LinearIssueRecord}, keyed by fingerprint. */
public interface LinearIssueRepository extends MongoRepository<LinearIssueRecord, String> {

  /**
   * Everything a producer has filed, newest occurrence first.
   *
   * @param producer the producer key
   * @return that producer's records, ordered by most recently seen
   */
  List<LinearIssueRecord> findByProducerOrderByLastSeenAtDesc(String producer);
}
```

```java
package com.simonrowe.factory.linear.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.stereotype.Component;

/**
 * Ensures the issue-sink index at startup. Mongock stays backend-owned; this database belongs to
 * the factory, so index management lives here in code — see {@code CveFixIndexInitializer}.
 *
 * <p>Gated on {@code factory.linear.enabled} so an unreachable Mongo cannot fail the whole
 * application context, and with it the GitHub webhook receiver and the {@code code-review} worker.
 */
@Component
@ConditionalOnProperty(name = "factory.linear.enabled", havingValue = "true")
public class LinearIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  /**
   * Creates the initializer.
   *
   * @param mongoTemplate the template used to manage indexes on the factory's own database
   */
  public LinearIndexInitializer(final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Creates the {@code {producer, lastSeenAt}} index. Idempotent, because this runs on every
   * restart. No index on the fingerprint: it is the document {@code _id}.
   *
   * @param args the application arguments, unused
   */
  @Override
  public void run(final ApplicationArguments args) {
    mongoTemplate
        .indexOps(LinearIssueRecord.class)
        .createIndex(
            new CompoundIndexDefinition(
                    new org.bson.Document("producer", 1).append("lastSeenAt", -1))
                .named("producer_lastSeen"));
  }
}
```

- [ ] **Step 4: Run the pure test to verify it passes**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.persistence.LinearIssueRecordTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write the repository round-trip test**

```java
package com.simonrowe.factory.linear.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueStateType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Testcontainers
class LinearIssueRepositoryTest {

  @Container
  private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

  @DynamicPropertySource
  static void mongoUri(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.data.mongodb.uri", () -> MONGO.getConnectionString() + "/software_factory_test");
  }

  @Autowired private LinearIssueRepository records;

  // The container is a static singleton shared across this class, so the collection has to be
  // cleared per test or the ordering assertion sees rows from whichever test ran first.
  @BeforeEach
  void clearCollection() {
    records.deleteAll();
  }

  @Test
  void roundTripsEveryFieldIncludingTheEnumAndTheDecisionLog() {
    LinearIssueRecord saved =
        records.save(
            LinearIssueRecord.first("fp1", "deploy", List.of("recreate", "backend"), Instant.EPOCH)
                .withPendingAttachment("uuid", "SIM-1", "https://linear.app/i/1")
                .withAttachmentWritten()
                .withDecision(
                    new LinearIssueDecision(
                        Instant.EPOCH, FilingDecision.FILED_NEW, "run-1", "deploy-prod", "boom"),
                    Instant.EPOCH,
                    IssueStateType.TRIAGE));

    LinearIssueRecord loaded = records.findById("fp1").orElseThrow();
    assertThat(loaded.producer()).isEqualTo("deploy");
    assertThat(loaded.fingerprintVersion()).isEqualTo("v1");
    assertThat(loaded.keyParts()).containsExactly("recreate", "backend");
    assertThat(loaded.issueIdentifier()).isEqualTo("SIM-1");
    assertThat(loaded.attachmentPending()).isFalse();
    assertThat(loaded.lastKnownStateType()).isEqualTo(IssueStateType.TRIAGE);
    assertThat(loaded.decisions()).hasSize(1);
    assertThat(loaded.decisions().get(0).decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(loaded.decisions().get(0).occurrenceId()).isEqualTo("run-1");
    assertThat(saved.occurrences()).isEqualTo(2);
  }

  @Test
  void listsAProducersRecordsNewestOccurrenceFirst() {
    records.save(
        LinearIssueRecord.first("old", "deploy", List.of("a"), Instant.parse(
            "2026-01-01T00:00:00Z")));
    records.save(
        LinearIssueRecord.first("new", "deploy", List.of("b"), Instant.parse(
            "2026-06-01T00:00:00Z")));
    records.save(
        LinearIssueRecord.first("other", "cvefix", List.of("c"), Instant.parse(
            "2026-07-01T00:00:00Z")));

    assertThat(records.findByProducerOrderByLastSeenAtDesc("deploy"))
        .extracting(LinearIssueRecord::id)
        .containsExactly("new", "old");
  }
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.persistence.LinearIssueRepositoryTest"`
Expected: PASS, 2 tests. First run pulls `mongo:8`.

- [ ] **Step 7: Commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/linear/persistence \
        software-factory/src/test/java/com/simonrowe/factory/linear/persistence
git commit -m "feat: add the linear_issues audit record and its index"
```

---

### Task 6: Linear gateway — reads and team resolution

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/linear/LinearApiException.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/linear/LinearGateway.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/linear/LinearGatewayReadTest.java`

**Interfaces:**
- Produces: `LinearApiException extends RuntimeException` with `boolean retryable()`; `LinearGateway(LinearProperties properties, ObjectMapper objectMapper)`; `LinearGateway.TeamContext teamContext()` returning `TeamContext(String teamId, String triageStateId, Map<String, String> labelIds)`, resolved once and cached; `List<TrackedIssue> issuesForFingerprint(String fingerprintUrl)`.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.linear.linear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinearGatewayReadTest {

  private HttpServer server;
  private final List<String> bodies = new ArrayList<>();
  private final List<String> authHeaders = new ArrayList<>();
  private final AtomicInteger requests = new AtomicInteger();
  private volatile String response = "{}";
  private volatile int status = 200;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/graphql",
        exchange -> {
          requests.incrementAndGet();
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] out = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private LinearGateway gateway() {
    LinearProperties properties =
        new LinearProperties(
            true,
            "lin_api_test",
            "http://localhost:" + server.getAddress().getPort() + "/graphql",
            "SIM",
            null,
            false,
            null,
            null);
    return new LinearGateway(properties, new ObjectMapper());
  }

  @Test
  void resolvesTheTeamTriageStateAndLabelsInOneQueryAndCachesIt() {
    response =
        """
        {"data":{"teams":{"nodes":[{"id":"team-uuid","key":"SIM",
          "states":{"nodes":[{"id":"s-triage","name":"Triage","type":"triage"},
                             {"id":"s-todo","name":"Todo","type":"unstarted"}]},
          "labels":{"nodes":[{"id":"l-deploy","name":"factory:deploy"},
                             {"id":"l-cvefix","name":"factory:cvefix"}]}}]}}}
        """;

    LinearGateway gateway = gateway();
    LinearGateway.TeamContext first = gateway.teamContext();
    LinearGateway.TeamContext second = gateway.teamContext();

    assertThat(first.teamId()).isEqualTo("team-uuid");
    assertThat(first.triageStateId()).isEqualTo("s-triage");
    assertThat(first.labelIds()).containsEntry("factory:deploy", "l-deploy");
    assertThat(second).isSameAs(first);
    assertThat(requests.get()).isEqualTo(1);
    assertThat(authHeaders.get(0)).isEqualTo("lin_api_test");
  }

  @Test
  void failsNonRetryablyWhenTheTeamHasNoTriageState() {
    // Triage is a per-team toggle and the whole suppression design depends on it. Failing loudly
    // here is the difference between a clear error and issues quietly landing in the backlog.
    response =
        """
        {"data":{"teams":{"nodes":[{"id":"t","key":"SIM",
          "states":{"nodes":[{"id":"s","name":"Todo","type":"unstarted"}]},
          "labels":{"nodes":[]}}]}}}
        """;

    assertThatThrownBy(() -> gateway().teamContext())
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("Triage")
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }

  @Test
  void failsNonRetryablyWhenTheTeamKeyMatchesNothing() {
    response = """{"data":{"teams":{"nodes":[]}}}""";
    assertThatThrownBy(() -> gateway().teamContext())
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("SIM");
  }

  @Test
  void readsEveryIssueCarryingTheFingerprintIncludingCancelledOnes() {
    // The design turns on this: if a cancelled issue is not returned, suppression stops working
    // and declined bugs are re-filed forever.
    response =
        """
        {"data":{"attachmentsForURL":{"nodes":[
          {"issue":{"id":"i1","identifier":"SIM-1","url":"https://linear.app/i/1",
                    "createdAt":"2026-01-01T00:00:00.000Z","state":{"type":"canceled"}}},
          {"issue":{"id":"i2","identifier":"SIM-2","url":"https://linear.app/i/2",
                    "createdAt":"2026-06-01T00:00:00.000Z","state":{"type":"started"}}}]}}}
        """;

    List<TrackedIssue> issues =
        gateway().issuesForFingerprint("https://factory.simonrowe.dev/fingerprint/abc");

    assertThat(issues).hasSize(2);
    assertThat(issues.get(0).stateType()).isEqualTo(IssueStateType.CANCELED);
    assertThat(issues.get(1).identifier()).isEqualTo("SIM-2");
    assertThat(bodies.get(0)).contains("fingerprint/abc");
  }

  @Test
  void returnsEmptyWhenNothingCarriesTheFingerprint() {
    response = """{"data":{"attachmentsForURL":{"nodes":[]}}}""";
    assertThat(gateway().issuesForFingerprint("https://x/y")).isEmpty();
  }

  @Test
  void classifiesAnUnauthorisedResponseAsNonRetryable() {
    // A read-only or revoked key must not burn a retry budget.
    status = 401;
    response = """{"errors":[{"message":"Authentication required"}]}""";
    assertThatThrownBy(() -> gateway().issuesForFingerprint("https://x/y"))
        .isInstanceOf(LinearApiException.class)
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }

  @Test
  void classifiesServerErrorsAndRateLimitsAsRetryable() {
    status = 503;
    response = "upstream down";
    assertThatThrownBy(() -> gateway().issuesForFingerprint("https://x/y"))
        .isInstanceOf(LinearApiException.class)
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(true);

    status = 429;
    assertThatThrownBy(() -> gateway().issuesForFingerprint("https://x/y"))
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(true);
  }

  @Test
  void classifiesAGraphQlErrorOnATwoHundredAsNonRetryable() {
    // A malformed query does not fix itself; retrying it is pure cost.
    status = 200;
    response = """{"errors":[{"message":"Cannot query field \\"nope\\""}]}""";
    assertThatThrownBy(() -> gateway().issuesForFingerprint("https://x/y"))
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("nope")
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.linear.LinearGatewayReadTest"`
Expected: FAIL — `LinearGateway` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.simonrowe.factory.linear.linear;

/**
 * A Linear API fault, carrying whether retrying could help.
 *
 * <p>The distinction is load-bearing: the Temporal activity's retry policy is driven by it, and a
 * revoked or read-only key must fail fast rather than consume a retry budget.
 */
public class LinearApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final boolean retryable;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param retryable whether a later attempt could succeed
   */
  public LinearApiException(final String message, final boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what failed
   * @param retryable whether a later attempt could succeed
   * @param cause the underlying fault
   */
  public LinearApiException(
      final String message, final boolean retryable, final Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }

  /**
   * Whether a later attempt could succeed.
   *
   * @return true for transport faults, 5xx and 429; false for auth and query errors
   */
  public boolean retryable() {
    return retryable;
  }
}
```

```java
package com.simonrowe.factory.linear.linear;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Linear's GraphQL API, over {@link HttpClient} — the {@code DependencyTrackClient} pattern, so
 * this module adds no dependency.
 *
 * <p>The team, its Triage state and its labels are resolved in one query and cached for the
 * process lifetime, <strong>lazily on first use and never at startup</strong>: an unreachable
 * Linear must not fail the application context and take the GitHub webhook receiver and the
 * {@code code-review} worker down with it, which is the failure mode {@code
 * CveFixScheduleInitializer} documents avoiding.
 */
@Component
public class LinearGateway {

  private static final String TEAM_QUERY =
      "query($key:String!){teams(filter:{key:{eq:$key}}){nodes{id key "
          + "states{nodes{id name type}} labels{nodes{id name}}}}}";

  private static final String ATTACHMENTS_QUERY =
      "query($url:String!){attachmentsForURL(url:$url){nodes{issue{id identifier url "
          + "createdAt state{type}}}}}";

  private final LinearProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  private volatile TeamContext cachedTeam;

  /**
   * Creates the gateway.
   *
   * @param properties the bound {@code factory.linear} configuration
   * @param objectMapper mapper used to build requests and parse responses
   */
  public LinearGateway(final LinearProperties properties, final ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
  }

  /**
   * Resolves and caches the team, its Triage state id and its label ids.
   *
   * @return the resolved context
   * @throws LinearApiException if the team key matches nothing, or the team has no Triage state
   */
  public TeamContext teamContext() {
    TeamContext local = cachedTeam;
    if (local != null) {
      return local;
    }
    synchronized (this) {
      if (cachedTeam != null) {
        return cachedTeam;
      }
      JsonNode team =
          execute(TEAM_QUERY, Map.of("key", properties.teamKey()))
              .path("teams")
              .path("nodes")
              .path(0);
      if (team.isMissingNode() || team.path("id").asText("").isEmpty()) {
        throw new LinearApiException(
            "Linear has no team with key " + properties.teamKey(), false);
      }
      String triageStateId = null;
      for (JsonNode state : team.path("states").path("nodes")) {
        if ("triage".equals(state.path("type").asText())) {
          triageStateId = state.path("id").asText();
        }
      }
      if (triageStateId == null) {
        throw new LinearApiException(
            "Team "
                + properties.teamKey()
                + " has no Triage state — enable Triage on the team in Linear settings; the "
                + "suppression design depends on it",
            false);
      }
      Map<String, String> labels = new HashMap<>();
      for (JsonNode label : team.path("labels").path("nodes")) {
        labels.put(label.path("name").asText(), label.path("id").asText());
      }
      cachedTeam = new TeamContext(team.path("id").asText(), triageStateId, Map.copyOf(labels));
      return cachedTeam;
    }
  }

  /**
   * Every issue carrying a fingerprint, in whatever state — cancelled included.
   *
   * @param fingerprintUrl the synthetic attachment URL from {@code Fingerprint.urlFor}
   * @return the issues found, in the order Linear returned them; empty when none
   * @throws LinearApiException on any API fault
   */
  public List<TrackedIssue> issuesForFingerprint(final String fingerprintUrl) {
    JsonNode nodes =
        execute(ATTACHMENTS_QUERY, Map.of("url", fingerprintUrl))
            .path("attachmentsForURL")
            .path("nodes");
    List<TrackedIssue> issues = new ArrayList<>();
    for (JsonNode node : nodes) {
      JsonNode issue = node.path("issue");
      if (issue.isMissingNode() || issue.path("id").asText("").isEmpty()) {
        continue;
      }
      issues.add(
          new TrackedIssue(
              issue.path("id").asText(),
              issue.path("identifier").asText(),
              issue.path("url").asText(),
              IssueStateType.from(issue.path("state").path("type").asText(null)),
              Instant.parse(issue.path("createdAt").asText("1970-01-01T00:00:00.000Z"))));
    }
    return List.copyOf(issues);
  }

  /**
   * Executes a GraphQL document and returns its {@code data} node.
   *
   * @param document the query or mutation
   * @param variables the variables, which may be empty
   * @return the {@code data} node
   * @throws LinearApiException on transport, status or GraphQL errors
   */
  protected JsonNode execute(final String document, final Map<String, Object> variables) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("query", document);
    payload.set("variables", objectMapper.valueToTree(variables));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(properties.apiBaseUrl()))
            .timeout(properties.requestTimeout())
            // Linear personal API keys go in Authorization with no Bearer prefix.
            .header("Authorization", properties.apiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException exception) {
      throw new LinearApiException("Linear request failed", true, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new LinearApiException("Interrupted calling Linear", true, exception);
    }

    int status = response.statusCode();
    if (status == 401 || status == 403) {
      throw new LinearApiException(
          "Linear rejected the API key with " + status + " — check LINEAR_API_KEY and its scopes",
          false);
    }
    if (status == 429 || status >= 500) {
      throw new LinearApiException("Linear returned " + status, true);
    }
    if (status < 200 || status >= 300) {
      throw new LinearApiException("Linear returned " + status + ": " + response.body(), false);
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(response.body());
    } catch (IOException exception) {
      throw new LinearApiException("Linear returned unparseable JSON", false, exception);
    }
    if (root.has("errors") && !root.path("errors").isEmpty()) {
      throw new LinearApiException("Linear GraphQL error: " + root.path("errors"), false);
    }
    return root.path("data");
  }

  /**
   * The resolved team, cached for the process lifetime.
   *
   * @param teamId the team UUID
   * @param triageStateId the id of the team's {@code triage}-type workflow state
   * @param labelIds label name to label id, for the labels that exist on the team
   */
  public record TeamContext(String teamId, String triageStateId, Map<String, String> labelIds) {
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.linear.LinearGatewayReadTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/linear/linear \
        software-factory/src/test/java/com/simonrowe/factory/linear/linear
git commit -m "feat: read Linear issues by fingerprint, with retryable fault classification"
```

---

### Task 7: Linear gateway — writes

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/linear/linear/LinearGateway.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/linear/LinearGatewayWriteTest.java`

**Interfaces:**
- Produces, all on `LinearGateway`: `CreatedIssue createIssue(String title, String body, int priority, String labelName)` returning `CreatedIssue(String id, String identifier, String url)`; `void attachFingerprint(String issueId, String fingerprintUrl)`; `void addComment(String issueId, String body)`; `void relateIssues(String issueId, String relatedIssueId)`.

- [ ] **Step 1: Write the failing test**

Reuse the stub harness from Task 6, but the server now answers per-operation. Replace the single `response` field with a routing map keyed by the first mutation name seen in the body.

```java
package com.simonrowe.factory.linear.linear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinearGatewayWriteTest {

  private static final String TEAM_RESPONSE =
      """
      {"data":{"teams":{"nodes":[{"id":"team-uuid","key":"SIM",
        "states":{"nodes":[{"id":"s-triage","name":"Triage","type":"triage"}]},
        "labels":{"nodes":[{"id":"l-deploy","name":"factory:deploy"}]}}]}}}
      """;

  private HttpServer server;
  private final List<String> bodies = new ArrayList<>();
  private final Map<String, String> byOperation = new LinkedHashMap<>();

  @BeforeEach
  void startServer() throws IOException {
    byOperation.put("teams", TEAM_RESPONSE);
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/graphql",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          bodies.add(body);
          String answer = "{\"data\":{}}";
          for (Map.Entry<String, String> entry : byOperation.entrySet()) {
            if (body.contains(entry.getKey())) {
              answer = entry.getValue();
            }
          }
          byte[] out = answer.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private LinearGateway gateway() {
    return new LinearGateway(
        new LinearProperties(
            true,
            "k",
            "http://localhost:" + server.getAddress().getPort() + "/graphql",
            "SIM",
            null,
            false,
            null,
            null),
        new ObjectMapper());
  }

  @Test
  void createsAnIssueInTriageWithThePriorityAndLabel() {
    byOperation.put(
        "issueCreate",
        """
        {"data":{"issueCreate":{"success":true,"issue":{"id":"i9","identifier":"SIM-9",
          "url":"https://linear.app/i/9"}}}}
        """);

    LinearGateway.CreatedIssue created =
        gateway().createIssue("recreate failed on backend", "the body", 1, "factory:deploy");

    assertThat(created.identifier()).isEqualTo("SIM-9");
    assertThat(created.url()).isEqualTo("https://linear.app/i/9");
    String mutation = bodies.get(bodies.size() - 1);
    assertThat(mutation).contains("team-uuid").contains("s-triage").contains("l-deploy");
    assertThat(mutation).contains("\"priority\":1");
  }

  @Test
  void createsAnIssueWithNoLabelWhenTheLabelDoesNotExistOnTheTeam() {
    // A missing label must not lose the finding. It files unlabelled and the runbook says to
    // create the label.
    byOperation.put(
        "issueCreate",
        """
        {"data":{"issueCreate":{"success":true,"issue":{"id":"i1","identifier":"SIM-1",
          "url":"u"}}}}
        """);

    assertThat(gateway().createIssue("t", "b", 3, "factory:bughunter").identifier())
        .isEqualTo("SIM-1");
    assertThat(bodies.get(bodies.size() - 1)).doesNotContain("labelIds");
  }

  @Test
  void failsNonRetryablyWhenIssueCreateReportsUnsuccessful() {
    byOperation.put("issueCreate", """{"data":{"issueCreate":{"success":false}}}""");
    assertThatThrownBy(() -> gateway().createIssue("t", "b", 3, "factory:deploy"))
        .isInstanceOf(LinearApiException.class)
        .hasMessageContaining("issueCreate")
        .extracting(e -> ((LinearApiException) e).retryable())
        .isEqualTo(false);
  }

  @Test
  void attachesTheFingerprintUrl() {
    byOperation.put(
        "attachmentCreate",
        """{"data":{"attachmentCreate":{"success":true,"attachment":{"id":"a1"}}}}""");

    gateway().attachFingerprint("i9", "https://factory.simonrowe.dev/fingerprint/abc");

    assertThat(bodies.get(bodies.size() - 1))
        .contains("attachmentCreate")
        .contains("fingerprint/abc")
        .contains("i9");
  }

  @Test
  void addsAComment() {
    byOperation.put(
        "commentCreate", """{"data":{"commentCreate":{"success":true,"comment":{"id":"c1"}}}}""");

    gateway().addComment("i9", "seen again at deadbeef");

    assertThat(bodies.get(bodies.size() - 1)).contains("commentCreate").contains("deadbeef");
  }

  @Test
  void relatingIssuesIsBestEffortAndNeverThrows() {
    // The regression issue's body always names its predecessor, so the relation is a nicety. If
    // Linear rejects the relation type, losing the link must not lose the ticket.
    byOperation.put(
        "issueRelationCreate", """{"errors":[{"message":"Invalid relation type"}]}""");

    gateway().relateIssues("i9", "i1");

    assertThat(bodies.get(bodies.size() - 1)).contains("issueRelationCreate");
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.linear.LinearGatewayWriteTest"`
Expected: FAIL — `createIssue` does not exist.

- [ ] **Step 3: Add the write methods to `LinearGateway`**

Add these constants beside the existing queries:

```java
  private static final String CREATE_ISSUE =
      "mutation($input:IssueCreateInput!){issueCreate(input:$input){success "
          + "issue{id identifier url}}}";

  private static final String CREATE_ATTACHMENT =
      "mutation($input:AttachmentCreateInput!){attachmentCreate(input:$input){success "
          + "attachment{id}}}";

  private static final String CREATE_COMMENT =
      "mutation($input:CommentCreateInput!){commentCreate(input:$input){success comment{id}}}";

  private static final String CREATE_RELATION =
      "mutation($input:IssueRelationCreateInput!){issueRelationCreate(input:$input){success}}";
```

And these methods:

```java
  /**
   * Creates an issue in the team's Triage state.
   *
   * @param title the issue title
   * @param body the issue description, in Markdown
   * @param priority the Linear priority integer
   * @param labelName the label to apply; skipped when the team has no such label, because a
   *     missing label must not cost the finding
   * @return the created issue
   * @throws LinearApiException on any API fault, or when Linear reports the mutation unsuccessful
   */
  public CreatedIssue createIssue(
      final String title, final String body, final int priority, final String labelName) {
    TeamContext team = teamContext();
    ObjectNode input = objectMapper.createObjectNode();
    input.put("teamId", team.teamId());
    input.put("stateId", team.triageStateId());
    input.put("title", title);
    input.put("description", body);
    input.put("priority", priority);
    String labelId = team.labelIds().get(labelName);
    if (labelId != null) {
      input.putArray("labelIds").add(labelId);
    }
    JsonNode result = execute(CREATE_ISSUE, Map.of("input", input)).path("issueCreate");
    if (!result.path("success").asBoolean(false)) {
      throw new LinearApiException("Linear issueCreate reported failure", false);
    }
    JsonNode issue = result.path("issue");
    return new CreatedIssue(
        issue.path("id").asText(),
        issue.path("identifier").asText(),
        issue.path("url").asText());
  }

  /**
   * Stamps the fingerprint onto an issue. This is what makes the issue findable again.
   *
   * @param issueId the Linear issue UUID
   * @param fingerprintUrl the synthetic key URL
   * @throws LinearApiException on any API fault
   */
  public void attachFingerprint(final String issueId, final String fingerprintUrl) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put("issueId", issueId);
    input.put("url", fingerprintUrl);
    input.put("title", "factory fingerprint");
    JsonNode result = execute(CREATE_ATTACHMENT, Map.of("input", input)).path("attachmentCreate");
    if (!result.path("success").asBoolean(false)) {
      throw new LinearApiException("Linear attachmentCreate reported failure", false);
    }
  }

  /**
   * Adds a comment recording one more occurrence.
   *
   * @param issueId the Linear issue UUID
   * @param body the comment, in Markdown
   * @throws LinearApiException on any API fault
   */
  public void addComment(final String issueId, final String body) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put("issueId", issueId);
    input.put("body", body);
    JsonNode result = execute(CREATE_COMMENT, Map.of("input", input)).path("commentCreate");
    if (!result.path("success").asBoolean(false)) {
      throw new LinearApiException("Linear commentCreate reported failure", false);
    }
  }

  /**
   * Links a regression issue to the issue that claimed to fix it.
   *
   * <p><strong>Best effort by design.</strong> The regression issue's body always names its
   * predecessor, so the relation is a convenience. Losing the link must never lose the ticket, so
   * every fault here is swallowed.
   *
   * @param issueId the new regression issue
   * @param relatedIssueId the completed issue it regressed from
   */
  public void relateIssues(final String issueId, final String relatedIssueId) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put("issueId", issueId);
    input.put("relatedIssueId", relatedIssueId);
    input.put("type", "related");
    try {
      execute(CREATE_RELATION, Map.of("input", input));
    } catch (LinearApiException exception) {
      LoggerFactory.getLogger(LinearGateway.class)
          .warn("Could not link {} as a regression of {}", issueId, relatedIssueId, exception);
    }
  }
```

Add `import org.slf4j.LoggerFactory;` and this record beside `TeamContext`:

```java
  /**
   * An issue Linear has just created.
   *
   * @param id the issue UUID
   * @param identifier the human identifier, e.g. {@code SIM-42}
   * @param url the issue's web URL
   */
  public record CreatedIssue(String id, String identifier, String url) {
  }
```

- [ ] **Step 4: Run both gateway tests to verify they pass**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.linear.*"`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/linear/linear \
        software-factory/src/test/java/com/simonrowe/factory/linear/linear
git commit -m "feat: create, attach, comment and relate Linear issues"
```

---

### Task 8: The filer — orchestration and idempotency

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/domain/IssueFiling.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/domain/FiledIssue.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/service/IssueFiler.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/service/IssueFilerTest.java`

**Interfaces:**
- Produces: `IssueFiling(String producer, List<String> keyParts, String title, String body, String occurrenceDetail, String occurrenceId, String workflowId)`; `FiledIssue(FilingDecision decision, String issueIdentifier, String issueUrl, String fingerprint)`; `IssueFiler(LinearGateway gateway, FilingDecider decider, LinearIssueRepository records, LinearProperties properties, Clock clock)` with `FiledIssue file(IssueFiling filing)`.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.linear.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IssueFilerTest {

  private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

  private final LinearGateway gateway = mock(LinearGateway.class);
  private final LinearIssueRepository records = mock(LinearIssueRepository.class);
  private LinearProperties properties;

  private static IssueFiling filing() {
    return new IssueFiling(
        "deploy",
        List.of("recreate", "backend"),
        "recreate failed on backend",
        "The deploy of deadbeef failed in recreate.",
        "commit deadbeef at 12:00",
        "run-1",
        "deploy-prod");
  }

  private static TrackedIssue issue(final IssueStateType type) {
    return new TrackedIssue("i1", "SIM-1", "https://linear.app/i/1", type, Instant.EPOCH);
  }

  private IssueFiler filer() {
    return new IssueFiler(
        gateway,
        new FilingDecider(),
        records,
        properties,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @BeforeEach
  void configure() {
    properties =
        new LinearProperties(true, "k", null, "SIM", null, false, null, null);
    when(records.save(any(LinearIssueRecord.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(records.findById(anyString())).thenReturn(Optional.empty());
  }

  @Test
  void filesNewThenAttachesTheFingerprint() {
    when(gateway.issuesForFingerprint(anyString())).thenReturn(List.of());
    when(gateway.createIssue(anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(new LinearGateway.CreatedIssue("i9", "SIM-9", "https://linear.app/i/9"));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-9");
    assertThat(filed.fingerprint())
        .isEqualTo(Fingerprint.of("deploy", List.of("recreate", "backend")));
    verify(gateway)
        .createIssue(eq("recreate failed on backend"), anyString(), eq(1), eq("factory:deploy"));
    verify(gateway).attachFingerprint(eq("i9"), anyString());
  }

  @Test
  void commentsOnAnOpenIssueAndNeverCreatesASecond() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.STARTED)));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-1");
    verify(gateway).addComment(eq("i1"), anyString());
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
  }

  @Test
  void doesNothingToLinearWhenAHumanDeclinedIt() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.CANCELED)));

    assertThat(filer().file(filing()).decision()).isEqualTo(FilingDecision.SUPPRESSED);
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    verify(gateway, never()).addComment(anyString(), anyString());
    // Still audited: "we saw it and stayed quiet" is exactly what the trail is for.
    verify(records).save(any(LinearIssueRecord.class));
  }

  @Test
  void filesARegressionNamingThePredecessorInTheBody() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.COMPLETED)));
    when(gateway.createIssue(anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(new LinearGateway.CreatedIssue("i9", "SIM-9", "u"));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.FILED_REGRESSION);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(gateway).createIssue(anyString(), body.capture(), anyInt(), anyString());
    assertThat(body.getValue()).contains("SIM-1");
    verify(gateway).attachFingerprint(eq("i9"), anyString());
    verify(gateway).relateIssues("i9", "i1");
  }

  @Test
  void repairsAPendingAttachmentInsteadOfCreatingASecondIssue() {
    // The one real duplicate risk: issueCreate succeeded, attachmentCreate failed, the activity
    // retried. Without this the retry finds no attachment and files a second ticket.
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    when(records.findById(fingerprint))
        .thenReturn(
            Optional.of(
                LinearIssueRecord.first(fingerprint, "deploy", List.of("recreate", "backend"), NOW)
                    .withPendingAttachment("i9", "SIM-9", "https://linear.app/i/9")));
    when(gateway.issuesForFingerprint(anyString())).thenReturn(List.of());

    FiledIssue filed = filer().file(filing());

    verify(gateway).attachFingerprint(eq("i9"), anyString());
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-9");
  }

  @Test
  void aReplayedOccurrenceMutatesNothing() {
    // An activity retry after a fully successful run must not post a second "it happened again".
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    LinearIssueRecord already =
        LinearIssueRecord.first(fingerprint, "deploy", List.of("recreate", "backend"), NOW)
            .withPendingAttachment("i1", "SIM-1", "u")
            .withAttachmentWritten()
            .withDecision(
                new com.simonrowe.factory.linear.persistence.LinearIssueDecision(
                    NOW, FilingDecision.COMMENTED_EXISTING, "run-1", "deploy-prod", "x"),
                NOW,
                IssueStateType.STARTED);
    when(records.findById(fingerprint)).thenReturn(Optional.of(already));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-1");
    verifyNoInteractions(gateway);
    verify(records, never()).save(any(LinearIssueRecord.class));
  }

  @Test
  void dryRunReadsAndAuditsButMutatesNothingInLinear() {
    properties = new LinearProperties(true, "k", null, "SIM", null, true, null, null);
    when(gateway.issuesForFingerprint(anyString())).thenReturn(List.of());

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(filed.issueIdentifier()).isNull();
    verify(gateway).issuesForFingerprint(anyString());
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    verify(records).save(any(LinearIssueRecord.class));
  }

  @Test
  void propagatesGatewayFaultsSoTemporalCanDecideWhetherToRetry() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenThrow(new LinearApiException("Linear returned 503", true));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> filer().file(filing()))
        .isInstanceOf(LinearApiException.class);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.service.IssueFilerTest"`
Expected: FAIL — `IssueFiler` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.simonrowe.factory.linear.domain;

import java.util.List;

/**
 * One occurrence a producer wants filed.
 *
 * @param producer the producer key, e.g. {@code deploy} — selects the label and priority
 * @param keyParts the structured parts identifying the problem; never agent prose
 * @param title the issue title, which may be agent prose
 * @param body the issue description in Markdown, used only when an issue is created
 * @param occurrenceDetail one line naming this occurrence, used only when commenting
 * @param occurrenceId the producing workflow's run id, so an activity replay is recognised
 * @param workflowId the producing workflow's id, recorded in the audit trail
 */
public record IssueFiling(
    String producer,
    List<String> keyParts,
    String title,
    String body,
    String occurrenceDetail,
    String occurrenceId,
    String workflowId) {

  public IssueFiling {
    keyParts = keyParts == null ? List.of() : List.copyOf(keyParts);
  }
}
```

```java
package com.simonrowe.factory.linear.domain;

/**
 * What the sink did.
 *
 * @param decision the decision taken
 * @param issueIdentifier the issue concerned, e.g. {@code SIM-42}; null for a suppressed
 *     occurrence and for a dry run
 * @param issueUrl the issue's web URL, on the same terms
 * @param fingerprint the fingerprint, so a producer can record it alongside its own outcome
 */
public record FiledIssue(
    FilingDecision decision, String issueIdentifier, String issueUrl, String fingerprint) {
}
```

```java
package com.simonrowe.factory.linear.service;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.persistence.LinearIssueDecision;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Files one occurrence: fingerprint it, ask Linear what already carries that fingerprint, decide,
 * act, and record.
 *
 * <p>Ordering is deliberate. The Mongo record is written with {@code attachmentPending} between
 * {@code issueCreate} and {@code attachmentCreate}, so a retry after a half-completed filing
 * repairs by attaching rather than by creating a second ticket.
 */
@Component
public class IssueFiler {

  private static final Logger log = LoggerFactory.getLogger(IssueFiler.class);

  private final LinearGateway gateway;
  private final FilingDecider decider;
  private final LinearIssueRepository records;
  private final LinearProperties properties;
  private final Clock clock;

  /**
   * Creates the filer.
   *
   * @param gateway the Linear API
   * @param decider the precedence rules
   * @param records the audit collection
   * @param properties the bound {@code factory.linear} configuration
   * @param clock injected so tests can pin timestamps
   */
  public IssueFiler(
      final LinearGateway gateway,
      final FilingDecider decider,
      final LinearIssueRepository records,
      final LinearProperties properties,
      final Clock clock) {
    this.gateway = gateway;
    this.decider = decider;
    this.records = records;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Files an occurrence, exactly once per distinct problem.
   *
   * @param filing the occurrence
   * @return what was done
   */
  public FiledIssue file(final IssueFiling filing) {
    Instant now = clock.instant();
    String fingerprint = Fingerprint.of(filing.producer(), filing.keyParts());
    String fingerprintUrl = Fingerprint.urlFor(properties.fingerprintBaseUrl(), fingerprint);

    Optional<LinearIssueRecord> stored = records.findById(fingerprint);
    LinearIssueRecord record =
        stored.orElseGet(
            () ->
                LinearIssueRecord.first(
                    fingerprint, filing.producer(), filing.keyParts(), now));

    if (stored.isPresent() && stored.get().hasOccurrence(filing.occurrenceId())) {
      log.info(
          "Occurrence {} already filed for fingerprint {}; treating as a replay",
          filing.occurrenceId(),
          fingerprint);
      LinearIssueDecision previous = lastDecisionFor(stored.get(), filing.occurrenceId());
      return new FiledIssue(
          previous.decision(),
          stored.get().issueIdentifier(),
          stored.get().issueUrl(),
          fingerprint);
    }

    if (record.attachmentPending() && record.issueId() != null) {
      log.warn(
          "Repairing a pending fingerprint attachment on {} rather than filing a duplicate",
          record.issueIdentifier());
      gateway.attachFingerprint(record.issueId(), fingerprintUrl);
      record = record.withAttachmentWritten();
      records.save(record);
      return finish(record, FilingDecision.COMMENTED_EXISTING, filing, now, null, fingerprint);
    }

    List<TrackedIssue> carrying = gateway.issuesForFingerprint(fingerprintUrl);
    FilingDecider.Outcome outcome = decider.decide(carrying);
    IssueStateType observed = outcome.subject() == null ? null : outcome.subject().stateType();

    if (properties.dryRun()) {
      log.info(
          "Dry run: would {} for fingerprint {} ({} issues carry it)",
          outcome.decision(),
          fingerprint,
          carrying.size());
      return finish(record, outcome.decision(), filing, now, observed, fingerprint);
    }

    LinearProperties.Producer policy = properties.producerFor(filing.producer());
    switch (outcome.decision()) {
      case FILED_NEW -> record = createAndAttach(record, filing, filing.body(), policy,
          fingerprintUrl, null);
      case COMMENTED_EXISTING -> gateway.addComment(
          outcome.subject().id(), occurrenceComment(filing));
      case SUPPRESSED -> log.info(
          "Fingerprint {} was declined on {}; staying quiet",
          fingerprint,
          outcome.subject().identifier());
      case FILED_REGRESSION -> record = createAndAttach(record, filing,
          regressionBody(filing, outcome.subject()), policy, fingerprintUrl,
          outcome.subject().id());
      default -> throw new IllegalStateException("Unhandled decision " + outcome.decision());
    }

    if (outcome.decision() == FilingDecision.COMMENTED_EXISTING) {
      record =
          new LinearIssueRecord(
              record.id(), record.producer(), record.fingerprintVersion(), record.keyParts(),
              outcome.subject().id(), outcome.subject().identifier(), outcome.subject().url(),
              false, record.firstFiledAt(), record.lastSeenAt(), record.occurrences(),
              observed, record.decisions());
    }
    return finish(record, outcome.decision(), filing, now, observed, fingerprint);
  }

  private LinearIssueRecord createAndAttach(
      final LinearIssueRecord record,
      final IssueFiling filing,
      final String body,
      final LinearProperties.Producer policy,
      final String fingerprintUrl,
      final String regressedFromIssueId) {
    LinearGateway.CreatedIssue created =
        gateway.createIssue(filing.title(), body, policy.priority(), policy.label());
    // Written BEFORE the attachment, so a failure between the two is recoverable.
    LinearIssueRecord pending =
        record.withPendingAttachment(created.id(), created.identifier(), created.url());
    records.save(pending);
    gateway.attachFingerprint(created.id(), fingerprintUrl);
    if (regressedFromIssueId != null) {
      gateway.relateIssues(created.id(), regressedFromIssueId);
    }
    return pending.withAttachmentWritten();
  }

  private FiledIssue finish(
      final LinearIssueRecord record,
      final FilingDecision decision,
      final IssueFiling filing,
      final Instant now,
      final IssueStateType observed,
      final String fingerprint) {
    LinearIssueRecord saved =
        records.save(
            record.withDecision(
                new LinearIssueDecision(
                    now,
                    decision,
                    filing.occurrenceId(),
                    filing.workflowId(),
                    filing.occurrenceDetail()),
                now,
                observed));
    return new FiledIssue(decision, saved.issueIdentifier(), saved.issueUrl(), fingerprint);
  }

  private static LinearIssueDecision lastDecisionFor(
      final LinearIssueRecord record, final String occurrenceId) {
    return record.decisions().stream()
        .filter(d -> occurrenceId.equals(d.occurrenceId()))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private static String occurrenceComment(final IssueFiling filing) {
    return "Seen again: " + filing.occurrenceDetail();
  }

  private static String regressionBody(final IssueFiling filing, final TrackedIssue predecessor) {
    return filing.body()
        + "\n\n---\n\nThis is a regression of "
        + predecessor.identifier()
        + " ("
        + predecessor.url()
        + "), which was marked complete. Same fingerprint, new occurrence: "
        + filing.occurrenceDetail();
  }
}
```

Also add a `Clock` bean — put it in a new `config/LinearBeans.java`:

```java
package com.simonrowe.factory.linear.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Collaborators the Linear module needs that Spring does not provide by default. */
@Configuration
public class LinearBeans {

  /**
   * The system clock, injected rather than called statically so filing timestamps are pinnable in
   * tests.
   *
   * @return the UTC system clock
   */
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock clock() {
    return Clock.systemUTC();
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.service.IssueFilerTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Run the whole module's tests**

Run: `./gradlew :software-factory:test`
Expected: PASS. Nothing outside `com.simonrowe.factory.linear` has changed yet.

- [ ] **Step 6: Commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/linear \
        software-factory/src/test/java/com/simonrowe/factory/linear
git commit -m "feat: file Linear issues idempotently, with dry-run and attachment repair"
```

---

### Task 9: The Temporal activity and the credential gate

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/workflow/LinearActivities.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/linear/workflow/LinearActivitiesImpl.java`
- Modify: `software-factory/src/main/resources/application.yml`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/workflow/LinearActivitiesImplTest.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/linear/config/LinearWorkerRegistrationTest.java`

**Interfaces:**
- Produces: `@ActivityInterface LinearActivities` with `@ActivityMethod FiledIssue fileIssue(IssueFiling filing)`; `LinearActivitiesImpl` annotated `@ActivityImpl(taskQueues = LinearTaskQueues.LINEAR)` and `@ConditionalOnProperty(name = "factory.linear.enabled", havingValue = "true")`.

**RESOLVED — do not build the fallback.** Research item 7 settled this against
`io.temporal:temporal-spring-boot-starter:1.36.0`:
`WorkersTemplate.configureActivityBeansByTaskQueue` iterates every `@ActivityImpl` bean's
declared task queues and creates a worker when `workerFactory.tryGetWorker` returns null.
Activity discovery is `beanFactory.getBeansWithAnnotation(ActivityImpl.class)`, gated by
`register-activity-beans` (already `true`) and **independent of `workflow-packages`**.
So: **no `FileIssueWorkflow` stub, and no `workflow-packages` entry** — that list drives
`@WorkflowImpl` scanning only, and an entry for a workflow-less package does nothing while
falsely implying one lives there. Add a comment in that block instead.

- [ ] **Step 1: Write the failing activity test**

```java
package com.simonrowe.factory.linear.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.service.IssueFiler;
import io.temporal.failure.ApplicationFailure;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearActivitiesImplTest {

  private final IssueFiler filer = mock(IssueFiler.class);
  private final LinearActivitiesImpl activities = new LinearActivitiesImpl(filer);

  private static IssueFiling filing() {
    return new IssueFiling("deploy", List.of("recreate", "backend"), "t", "b", "d", "run-1", "w");
  }

  @Test
  void returnsWhatTheFilerDecided() {
    when(filer.file(any(IssueFiling.class)))
        .thenReturn(new FiledIssue(FilingDecision.FILED_NEW, "SIM-9", "url", "fp"));

    assertThat(activities.fileIssue(filing()).issueIdentifier()).isEqualTo("SIM-9");
  }

  @Test
  void mapsANonRetryableFaultToANonRetryableApplicationFailure() {
    // Temporal retries every exception by default. A revoked or read-only API key would then
    // burn the whole retry budget on a fault that cannot resolve itself.
    when(filer.file(any(IssueFiling.class)))
        .thenThrow(new LinearApiException("Linear rejected the API key with 401", false));

    assertThatThrownBy(() -> activities.fileIssue(filing()))
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(e -> assertThat(((ApplicationFailure) e).isNonRetryable()).isTrue());
  }

  @Test
  void letsARetryableFaultPropagateSoTemporalRetriesIt() {
    when(filer.file(any(IssueFiling.class)))
        .thenThrow(new LinearApiException("Linear returned 503", true));

    assertThatThrownBy(() -> activities.fileIssue(filing()))
        .isInstanceOf(LinearApiException.class);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.workflow.LinearActivitiesImplTest"`
Expected: FAIL — `LinearActivitiesImpl` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.simonrowe.factory.linear.workflow;

import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.IssueFiling;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The sink's only activity.
 *
 * <p>Scheduled by producer workflows with {@code ActivityOptions.setTaskQueue(
 * LinearTaskQueues.LINEAR)}, so the filing executes in whichever container polls that queue —
 * {@code software-factory} — rather than in the container that ran the producing workflow. That is
 * what keeps {@code LINEAR_API_KEY} off the {@code deployer}, which holds the Docker socket.
 */
@ActivityInterface
public interface LinearActivities {

  /**
   * Files one occurrence into Linear, exactly once per distinct problem.
   *
   * @param filing the occurrence
   * @return what was done
   */
  @ActivityMethod
  FiledIssue fileIssue(IssueFiling filing);
}
```

```java
package com.simonrowe.factory.linear.workflow;

import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.service.IssueFiler;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The executor half of the sink.
 *
 * <p><strong>Do not remove the {@code @ConditionalOnProperty}.</strong> Both {@code
 * software-factory} and {@code deployer} run this image; this annotation is the only thing that
 * stops the {@code deployer} — the container holding a root-equivalent Docker socket — needing a
 * tracker credential. It is evaluated by the component scanner, so declaring this class through an
 * explicit {@code @Bean} method would register it unconditionally and silently ignore the
 * annotation. {@code LinearWorkerRegistrationTest} pins the behaviour.
 */
@Component
@ActivityImpl(taskQueues = LinearTaskQueues.LINEAR)
@ConditionalOnProperty(name = "factory.linear.enabled", havingValue = "true")
public class LinearActivitiesImpl implements LinearActivities {

  private final IssueFiler filer;

  /**
   * Creates the activity implementation.
   *
   * @param filer the orchestration this activity is a thin shell over
   */
  public LinearActivitiesImpl(final IssueFiler filer) {
    this.filer = filer;
  }

  @Override
  public FiledIssue fileIssue(final IssueFiling filing) {
    try {
      return filer.file(filing);
    } catch (LinearApiException exception) {
      if (exception.retryable()) {
        // Let it out: Temporal's retry policy is the right place to back off a 429 or a 5xx.
        throw exception;
      }
      throw ApplicationFailure.newNonRetryableFailure(
          exception.getMessage(), "LinearApiError", filing.producer());
    }
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.workflow.LinearActivitiesImplTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Document the activity-only queue — add NO list entry**

In `application.yml`, add a comment to the `spring.temporal.workers-auto-discovery` block, in
the voice of the two already there. **Do not add a `workflow-packages` entry** (see above).

```yaml
        # `linear` is this application's FIRST ACTIVITY-ONLY task queue and deliberately has no
        # entry in this list: no @WorkflowImpl names it, and this list drives @WorkflowImpl
        # scanning only. Its worker is created from LinearActivitiesImpl's
        # @ActivityImpl(taskQueues = ...) alone, which `register-activity-beans: true` below is
        # what enables — flipping that setting silently leaves this queue unpolled. Verified
        # against starter 1.36.0 in specs/039-linear-issue-sink/research.md item 7. If it ever
        # stops holding, the symptom is the usual quiet one: the container is healthy, a
        # producer schedules fileIssue, and the activity sits in the queue until its
        # schedule-to-close timeout.
        #
        # Only `software-factory` sets factory.linear.enabled, so only `software-factory` holds
        # LinearActivitiesImpl and only it polls this queue. The `deployer` never receives
        # LINEAR_API_KEY.
```

- [ ] **Step 6: Write the registration gate test**

```java
package com.simonrowe.factory.linear.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.linear.persistence.LinearIndexInitializer;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import com.simonrowe.factory.linear.workflow.LinearActivitiesImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Pins the credential gate.
 *
 * <p><strong>Do not delete these tests.</strong> {@link LinearActivitiesImpl}'s
 * {@code @ConditionalOnProperty} is the only thing that keeps a tracker credential out of the
 * {@code deployer}, the container holding the Docker socket. Both containers run the same image.
 *
 * <p>This component-scans rather than declaring the beans directly, for the reason {@code
 * DeployWorkerRegistrationTest} documents at length: a class-level {@code @ConditionalOnProperty}
 * is evaluated by the component scanner, not the bean factory, so a harness that declares the
 * class through an explicit {@code @Bean} method tests nothing about production.
 */
class LinearWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedLinearPackage.class);

  @Test
  void theSinkIsAbsentByDefault() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(LinearActivitiesImpl.class);
          // And nothing touches Mongo, for the reason CveFixIndexInitializer documents.
          assertThat(context).doesNotHaveBean(LinearIndexInitializer.class);
        });
  }

  @Test
  void theSinkIsAbsentWhenTheFlagIsExplicitlyFalse() {
    runner
        .withPropertyValues("factory.linear.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(LinearActivitiesImpl.class));
  }

  @Test
  void theSinkIsPresentOnlyWhenTheFlagIsTrue() {
    runner
        .withPropertyValues("factory.linear.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(LinearActivitiesImpl.class);
              assertThat(context).hasSingleBean(LinearIndexInitializer.class);
            });
  }

  @Test
  void theDeployerShapeHoldsNoFilingImplementation() {
    // The combination that matters: the container with the Docker socket must hold no
    // implementation that reads LINEAR_API_KEY.
    runner
        .withPropertyValues("factory.deploy.enabled=true", "factory.linear.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(LinearActivitiesImpl.class));
  }

  /** A real component scan of the linear package, with external collaborators mocked. */
  @Configuration
  @ComponentScan("com.simonrowe.factory.linear")
  @EnableConfigurationProperties(LinearProperties.class)
  static class ScannedLinearPackage {

    @Bean
    LinearIssueRepository linearIssueRepository() {
      return mock(LinearIssueRepository.class);
    }

    @Bean
    MongoTemplate mongoTemplate() {
      return mock(MongoTemplate.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
```

- [ ] **Step 7: Run it to verify it passes**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.linear.config.LinearWorkerRegistrationTest"`
Expected: PASS, 4 tests.

- [ ] **Step 8: Commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/linear/workflow \
        software-factory/src/test/java/com/simonrowe/factory/linear \
        software-factory/src/main/resources/application.yml
git commit -m "feat: expose the Linear sink as a gated Temporal activity"
```

---

### Task 10: Retrofit the deploy producer

The GitHub issue goes; the commit comment stays and gains the Linear URL. `DeployReportRenderer.issueTitle` and `issueBody` are **kept unchanged** — they already render exactly what a Linear issue wants, so this is a re-target, not a rewrite.

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/deploy/domain/DeployRequest.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/deploy/api/DeployWorkflowService.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/deploy/workflow/DeployActivities.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/deploy/workflow/DeployActivitiesImpl.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/deploy/workflow/DeployWorkflowImpl.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/deploy/github/DeployReportGateway.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/deploy/github/DeployReportRenderer.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/deploy/persistence/DeployRunRecord.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/deploy/workflow/DeployWorkflowTest.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/deploy/persistence/DeployRunRepositoryTest.java` (fixture only)

**Interfaces:**
- Consumes: `LinearActivities.fileIssue(IssueFiling)` → `FiledIssue`; `LinearTaskQueues.LINEAR`; `LinearProperties.enabled()`.
- Produces: `DeployRequest` gains a trailing `boolean linearFilingEnabled`; `DeployActivities.report(DeployRunRecord record, Triage triage, Long installationId, String linearIssueUrl)` returning `Report(String commitCommentUrl)`; `DeployRunRecord` gains a trailing `boolean linearFilingFailed`.

- [ ] **Step 1: Write the failing workflow test**

Add to the existing `DeployWorkflowTest`, following whatever `TestWorkflowEnvironment` harness that class already uses. Register `LinearActivities` on the `linear` task queue in the test environment.

```java
  @Test
  void aFailedDeployFilesToLinearBeforeCommentingAndPassesTheUrlToTheReport() {
    // The order is deliberate: the commit comment names the ticket, so the ticket has to exist
    // before the comment is written.
    givenAFailingVerifyPhase();
    when(linearActivities.fileIssue(any()))
        .thenReturn(new FiledIssue(FilingDecision.FILED_NEW, "SIM-9",
            "https://linear.app/i/9", "fp"));

    DeployResult result = runDeploy(requestWithLinearFilingEnabled(true));

    ArgumentCaptor<IssueFiling> filing = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linearActivities).fileIssue(filing.capture());
    assertThat(filing.getValue().producer()).isEqualTo("deploy");
    // Phase and status, never the agent's headline: the fingerprint must be deterministic.
    assertThat(filing.getValue().keyParts()).containsExactly("verify", "ROLLED_BACK");
    verify(deployActivities)
        .report(any(), any(), any(), eq("https://linear.app/i/9"));
    assertThat(result.issueUrl()).isEqualTo("https://linear.app/i/9");
  }

  @Test
  void aDeployStillRollsBackAndReportsWhenLinearFilingFails() {
    // The tracker being down must never change the deploy's outcome.
    givenAFailingVerifyPhase();
    when(linearActivities.fileIssue(any()))
        .thenThrow(ApplicationFailure.newNonRetryableFailure("Linear down", "LinearApiError"));

    DeployResult result = runDeploy(requestWithLinearFilingEnabled(true));

    assertThat(result.status()).isEqualTo(DeployStatus.ROLLED_BACK);
    verify(deployActivities).report(any(), any(), any(), eq(null));
    ArgumentCaptor<DeployRunRecord> saved = ArgumentCaptor.forClass(DeployRunRecord.class);
    verify(deployActivities).persist(saved.capture());
    assertThat(saved.getValue().linearFilingFailed()).isTrue();
  }

  @Test
  void nothingIsFiledWhenTheSinkIsDisabled() {
    // With factory.linear.enabled false nothing polls the `linear` queue, so scheduling the
    // activity at all would stall the deploy until its schedule-to-close timeout.
    givenAFailingVerifyPhase();

    runDeploy(requestWithLinearFilingEnabled(false));

    verifyNoInteractions(linearActivities);
    verify(deployActivities).report(any(), any(), any(), eq(null));
  }

  @Test
  void aSuccessfulDeployFilesNothing() {
    givenEveryPhasePassing();
    runDeploy(requestWithLinearFilingEnabled(true));
    verifyNoInteractions(linearActivities);
  }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.deploy.workflow.DeployWorkflowTest"`
Expected: FAIL — `DeployRequest` has no `linearFilingEnabled`, `report` has three parameters.

- [ ] **Step 3: Widen the request and the trigger**

In `DeployRequest`, add a trailing component and document it:

```java
 * @param linearFilingEnabled whether to file a failure into Linear. Carried on the request rather
 *     than read from configuration because a {@code @WorkflowImpl} is instantiated by the Temporal
 *     SDK and cannot inject properties — and because with the sink disabled nothing polls the
 *     {@code linear} queue, so scheduling the activity would stall the deploy until its
 *     schedule-to-close timeout.
```

In `DeployWorkflowService`, inject `LinearProperties` and pass `linearProperties.enabled()` when building the `DeployRequest`. Update every other `new DeployRequest(...)` call site the compiler flags — tests included.

- [ ] **Step 4: Re-target reporting**

- `DeployActivities`: change `Report report(DeployRunRecord record, Triage triage, Long installationId)` to `Report report(DeployRunRecord record, Triage triage, Long installationId, String linearIssueUrl)`, and reduce `Report` to `record Report(String commitCommentUrl)`.
- `DeployActivitiesImpl.report`: delete the `openIssue` call; pass `linearIssueUrl` to the renderer.
- `DeployReportGateway`: delete `openIssue` and its `API_VERSION`-versioned issues path. Update the class Javadoc — it currently says "a comment on the deployed commit, and a tracked issue"; the tracked issue now lives in Linear.
- `DeployReportRenderer.commitComment`: take the Linear issue URL and, when non-null, render a line naming it. Keep `issueTitle` and `issueBody` untouched — they are now the Linear title and body.
- `DeployRunRecord`: add a trailing `boolean linearFilingFailed`. Its existing `issueUrl` field now carries the Linear URL; update its Javadoc to say so.

- [ ] **Step 5: File before reporting in the workflow**

In `DeployWorkflowImpl`, add the cross-queue stub beside the existing three:

```java
  /**
   * The issue sink, on its own task queue.
   *
   * <p>Executed by {@code software-factory}, not by the {@code deployer} that runs every other
   * activity here — that is what keeps the tracker credential off the container holding the Docker
   * socket.
   *
   * <p>2m schedule-to-close, deliberately short. With {@code factory.linear.enabled} false nothing
   * polls this queue, and a misconfiguration must cost the deploy two minutes, not the default.
   * {@code linearFilingEnabled} on the request is the primary guard; this is the backstop.
   */
  private final LinearActivities linear =
      Workflow.newActivityStub(
          LinearActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(LinearTaskQueues.LINEAR)
              .setStartToCloseTimeout(Duration.ofSeconds(90))
              .setScheduleToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(INFRASTRUCTURE_RETRIES)
              .build());
```

In `reportAndFinish`, before the `fast.report(...)` call:

```java
    String linearIssueUrl = null;
    boolean linearFilingFailed = false;
    if (request.linearFilingEnabled() && status != DeployStatus.DEPLOYED) {
      // Rendering happens in an activity, not here: a @WorkflowImpl must stay deterministic and
      // holds no Spring bean, so it can reach neither DeployReportRenderer nor the phase output
      // that names the failing service.
      DeployActivities.Rendered rendered = fast.renderFailure(provisional, triage);
      try {
        FiledIssue filed =
            linear.fileIssue(
                new IssueFiling(
                    "deploy",
                    // Structured, never the agent's headline: two phrasings of one failure must
                    // not become two tickets. Commit deliberately excluded — see the design.
                    // Structured enum values already in scope: no parsing, no agent prose.
                    List.of(
                        failingPhase.name().toLowerCase(java.util.Locale.ROOT), status.name()),
                    rendered.title(),
                    rendered.body(),
                    "commit "
                        + provisional.sha()
                        + ", workflow run "
                        + Workflow.getInfo().getRunId(),
                    Workflow.getInfo().getRunId(),
                    Workflow.getInfo().getWorkflowId()));
        linearIssueUrl = filed.issueUrl();
      } catch (ActivityFailure failure) {
        // The tracker is not allowed to change the deploy's outcome. Rollback has already run.
        Workflow.getLogger(DeployWorkflowImpl.class)
            .warn("Could not file the deploy failure into Linear", failure);
        linearFilingFailed = true;
      }
    }
```

Add the rendering activity to `DeployActivities`:

```java
  /**
   * Renders a failure for the issue sink.
   *
   * <p>The title and body are the existing {@code DeployReportRenderer.issueTitle} and {@code
   * issueBody} — re-targeted from GitHub to Linear, not rewritten. Rendering is an activity
   * because a {@code @WorkflowImpl} holds no Spring bean and cannot reach the renderer.
   *
   * <p>It deliberately does NOT supply the fingerprint key parts. Those are the failing phase and
   * the deploy status, both already in workflow scope as parameters of {@code reportAndFinish} —
   * structured enum values rather than agent prose, which is what a fingerprint requires.
   *
   * @param record the run so far
   * @param triage the agent's diagnosis, which may be null
   * @return the rendered title and body
   */
  Rendered renderFailure(DeployRunRecord record, Triage triage);

  /**
   * A failure rendered for filing.
   *
   * @param title the issue title, agent prose — never part of the fingerprint
   * @param body the issue description, in Markdown
   */
  record Rendered(String title, String body) {
  }
```

Implement it in `DeployActivitiesImpl` by delegating to the existing
`DeployReportRenderer.issueTitle` / `issueBody`, which are otherwise unchanged.

**Key parts corrected 2026-08-27.** The design said "failing phase + failing service". The
phase is structured and already a parameter of `reportAndFinish`, but **the failing service is
not structured anywhere**: `PhaseOutcome.detail` is documented as "the trimmed tail of the
phase's output, bounded to 4000 characters... it is not a log store". Deriving a service name
from it means inventing a parser for `restart-prod.sh` failure output that nobody has observed
in production — precisely the invented taxonomy this design deferred for lack of evidence.
`DeployStatus` is structured, deterministic, in scope, and separates the cases that genuinely
differ: `FAILED` (before recreate), `ROLLED_BACK`, `ROLLBACK_FAILED`, `ROLLBACK_DISABLED`.

Then pass `linearIssueUrl` into `fast.report(provisional, triage, request.installationId(), linearIssueUrl)`, and `linearFilingFailed` into the `DeployRunRecord` built at the end.

- [ ] **Step 6: Run the deploy tests to verify they pass**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.deploy.*"`
Expected: PASS. Fix `DeployRunRepositoryTest`'s fixture for the new trailing field.

- [ ] **Step 7: Run the whole module**

Run: `./gradlew :software-factory:test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/deploy \
        software-factory/src/test/java/com/simonrowe/factory/deploy
git commit -m "feat: file deploy failures into Linear instead of GitHub Issues"
```

---

### Task 11: Retrofit the cvefix producer

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/cvefix/domain/CveFixRequest.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/cvefix/schedule/CveFixScheduleInitializer.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/cvefix/workflow/CveFixActivities.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/cvefix/workflow/CveFixActivitiesImpl.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/cvefix/workflow/CveFixWorkflowImpl.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/cvefix/workflow/CveFixActivitiesImplTest.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/cvefix/workflow/CveFixWorkflowTest.java`

**Interfaces:**
- Consumes: `LinearActivities.fileIssue(IssueFiling)`; `LinearTaskQueues.LINEAR`; `LinearProperties.enabled()`.
- Produces: `CveFixRequest` gains a trailing `boolean linearFilingEnabled`; `CveFixActivities.recordUnfixable` changes from `void` to `List<UnfixableComponent> recordUnfixable(List<UnfixableComponent> unfixable, List<ComponentFindings> components)`, returning only the components it *newly* recorded.

- [ ] **Step 1: Write the failing activity test**

`UnfixableFindingRecord`'s Javadoc already defines the condition: *"A later run re-attempts as soon as the current fingerprint differs from the stored one."* That is exactly "new information", so it is also exactly what should file.

```java
  @Test
  void returnsOnlyTheComponentsItNewlyRecorded() {
    // One component already recorded with the same fingerprint, one with a different fingerprint,
    // one never seen. Only the last two are new information and only they should file.
    givenStored("pkg:maven/a/b@1", "pkg:maven/a/b@1|CVE-1");
    givenStored("pkg:maven/c/d@1", "pkg:maven/c/d@1|CVE-OLD");

    List<UnfixableComponent> newlyRecorded =
        activities.recordUnfixable(
            List.of(
                unfixable("pkg:maven/a/b@1", "no fix published"),
                unfixable("pkg:maven/c/d@1", "no fix published"),
                unfixable("pkg:npm/e@1", "no fix published")),
            List.of(
                componentFindings("pkg:maven/a/b@1", "CVE-1"),
                componentFindings("pkg:maven/c/d@1", "CVE-NEW"),
                componentFindings("pkg:npm/e@1", "CVE-2")));

    assertThat(newlyRecorded)
        .extracting(UnfixableComponent::purl)
        .containsExactlyInAnyOrder("pkg:maven/c/d@1", "pkg:npm/e@1");
  }

  @Test
  void aRepeatedGiveUpOnUnchangedFindingsRecordsNothingNew() {
    // Without this the daily schedule would comment on the same ticket every 24 hours forever.
    givenStored("pkg:maven/a/b@1", "pkg:maven/a/b@1|CVE-1");

    assertThat(
            activities.recordUnfixable(
                List.of(unfixable("pkg:maven/a/b@1", "no fix published")),
                List.of(componentFindings("pkg:maven/a/b@1", "CVE-1"))))
        .isEmpty();
  }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.cvefix.workflow.CveFixActivitiesImplTest"`
Expected: FAIL — `recordUnfixable` returns `void`.

- [ ] **Step 3: Change the activity to report what it recorded**

In `CveFixActivities`, change the signature and document the new contract:

```java
  /**
   * Records the components the agent declined to bump.
   *
   * @param unfixable the components the agent declined to bump, from this run
   * @param components the current finding set, whose per-component fingerprints decide what counts
   *     as new information
   * @return only the components newly recorded — absent before, or stored under a different
   *     fingerprint. The daily schedule re-runs this with an unchanged finding set most days, and
   *     returning those would comment on the same Linear ticket every 24 hours forever.
   */
  List<UnfixableComponent> recordUnfixable(
      List<UnfixableComponent> unfixable, List<ComponentFindings> components);
```

In `CveFixActivitiesImpl`, collect and return the subset whose stored `fingerprint` was absent or different before the upsert.

- [ ] **Step 4: Widen the request and set it from configuration**

Add a trailing `boolean linearFilingEnabled` to `CveFixRequest`, with the same Javadoc rationale as `DeployRequest`. In `CveFixScheduleInitializer`, inject `LinearProperties` and pass `linearProperties.enabled()` into the scheduled `CveFixRequest`. This is the pattern that class already documents: *"The scheduled request carries the CI settings rather than the workflow reading them, because `@WorkflowImpl` classes are instantiated by the Temporal SDK and cannot inject `CveFixProperties`."*

- [ ] **Step 5: File one issue per newly-recorded component**

In `CveFixWorkflowImpl`, add the same cross-queue `LinearActivities` stub as Task 10 Step 5. At each of the five `recordUnfixable` call sites, capture the returned list and file for each entry:

```java
  private void fileUnfixable(
      final CveFixRequest request, final List<UnfixableComponent> newlyRecorded) {
    if (!request.linearFilingEnabled()) {
      return;
    }
    for (UnfixableComponent component : newlyRecorded) {
      try {
        linear.fileIssue(
            new IssueFiling(
                "cvefix",
                // The component purl alone: the key UnfixableFindingRecord already uses, so one
                // ticket per component however many advisories accumulate against it.
                List.of(component.purl()),
                "Cannot auto-fix " + component.purl(),
                unfixableBody(component),
                "run " + Workflow.getInfo().getRunId(),
                Workflow.getInfo().getRunId() + ":" + component.purl(),
                Workflow.getInfo().getWorkflowId()));
      } catch (ActivityFailure failure) {
        // The suppression record is already written; the ticket is a nicety by comparison.
        Workflow.getLogger(CveFixWorkflowImpl.class)
            .warn("Could not file {} into Linear", component.purl(), failure);
      }
    }
  }
```

Note the `occurrenceId` is the run id **plus the purl**: one run files several components, and a bare run id would make the second component look like a replay of the first.

- [ ] **Step 6: Run the cvefix tests, then the module**

Run: `./gradlew :software-factory:test --tests "com.simonrowe.factory.cvefix.*"` then `./gradlew :software-factory:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
./gradlew :software-factory:check
git add software-factory/src/main/java/com/simonrowe/factory/cvefix \
        software-factory/src/test/java/com/simonrowe/factory/cvefix
git commit -m "feat: file unfixable CVE findings into Linear"
```

---

### Task 12: Compose wiring and documentation

**Files:**
- Modify: `docker-compose.prod.yml`
- Create: `docs/runbooks/linear.md`
- Modify: `docs/runbooks/software-factory-manual-actions.md`
- Modify: `docs/runbooks/software-factory.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add the environment to `software-factory` only**

In `docker-compose.prod.yml`, under the `software-factory` service's `environment:`:

```yaml
      # The issue sink. Deliberately NOT set on `deployer`, which runs this same image: that
      # container holds /var/run/docker.sock and must hold as few other credentials as possible.
      FACTORY_LINEAR_ENABLED: ${FACTORY_LINEAR_ENABLED:-false}
      FACTORY_LINEAR_TEAM_KEY: ${FACTORY_LINEAR_TEAM_KEY:-}
      FACTORY_LINEAR_DRY_RUN: ${FACTORY_LINEAR_DRY_RUN:-false}
      LINEAR_API_KEY: ${LINEAR_API_KEY:-}
```

Add nothing to the `deployer` service. **Add a test that fails if someone ever does** — the
`@ConditionalOnProperty` gate keeps the credential out of the JVM, but nothing today stops a
compose edit handing `deployer` the key: parse `docker-compose.prod.yml` and assert no `LINEAR_`
variable appears under the `deployer` service. Then add a comment there stating the omission is
deliberate, so a future tidy-up does not "fix" it:

```yaml
      # No LINEAR_* here on purpose. This container holds the Docker socket; the tracker
      # credential lives only on `software-factory`. See docs/runbooks/linear.md.
```

- [ ] **Step 2: Write `docs/runbooks/linear.md`**

Cover, in this order: what the sink is and what it is not; the decision table with the open > canceled > completed precedence and the reopen-to-un-suppress property; the human prerequisites; the rollout order from the design; how to read `linear_issues` (`docker exec ... mongosh software_factory --eval 'db.linear_issues.find().sort({lastSeenAt:-1}).limit(5).pretty()'`); the `temporal task-queue describe --task-queue linear` check and the fact that **one activity poller and zero workflow pollers is the expected shape**; and the failure modes — filing failed but the producer succeeded, a fingerprint version bump orphaning tickets, and Triage not enabled on the team.

- [ ] **Step 3: Append the human prerequisites to `software-factory-manual-actions.md`**

The three from the design: team created with **Triage enabled** (per-team toggle, the suppression design depends on it); labels `factory:deploy` and `factory:cvefix`; API key minted and added to prod `.env`. Mark each with its current status, as that file already does for the GitHub App steps.

- [ ] **Step 4: Correct `docs/runbooks/software-factory.md`**

Its opening still says the container *"hosts only `codereview`"* — three modules stale. Replace that with the five-module table (`codereview`, `feedback`, `cvefix`, `deploy`, `linear`) giving each one's task queue, trigger and role, and link to each module's runbook.

- [ ] **Step 5: Update `CLAUDE.md`**

Add a `039-linear-issue-sink` Recent Changes entry covering: the activity-only task queue and its poller shape; the credential confinement and the single annotation that enforces it; open > canceled > completed with reopen-to-un-suppress; `attachmentPending` and why the record is written between create and attach; the `linearFilingEnabled`-on-the-request guard and why an unpolled queue would otherwise stall a deploy; that the deploy GitHub issue is gone; and that bumping the fingerprint's `v1` orphans every ticket.

Add the missing `feedback` module entry too — CLAUDE.md has never mentioned it, and it has been in production since PR #99.

- [ ] **Step 6: Confirm the transitional reduction is over**

Until `FACTORY_LINEAR_ENABLED=true` is live on `software-factory`, a failed deploy's only
human-readable output is the **short commit comment** — headline plus next step. `renderFailure`
sits inside the `linearFilingEnabled` guard, and `DeployRunRecord` persists no triage field, so
with the sink off the agent computes `triage.diagnosis()`, `confidence()`, `errorClass()`, the
`logExcerpts()` and the whole phases/config-sync table **and then discards them**. They used to
go into the GitHub `issueBody`.

That is the intended default-off posture, but it is a real reduction while the flag is off. So
this step is not documentation: after Step 4 enables the flag, **trigger a failure and confirm
the filed Linear issue actually contains the long-form diagnosis**, not just the headline. If it
does not, the sink is enabled but the diagnosis is still being thrown away.

- [ ] **Step 7: Full verification**

```bash
./gradlew :software-factory:check
./gradlew build -x :backend:test
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add docker-compose.prod.yml docs/runbooks CLAUDE.md
git commit -m "docs: document the Linear issue sink and wire its prod environment"
```

---

## Deferred, by design

These are named so a reviewer knows they were considered and declined, not missed:

- **The deploy error-class taxonomy.** The fingerprint is phase + service. Splitting `healthcheck-timeout` from `image-pull-failed` is where this should end up, but the deploy failure path has never fired in production, so any taxonomy today would be invented. The comment trail on the first shared ticket is the evidence to build it from.
- **Linear as a work queue.** Reading a ticket and acting on it — auto-picking-up CVE tickets. The seam is `LinearProperties.producers`; the feature is a separate spec.
- **The other three roadmap features** — the 24-hour production bug-hunter (interval configurable, unlike `cve-fix-daily`'s hardcoded constant), post-deploy verification, and frontend error collection. Each is its own spec; this plan builds only the sink they file into.

## Self-review notes

Checked against the design, section by section.

- **Spec coverage.** Every design section maps to a task: architecture → 2, 9; fingerprint → 3; Linear side → 6, 7; Mongo → 5; decision table → 4; idempotency → 5, 8; failure boundaries → 8, 9, 10, 11; configuration → 2; rollout → 12 and the runbook; testing → each task; research items → 1; human prerequisites → 1, 12.
- **Two gaps found and closed while reviewing.** (1) `DeployWorkflowImpl` cannot render an issue title — it is `@WorkflowImpl` and holds no renderer bean — so Task 10 Step 5 adds a `renderFailure` activity rather than pretending the workflow can call `DeployReportRenderer`. (2) `cvefix` files several components in one run, so a bare run id as `occurrenceId` would make the second component look like a replay of the first; Task 11 Step 5 uses run id + purl.
- **Type consistency.** `FiledIssue`, `IssueFiling`, `TrackedIssue`, `FilingDecision`, `IssueStateType`, `LinearIssueRecord`, `TeamContext` and `CreatedIssue` are used with the same field names in every task that touches them. `recordUnfixable`'s return type is changed in one place (Task 11 Step 3) and consumed in one place (Step 5).
- **Known-and-accepted, both asserted by a test rather than left implicit:** a replay older than 20 decisions can post one duplicate comment; two unrelated changes breaking the same phase share one ticket.
