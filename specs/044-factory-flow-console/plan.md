# Factory Flow Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the seven module cards on `/admin/software-factory` with a twelve-node loop diagram of the Software Factory, every node clickable, sourced from Temporal, Linear and GitHub with no new persistence.

**Architecture:** A new `com.simonrowe.factory.flow` package in `software-factory` owns the topology as code and assembles live counts onto it. Counts for the six workflow-backed modules come from one Temporal `ListWorkflowExecutions` call per status band, keyed on `WorkflowType`; the `Linear` node reads the existing `linear_issues` collection; artifact nodes read the GitHub API. The backend proxies the result, fanning out to both the `software-factory` and `deployer` containers exactly as `FactoryAdminService` already does. The frontend renders it as accessible buttons with a decorative SVG layered over them.

**Tech Stack:** Java 25 / Spring Boot 4.1.1, Temporal Java SDK 1.36.x, Jackson 3 (`tools.jackson.databind`), Spring `RestClient`, Spring Data MongoDB, React 19 / TypeScript 5 / Vite, Vitest, plain CSS with BEM.

## Global Constraints

- Spec: `specs/044-factory-flow-console/spec.md`. Every requirement there is in scope for this plan.
- **Twelve nodes**: seven module nodes (`logwatch`, `cvefix`, `build`, `codereview`, `deploy`, `platformbackup`, `feedback`) and five artifact nodes (`linear`, `pull-request`, `main`, `production`, `agent-setup`). The `linear` *module* is not a node — its health renders as a badge on the `linear` artifact node.
- **`platformbackup` has no edges.** It is attached to `production` visually but participates in no loop. A test asserts it has zero edges.
- **`GET /api/factory/flow` is token-protected.** `GET /api/factory/status` stays unauthenticated and unmodified.
- **No module is edited.** This feature reads only. No new Mongo collection, no Mongock change unit.
- Java is Google Java Style, enforced by Checkstyle; every public type and public method needs Javadoc. Run `../gradlew :software-factory:test :software-factory:checkstyleMain` before each commit.
- Frontend is plain CSS with BEM naming in the single `styles.css`, CSS custom properties for theming.
- Conventional commits (`feat:`, `fix:`, `docs:`, `test:`). No Jira ticket. No Claude attribution in commit messages.
- Temporal workflow types are the **interface simple names** — no `@WorkflowMethod(name = ...)` override exists in this repo. They are exactly: `CodeReviewWorkflow`, `ReviewFeedbackWorkflow`, `CveFixWorkflow`, `DeployWorkflow`, `PlatformBackupWorkflow`, `LogWatchWorkflow`.
- `software-factory` HTTP stub tests are known to flake on port and connection reuse. Re-run a failing stub test isolated three times before attributing the failure to a change.

---

## Task 0: Retire the visibility-filtering risk

This is the one task that can invalidate the rest of the plan. Do it first and do not start Task 3 until it passes.

**Files:**
- Create: `specs/044-factory-flow-console/research.md`

- [ ] **Step 1: Confirm the Temporal server and visibility store**

Run:

```bash
grep -n "temporalio/server\|image: postgres" docker-compose.prod.yml
grep -n "retention" scripts/temporal/create-namespace.sh
```

Expected: `temporalio/server:1.31.2`, `postgres:15`, `TEMPORAL_NAMESPACE_RETENTION:-30d`.

Temporal has supported advanced visibility on Postgres 12+ natively since 1.21, so 1.31.2 on Postgres 15 should accept a `WorkflowType` filter. This step records the versions; the next one proves the behaviour.

- [ ] **Step 2: Prove `WorkflowType` filtering against a real Temporal**

Start the local stack's Temporal only, then query it:

```bash
docker compose -f docker-compose.yml up -d temporal
docker compose -f docker-compose.yml exec temporal \
  temporal workflow count --query "WorkflowType = 'LogWatchWorkflow'" --address temporal:7233
docker compose -f docker-compose.yml exec temporal \
  temporal workflow count \
  --query "WorkflowType = 'LogWatchWorkflow' AND ExecutionStatus = 'Running'" \
  --address temporal:7233
```

Expected: both return a count (`Total: 0` is a pass — the query being *accepted* is what is being tested). A failure looks like `InvalidArgument` naming an unsupported search attribute, and means the plan must fall back to approach (b) in the spec.

- [ ] **Step 3: Prove the `StartTime` range filter too**

```bash
docker compose -f docker-compose.yml exec temporal \
  temporal workflow count \
  --query "WorkflowType = 'LogWatchWorkflow' AND StartTime > '2026-09-03T00:00:00Z'" \
  --address temporal:7233
```

Expected: accepted. The 24-hour counts depend on this specific combination, so it is proved separately rather than assumed from Step 2.

- [ ] **Step 4: Record the result**

Write `specs/044-factory-flow-console/research.md` containing: the Temporal and Postgres versions, the exact queries run, their output, and one sentence stating whether approach (c) is viable. If any step failed, stop and raise it — the remaining tasks assume it passed.

- [ ] **Step 5: Commit**

```bash
git add specs/044-factory-flow-console/research.md
git commit -m "docs: record the Temporal visibility spike for the factory flow console"
```

---

## Task 1: The topology, as code

The graph's shape is a property of the code, not of configuration or of the database. It is defined once here and pinned by a test, so that adding an eighth module without adding it to the graph fails the build — the same failure mode `FactoryAdminService.ORDER` already has, which silently drops a module from the console.

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/domain/NodeKind.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/domain/Band.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/domain/Loop.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/domain/NodeHealth.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/domain/NodeDescriptor.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/domain/FlowEdge.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/FactoryFlowTopology.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/flow/FactoryFlowTopologyTest.java`

**Interfaces:**
- Consumes: `ModulePrerequisites.CODE_REVIEW`, `.FEEDBACK`, `.CVEFIX`, `.DEPLOY`, `.LINEAR`, `.PLATFORM_BACKUP`, `.LOGWATCH` — the existing module key constants.
- Produces: `FactoryFlowTopology.NODES` (`List<NodeDescriptor>`), `FactoryFlowTopology.EDGES` (`List<FlowEdge>`), `FactoryFlowTopology.BUILD` (`String`), and the six domain types above. Tasks 3, 5 and 6 all read `NODES` and `EDGES`.

- [ ] **Step 1: Write the failing test**

`software-factory/src/test/java/com/simonrowe/factory/flow/FactoryFlowTopologyTest.java`:

```java
package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.flow.domain.Band;
import com.simonrowe.factory.flow.domain.FlowEdge;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import com.simonrowe.factory.flow.domain.NodeKind;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FactoryFlowTopologyTest {

  @Test
  void pinsTheTwelveNodes() {
    assertThat(FactoryFlowTopology.NODES).extracting(NodeDescriptor::key)
        .containsExactlyInAnyOrder(
            "logwatch", "cvefix", "linear", "build", "pull-request", "codereview",
            "main", "deploy", "production", "feedback", "agent-setup", "platformbackup");
  }

  @Test
  void doesNotDrawTheLinearModuleAsItsOwnNode() {
    // The linear module is the factory's only activity-only task queue: nothing flows THROUGH it,
    // so it is a badge on the Linear artifact node rather than a box of its own.
    NodeDescriptor linear = node("linear");
    assertThat(linear.kind()).isEqualTo(NodeKind.ARTIFACT);
    assertThat(linear.moduleKey()).isEqualTo("linear");
  }

  @Test
  void leavesPlatformBackupOffTheRing() {
    // It participates in no loop. Drawing it on one would be a lie.
    assertThat(FactoryFlowTopology.EDGES)
        .noneMatch(edge -> edge.from().equals("platformbackup") || edge.to().equals("platformbackup"));
  }

  @Test
  void givesEveryNodeAnEdgeExceptPlatformBackup() {
    Set<String> connected = FactoryFlowTopology.EDGES.stream()
        .flatMap(edge -> java.util.stream.Stream.of(edge.from(), edge.to()))
        .collect(Collectors.toSet());
    List<String> orphans = FactoryFlowTopology.NODES.stream()
        .map(NodeDescriptor::key)
        .filter(key -> !connected.contains(key))
        .toList();
    assertThat(orphans).containsExactly("platformbackup");
  }

  @Test
  void wiresEveryEdgeToNodesThatExist() {
    Set<String> keys = FactoryFlowTopology.NODES.stream()
        .map(NodeDescriptor::key).collect(Collectors.toSet());
    assertThat(FactoryFlowTopology.EDGES)
        .allSatisfy(edge -> {
          assertThat(keys).contains(edge.from());
          assertThat(keys).contains(edge.to());
        });
  }

  @Test
  void drawsCvefixAsDownstreamOfAMergeRatherThanAsASource() {
    // Publish uploads image and manifest SBOMs to Dependency-Track, which cvefix reads. Without
    // this edge cvefix is a source with no input, which is simply wrong.
    assertThat(FactoryFlowTopology.EDGES)
        .anyMatch(edge -> edge.from().equals("main") && edge.to().equals("cvefix"));
  }

  @Test
  void closesTheMainLoop() {
    List<String> ring = List.of(
        "linear", "build", "pull-request", "main", "deploy", "production", "logwatch", "linear");
    for (int i = 0; i < ring.size() - 1; i++) {
      String from = ring.get(i);
      String to = ring.get(i + 1);
      assertThat(FactoryFlowTopology.EDGES)
          .as("edge %s -> %s", from, to)
          .anyMatch(edge -> edge.from().equals(from) && edge.to().equals(to));
    }
  }

  @Test
  void assignsEveryNodeToABand() {
    assertThat(FactoryFlowTopology.NODES).allSatisfy(node -> assertThat(node.band()).isNotNull());
    assertThat(FactoryFlowTopology.NODES).filteredOn(n -> n.band() == Band.UTILITY)
        .extracting(NodeDescriptor::key).containsExactly("platformbackup");
  }

  private static NodeDescriptor node(final String key) {
    return FactoryFlowTopology.NODES.stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No node " + key));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :software-factory:test --tests '*FactoryFlowTopologyTest'`
Expected: FAIL — compilation error, `package com.simonrowe.factory.flow does not exist`.

- [ ] **Step 3: Write the domain types**

`.../flow/domain/NodeKind.java`:

```java
package com.simonrowe.factory.flow.domain;

/** Whether a node is something the factory runs or something the factory exchanges. */
public enum NodeKind {
  /** A factory module with a Temporal task queue. */
  MODULE,
  /** A thing modules pass between them: Linear, a pull request, main, production, agent-setup. */
  ARTIFACT
}
```

`.../flow/domain/Band.java`:

```java
package com.simonrowe.factory.flow.domain;

/** The horizontal band a node is drawn in. */
public enum Band {
  OBSERVE,
  PLAN,
  BUILD,
  SHIP,
  LEARN,
  /** Off the ring entirely. Only platform backup. */
  UTILITY
}
```

`.../flow/domain/Loop.java`:

```java
package com.simonrowe.factory.flow.domain;

/**
 * Which of the factory's three feedback loops an edge belongs to.
 *
 * <p>Drawn at different weights so they can be told apart at a glance. The slow loop is the one no
 * existing view shows, and is why this graph is a ring rather than a pipeline.
 */
public enum Loop {
  /** Minutes. Pull request against code review. */
  FAST,
  /** Hours. Linear to build to merge to deploy to production and back to Linear. */
  MAIN,
  /** Days. A closed review shapes the agents through agent-setup. */
  SLOW
}
```

`.../flow/domain/NodeHealth.java`:

```java
package com.simonrowe.factory.flow.domain;

/**
 * What a node's badge says.
 *
 * <p>{@link #IDLE} and {@link #OFFLINE} are separate on purpose: "nothing to do" and "nothing is
 * listening" lead an operator to different actions, and conflating them is the same mistake as
 * reporting an unreadable log source as a clean scan.
 */
public enum NodeHealth {
  /** Configured, polled, prerequisites met. */
  READY,
  /** Enabled but not usable — a missing prerequisite or a missing poller. */
  DEGRADED,
  /** Switched off by configuration. */
  DISABLED,
  /** The owning container could not be asked. */
  UNAVAILABLE,
  /** Work is waiting and nothing has picked it up. */
  OFFLINE,
  /** Nothing to do. */
  IDLE
}
```

`.../flow/domain/NodeDescriptor.java`:

```java
package com.simonrowe.factory.flow.domain;

/**
 * A node's fixed properties: everything true of it before any live data is read.
 *
 * @param key the stable identifier used by edges and by the frontend
 * @param kind whether the factory runs this or exchanges it
 * @param band the row it is drawn in
 * @param label the human name
 * @param moduleKey the {@code ModulePrerequisites} key whose health this node reports, or null
 *     when no module owns it. Set on the {@code linear} artifact node, which reports the
 *     activity-only {@code linear} module's health because that module is not drawn as a box.
 * @param workflowType the Temporal workflow type whose executions this node counts, or null
 */
public record NodeDescriptor(
    String key,
    NodeKind kind,
    Band band,
    String label,
    String moduleKey,
    String workflowType) {
}
```

`.../flow/domain/FlowEdge.java`:

```java
package com.simonrowe.factory.flow.domain;

/**
 * A directed edge between two nodes.
 *
 * @param from the source node key
 * @param to the target node key
 * @param label what travels along it
 * @param loop which feedback loop it belongs to
 */
public record FlowEdge(String from, String to, String label, Loop loop) {
}
```

- [ ] **Step 4: Write the topology**

`.../flow/FactoryFlowTopology.java`:

```java
package com.simonrowe.factory.flow;

import com.simonrowe.factory.admin.ModulePrerequisites;
import com.simonrowe.factory.flow.domain.Band;
import com.simonrowe.factory.flow.domain.FlowEdge;
import com.simonrowe.factory.flow.domain.Loop;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import com.simonrowe.factory.flow.domain.NodeKind;
import java.util.List;

/**
 * The factory's shape, as code.
 *
 * <p>This is deliberately not configuration and not data. The topology is a property of which
 * modules exist and what they pass between them, so it changes only when the code changes — and
 * {@code FactoryFlowTopologyTest} fails the build when a module is added without being drawn,
 * which is the failure {@code FactoryAdminService.ORDER} has today.
 */
public final class FactoryFlowTopology {

  /** The build agent's node key. It has no module on this side: see specs/045-build-agent. */
  public static final String BUILD = "build";

  /** The Linear artifact node. Carries the activity-only linear module's health as a badge. */
  public static final String LINEAR_NODE = "linear";

  /** Every node, in no particular order; the frontend lays them out by band. */
  public static final List<NodeDescriptor> NODES =
      List.of(
          new NodeDescriptor("logwatch", NodeKind.MODULE, Band.OBSERVE, "Log watch",
              ModulePrerequisites.LOGWATCH, "LogWatchWorkflow"),
          new NodeDescriptor("cvefix", NodeKind.MODULE, Band.OBSERVE, "Vulnerability scan",
              ModulePrerequisites.CVEFIX, "CveFixWorkflow"),
          new NodeDescriptor(LINEAR_NODE, NodeKind.ARTIFACT, Band.PLAN, "Linear",
              ModulePrerequisites.LINEAR, null),
          new NodeDescriptor(BUILD, NodeKind.MODULE, Band.BUILD, "Build agent", null, null),
          new NodeDescriptor("pull-request", NodeKind.ARTIFACT, Band.BUILD, "Pull request",
              null, null),
          new NodeDescriptor("codereview", NodeKind.MODULE, Band.BUILD, "Code review",
              ModulePrerequisites.CODE_REVIEW, "CodeReviewWorkflow"),
          new NodeDescriptor("main", NodeKind.ARTIFACT, Band.SHIP, "main", null, null),
          new NodeDescriptor("deploy", NodeKind.MODULE, Band.SHIP, "Deploy",
              ModulePrerequisites.DEPLOY, "DeployWorkflow"),
          new NodeDescriptor("production", NodeKind.ARTIFACT, Band.SHIP, "Production", null, null),
          new NodeDescriptor("feedback", NodeKind.MODULE, Band.LEARN, "Feedback",
              ModulePrerequisites.FEEDBACK, "ReviewFeedbackWorkflow"),
          new NodeDescriptor("agent-setup", NodeKind.ARTIFACT, Band.LEARN, "agent-setup",
              null, null),
          new NodeDescriptor("platformbackup", NodeKind.MODULE, Band.UTILITY, "Platform backup",
              ModulePrerequisites.PLATFORM_BACKUP, "PlatformBackupWorkflow"));

  /**
   * Every edge. Platform backup deliberately appears in none of them.
   */
  public static final List<FlowEdge> EDGES =
      List.of(
          new FlowEdge("pull-request", "codereview", "push webhook", Loop.FAST),
          new FlowEdge("codereview", "pull-request", "findings and check run", Loop.FAST),

          new FlowEdge("production", "logwatch", "reads Loki", Loop.MAIN),
          new FlowEdge("logwatch", LINEAR_NODE, "files signature", Loop.MAIN),
          new FlowEdge("main", "cvefix", "publishes SBOMs", Loop.MAIN),
          new FlowEdge("cvefix", LINEAR_NODE, "files vulnerabilities", Loop.MAIN),
          new FlowEdge(LINEAR_NODE, BUILD, "approved: factory:build", Loop.MAIN),
          new FlowEdge(BUILD, "pull-request", "opens", Loop.MAIN),
          new FlowEdge("pull-request", "main", "merge", Loop.MAIN),
          new FlowEdge("main", "deploy", "Publish webhook", Loop.MAIN),
          new FlowEdge("deploy", "production", "recreates", Loop.MAIN),
          new FlowEdge("deploy", "logwatch", "scan after five minutes", Loop.MAIN),
          new FlowEdge("deploy", LINEAR_NODE, "files failure", Loop.MAIN),

          new FlowEdge("pull-request", "feedback", "on close", Loop.SLOW),
          new FlowEdge("feedback", "agent-setup", "guidance pull request", Loop.SLOW),
          new FlowEdge("agent-setup", BUILD, "shapes the agent", Loop.SLOW),
          new FlowEdge("agent-setup", "codereview", "shapes the reviewer", Loop.SLOW));

  private FactoryFlowTopology() {
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :software-factory:test --tests '*FactoryFlowTopologyTest' :software-factory:checkstyleMain`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/flow software-factory/src/test/java/com/simonrowe/factory/flow
git commit -m "feat: define the factory flow topology as code"
```

---

## Task 2: Count workflow executions from Temporal

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/domain/NodeCounts.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/WorkflowCountsReader.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/flow/WorkflowCountsReaderTest.java`

**Interfaces:**
- Consumes: `io.temporal.client.WorkflowClient` (already a bean), `FactoryFlowTopology.NODES`.
- Produces: `NodeCounts(int inFlight, int ok24h, int failed24h)` and
  `WorkflowCountsReader.countsFor(String workflowType)` returning `NodeCounts` — **null** when Temporal cannot be reached, matching `FactoryStatusService.pollers`, because "no runs" and "we do not know" lead to different actions.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.flow.domain.NodeCounts;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowCountsReaderTest {

  @Test
  void countsRunningSucceededAndFailedSeparately() {
    List<String> queries = new ArrayList<>();
    WorkflowCountsReader reader = readerReturning(queries, 3L, 7L, 1L);

    NodeCounts counts = reader.countsFor("LogWatchWorkflow");

    assertThat(counts).isEqualTo(new NodeCounts(3, 7, 1));
    assertThat(queries).hasSize(3);
    assertThat(queries).allMatch(query -> query.contains("WorkflowType = 'LogWatchWorkflow'"));
    assertThat(queries.get(0)).contains("ExecutionStatus = 'Running'");
    assertThat(queries.get(1)).contains("ExecutionStatus = 'Completed'").contains("StartTime >");
    assertThat(queries.get(2)).contains("ExecutionStatus = 'Failed'").contains("StartTime >");
  }

  @Test
  void doesNotBoundTheRunningQueryByStartTime() {
    // A deploy started 26 hours ago and still running is in flight NOW. Applying the 24-hour
    // window to the running query would hide exactly the run an operator is looking for.
    List<String> queries = new ArrayList<>();
    readerReturning(queries, 1L, 0L, 0L).countsFor("DeployWorkflow");

    assertThat(queries.get(0)).doesNotContain("StartTime");
  }

  @Test
  void returnsNullWhenTemporalCannotBeReached() {
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.countWorkflowExecutions(any())).thenThrow(new RuntimeException("unavailable"));

    assertThat(new WorkflowCountsReader(clientWith(stub)).countsFor("LogWatchWorkflow")).isNull();
  }

  private WorkflowCountsReader readerReturning(
      final List<String> capturedQueries, final long running, final long ok, final long failed) {
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    List<Long> answers = List.of(running, ok, failed);
    when(stub.countWorkflowExecutions(any())).thenAnswer(invocation -> {
      CountWorkflowExecutionsRequest request = invocation.getArgument(0);
      capturedQueries.add(request.getQuery());
      return CountWorkflowExecutionsResponse.newBuilder()
          .setCount(answers.get(capturedQueries.size() - 1))
          .build();
    });
    return new WorkflowCountsReader(clientWith(stub));
  }

  private WorkflowClient clientWith(final WorkflowServiceBlockingStub stub) {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    when(stubs.blockingStub()).thenReturn(stub);
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(stubs);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    return client;
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :software-factory:test --tests '*WorkflowCountsReaderTest'`
Expected: FAIL — `WorkflowCountsReader` and `NodeCounts` do not exist.

- [ ] **Step 3: Write the implementation**

`.../flow/domain/NodeCounts.java`:

```java
package com.simonrowe.factory.flow.domain;

/**
 * What a node's badge counts.
 *
 * @param inFlight runs executing right now, unbounded by any time window
 * @param ok24h runs that completed successfully in the last 24 hours
 * @param failed24h runs that failed in the last 24 hours
 */
public record NodeCounts(int inFlight, int ok24h, int failed24h) {

  /** Nothing has happened. Distinct from a null {@code NodeCounts}, which means "we do not know". */
  public static final NodeCounts NONE = new NodeCounts(0, 0, 0);
}
```

`.../flow/WorkflowCountsReader.java`:

```java
package com.simonrowe.factory.flow;

import com.simonrowe.factory.flow.domain.NodeCounts;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/**
 * Counts a module's Temporal executions without reading any of them.
 *
 * <p>This is the whole reason the console needs no new persistence. Every module except the
 * activity-only {@code linear} sink runs as a workflow with a distinct type, so one visibility
 * query per status band serves all six — including {@code codereview}, which keeps no run
 * collection of its own and could not otherwise be counted at all.
 */
@Service
public class WorkflowCountsReader {

  /** The reporting window for settled runs. Well inside the namespace's 30-day retention. */
  private static final Duration WINDOW = Duration.ofHours(24);

  private final WorkflowClient client;

  public WorkflowCountsReader(final WorkflowClient client) {
    this.client = client;
  }

  /**
   * Counts one workflow type's executions.
   *
   * @param workflowType the Temporal workflow type, which for this repository is always the
   *     workflow interface's simple name
   * @return the counts, or null when Temporal could not be reached
   */
  public NodeCounts countsFor(final String workflowType) {
    String scope = "WorkflowType = '" + workflowType + "'";
    String since = " AND StartTime > '"
        + Instant.now().minus(WINDOW).truncatedTo(ChronoUnit.SECONDS) + "'";
    Long running = count(scope + " AND ExecutionStatus = 'Running'");
    Long ok = count(scope + " AND ExecutionStatus = 'Completed'" + since);
    Long failed = count(scope + " AND ExecutionStatus = 'Failed'" + since);
    if (running == null || ok == null || failed == null) {
      return null;
    }
    return new NodeCounts(running.intValue(), ok.intValue(), failed.intValue());
  }

  /**
   * The running query is deliberately unbounded by time: a deploy that started 26 hours ago and
   * has not finished is in flight now, and is precisely the run an operator opened this page for.
   */
  private Long count(final String query) {
    try {
      return client
          .getWorkflowServiceStubs()
          .blockingStub()
          .countWorkflowExecutions(
              CountWorkflowExecutionsRequest.newBuilder()
                  .setNamespace(client.getOptions().getNamespace())
                  .setQuery(query)
                  .build())
          .getCount();
    } catch (RuntimeException exception) {
      return null;
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :software-factory:test --tests '*WorkflowCountsReaderTest' :software-factory:checkstyleMain`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/flow software-factory/src/test/java/com/simonrowe/factory/flow
git commit -m "feat: count factory workflow executions from Temporal visibility"
```

---

## Task 3: Read Linear and GitHub for the artifact nodes

The four artifact nodes that are not `production` need live figures. `linear` comes from the
existing `linear_issues` collection — no Linear API call, so the console works even when
`LINEAR_API_KEY` is absent. `pull-request`, `main` and `agent-setup` come from GitHub, reusing the
credentials the reviewer already holds.

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/ArtifactCountsReader.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/flow/ArtifactCountsReaderTest.java`

**Interfaces:**
- Consumes: `LinearIssueRepository.findAll()`, `LinearIssueRecord.lastKnownStateType()` (an
  `IssueStateType`, whose `open()` is the classification already used by the sink),
  `GitHubCredentials.accessToken(Long)` and `.installationId(String owner, String repository)`.
- Produces: `ArtifactCountsReader.linearCounts()`, `.pullRequestCounts()`, `.mainCounts()`,
  `.agentSetupCounts()` — each returning `NodeCounts` or null when the source could not be read.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtifactCountsReaderTest {

  private final LinearIssueRepository repository = mock(LinearIssueRepository.class);

  @Test
  void countsOpenLinearIssuesAsInFlight() {
    when(repository.findAll()).thenReturn(List.of(
        record("a", IssueStateType.TRIAGE, Instant.now()),
        record("b", IssueStateType.STARTED, Instant.now()),
        record("c", IssueStateType.COMPLETED, Instant.now()),
        record("d", IssueStateType.CANCELED, Instant.now())));

    NodeCounts counts = reader().linearCounts();

    assertThat(counts.inFlight()).isEqualTo(2);
  }

  @Test
  void treatsAnUnknownLinearStateAsOpen() {
    // Same reasoning as the sink: if Linear adds a state type, the safe failure is to keep
    // showing the ticket, not to quietly declare it handled.
    when(repository.findAll()).thenReturn(List.of(record("a", IssueStateType.UNKNOWN, Instant.now())));

    assertThat(reader().linearCounts().inFlight()).isEqualTo(1);
  }

  @Test
  void countsRecentlyClosedLinearIssuesAsSettledWithinTheWindow() {
    Instant recent = Instant.now().minusSeconds(3600);
    Instant old = Instant.now().minusSeconds(60 * 60 * 48);
    when(repository.findAll()).thenReturn(List.of(
        record("a", IssueStateType.COMPLETED, recent),
        record("b", IssueStateType.CANCELED, recent),
        record("c", IssueStateType.COMPLETED, old)));

    NodeCounts counts = reader().linearCounts();

    assertThat(counts.ok24h()).isEqualTo(2);
    assertThat(counts.inFlight()).isZero();
  }

  @Test
  void reportsZeroRatherThanNullWhenLinearHasFiledNothing() {
    // An empty collection is a known fact. Null is reserved for "could not read", and the two
    // render differently: IDLE against UNAVAILABLE.
    when(repository.findAll()).thenReturn(List.of());

    assertThat(reader().linearCounts()).isEqualTo(NodeCounts.NONE);
  }

  @Test
  void returnsNullWhenTheLinearCollectionCannotBeRead() {
    when(repository.findAll()).thenThrow(new RuntimeException("mongo down"));

    assertThat(reader().linearCounts()).isNull();
  }

  private ArtifactCountsReader reader() {
    return new ArtifactCountsReader(repository, null, "simonjamesrowe", "simonrowe-dev-monorepo");
  }

  private static LinearIssueRecord record(
      final String id, final IssueStateType state, final Instant lastSeen) {
    return new LinearIssueRecord(
        id, "logwatch", "v1", List.of("k"), "iss", "SIM-1", "https://linear.app/x",
        false, lastSeen, lastSeen, 1, state, List.of());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :software-factory:test --tests '*ArtifactCountsReaderTest'`
Expected: FAIL — `ArtifactCountsReader` does not exist.

- [ ] **Step 3: Write the implementation**

`.../flow/ArtifactCountsReader.java`:

```java
package com.simonrowe.factory.flow;

import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Live figures for the nodes that are artifacts rather than modules.
 *
 * <p>The Linear figures come from {@code linear_issues} rather than from Linear itself, so the
 * console keeps working when {@code LINEAR_API_KEY} is absent — the collection is the sink's audit
 * trail, and for a count of what the factory has filed it is sufficient. State is re-read from
 * Linear only where a filing decision depends on it, which is not the case here.
 *
 * <p>Every method returns null rather than zero on failure. "Nothing filed" and "could not be
 * read" render as IDLE and UNAVAILABLE respectively, and collapsing them would present a broken
 * source as a quiet one.
 */
@Service
public class ArtifactCountsReader {

  private static final Duration WINDOW = Duration.ofHours(24);

  private final LinearIssueRepository issues;
  private final GitHubCredentials credentials;
  private final String owner;
  private final String repository;

  public ArtifactCountsReader(
      final LinearIssueRepository issues,
      final GitHubCredentials credentials,
      @Value("${factory.github.owner:simonjamesrowe}") final String owner,
      @Value("${factory.github.repository:simonrowe-dev-monorepo}") final String repository) {
    this.issues = issues;
    this.credentials = credentials;
    this.owner = owner;
    this.repository = repository;
  }

  /**
   * Counts what the factory has filed into Linear.
   *
   * @return open issues as in-flight and issues closed in the window as settled, or null
   */
  public NodeCounts linearCounts() {
    try {
      List<LinearIssueRecord> all = issues.findAll();
      Instant cutoff = Instant.now().minus(WINDOW);
      int open = 0;
      int settled = 0;
      for (LinearIssueRecord record : all) {
        if (record.lastKnownStateType() == null || record.lastKnownStateType().open()) {
          open++;
        } else if (record.lastSeenAt() != null && record.lastSeenAt().isAfter(cutoff)) {
          settled++;
        }
      }
      return new NodeCounts(open, settled, 0);
    } catch (RuntimeException exception) {
      return null;
    }
  }
}
```

Note the GitHub methods are added in Step 5 below, after the Linear behaviour is green. Keeping
them apart means a GitHub outage cannot make the Linear tests fail.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :software-factory:test --tests '*ArtifactCountsReaderTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Add the GitHub reads with their own failing test first**

Append to `ArtifactCountsReaderTest`:

```java
  @Test
  void returnsNullForGitHubBackedNodesWhenNoInstallationCanBeResolved() {
    // The reviewer's App credentials are optional in a local run. A console that threw here
    // would be unopenable on a developer machine, which is where it is most needed.
    ArtifactCountsReader reader =
        new ArtifactCountsReader(repository, null, "simonjamesrowe", "simonrowe-dev-monorepo");

    assertThat(reader.pullRequestCounts()).isNull();
    assertThat(reader.mainCounts()).isNull();
    assertThat(reader.agentSetupCounts()).isNull();
  }
```

Run it, watch it fail with "cannot find symbol: pullRequestCounts", then add to
`ArtifactCountsReader`:

```java
  /**
   * Counts open pull requests on the target repository.
   *
   * @return open pull requests as in-flight, or null when GitHub could not be asked
   */
  public NodeCounts pullRequestCounts() {
    return gitHubCount("/repos/" + owner + "/" + repository + "/pulls?state=open&per_page=100");
  }

  /**
   * Counts merges to the default branch inside the window.
   *
   * @return merges as settled, or null when GitHub could not be asked
   */
  public NodeCounts mainCounts() {
    Instant since = Instant.now().minus(WINDOW);
    NodeCounts commits = gitHubCount(
        "/repos/" + owner + "/" + repository + "/commits?sha=main&since=" + since + "&per_page=100");
    return commits == null ? null : new NodeCounts(0, commits.inFlight(), 0);
  }

  /**
   * Counts open agent-feedback guidance pull requests on agent-setup.
   *
   * @return open guidance pull requests as in-flight, or null when GitHub could not be asked
   */
  public NodeCounts agentSetupCounts() {
    return gitHubCount("/repos/" + owner + "/agent-setup/pulls?state=open&per_page=100");
  }

  private NodeCounts gitHubCount(final String path) {
    if (credentials == null) {
      return null;
    }
    try {
      Long installation = credentials.installationId(owner, repository);
      if (installation == null) {
        return null;
      }
      List<?> items = github(credentials.accessToken(installation), path);
      return items == null ? null : new NodeCounts(items.size(), 0, 0);
    } catch (RuntimeException exception) {
      return null;
    }
  }
```

`github(String token, String path)` is a small private `RestClient` call returning `List<?>` from
the JSON array body, built the same way `FactoryAdminClient` builds its clients: a `RestClient`
with `Authorization: Bearer <token>`, `Accept: application/vnd.github+json` and
`X-GitHub-Api-Version: 2026-03-10`, matching `GitHubGateway.API_VERSION`.

- [ ] **Step 6: Run the whole class and checkstyle**

Run: `./gradlew :software-factory:test --tests '*ArtifactCountsReaderTest' :software-factory:checkstyleMain`
Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/flow software-factory/src/test/java/com/simonrowe/factory/flow
git commit -m "feat: read Linear and GitHub counts for the factory flow artifact nodes"
```

---

## Task 4: Assemble and serve the flow

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/FlowNode.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/FactoryFlowResponse.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/FactoryFlowService.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/FactoryFlowController.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/flow/FactoryFlowServiceTest.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/flow/FactoryFlowControllerTest.java`

**Interfaces:**
- Consumes: `FactoryFlowTopology.NODES` / `.EDGES`, `WorkflowCountsReader.countsFor(String)`,
  `ArtifactCountsReader.*`, `FactoryStatusService.status()` returning `FactoryStatusResponse` whose
  `modules()` is a `List<ModuleStatus>` with `key()`, `configured()`, `ready()`, `diagnostic()`.
- Produces: `FactoryFlowResponse(Instant fetchedAt, List<FlowNode> nodes, List<FlowEdge> edges)`
  and `FlowNode(String key, NodeKind kind, Band band, String label, NodeCounts counts,
  NodeHealth health, String diagnostic)`. The frontend in Task 6 mirrors these names exactly.

- [ ] **Step 1: Write the failing service test**

```java
package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.admin.FactoryStatusResponse;
import com.simonrowe.factory.admin.FactoryStatusService;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.flow.domain.NodeHealth;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FactoryFlowServiceTest {

  private final FactoryStatusService status = mock(FactoryStatusService.class);
  private final WorkflowCountsReader workflows = mock(WorkflowCountsReader.class);
  private final ArtifactCountsReader artifacts = mock(ArtifactCountsReader.class);

  @Test
  void returnsEveryTopologyNodeAndEdge() {
    givenStatus();
    FactoryFlowResponse flow = service().flow();

    assertThat(flow.nodes()).hasSize(FactoryFlowTopology.NODES.size());
    assertThat(flow.edges()).isEqualTo(FactoryFlowTopology.EDGES);
  }

  @Test
  void reportsUnavailableWhenTemporalCouldNotBeCounted() {
    givenStatus();
    when(workflows.countsFor("LogWatchWorkflow")).thenReturn(null);

    assertThat(node(service().flow(), "logwatch").health()).isEqualTo(NodeHealth.UNAVAILABLE);
  }

  @Test
  void reportsDisabledAheadOfDegradedWhenAModuleIsSwitchedOff() {
    // "Switched off" and "on but broken" send an operator to different places: one is a flag,
    // the other is a container. Collapsing them was the first cut of the status endpoint and it
    // sent people looking for an outage when the answer was configuration.
    givenStatus(module("logwatch", false, false, "Disabled by configuration"));
    when(workflows.countsFor("LogWatchWorkflow")).thenReturn(NodeCounts.NONE);

    assertThat(node(service().flow(), "logwatch").health()).isEqualTo(NodeHealth.DISABLED);
  }

  @Test
  void reportsDegradedWhenAModuleIsEnabledButNotReady() {
    givenStatus(module("logwatch", true, false, "Required Temporal poller is missing"));
    when(workflows.countsFor("LogWatchWorkflow")).thenReturn(NodeCounts.NONE);

    FlowNode logwatch = node(service().flow(), "logwatch");
    assertThat(logwatch.health()).isEqualTo(NodeHealth.DEGRADED);
    assertThat(logwatch.diagnostic()).isEqualTo("Required Temporal poller is missing");
  }

  @Test
  void putsTheLinearModulesHealthOnTheLinearArtifactNode() {
    // The linear module is activity-only and is deliberately not drawn as a box.
    givenStatus(module("linear", true, false, "Enabled but not usable: LINEAR_API_KEY is unset"));
    when(artifacts.linearCounts()).thenReturn(new NodeCounts(4, 1, 0));

    FlowNode linear = node(service().flow(), "linear");
    assertThat(linear.health()).isEqualTo(NodeHealth.DEGRADED);
    assertThat(linear.counts()).isEqualTo(new NodeCounts(4, 1, 0));
  }

  @Test
  void reportsTheBuildNodeAsOfflineWhenWorkIsWaitingAndNothingHasRun() {
    // The build agent lives on a laptop the Pi cannot reach. Empty is not the same as offline:
    // work waiting with nothing moving is OFFLINE, nothing waiting is IDLE.
    givenStatus();
    when(artifacts.linearCounts()).thenReturn(new NodeCounts(3, 0, 0));

    assertThat(node(service().flow(), "build").health()).isEqualTo(NodeHealth.OFFLINE);
  }

  @Test
  void reportsTheBuildNodeAsIdleWhenThereIsNothingWaiting() {
    givenStatus();
    when(artifacts.linearCounts()).thenReturn(NodeCounts.NONE);

    assertThat(node(service().flow(), "build").health()).isEqualTo(NodeHealth.IDLE);
  }

  private FactoryFlowService service() {
    return new FactoryFlowService(status, workflows, artifacts);
  }

  private void givenStatus(final FactoryStatusResponse.ModuleStatus... overrides) {
    List<FactoryStatusResponse.ModuleStatus> modules = new java.util.ArrayList<>(List.of(
        module("codereview", true, true, null),
        module("feedback", true, true, null),
        module("cvefix", true, true, null),
        module("deploy", true, true, null),
        module("linear", true, true, null),
        module("platformbackup", true, true, null),
        module("logwatch", true, true, null)));
    for (FactoryStatusResponse.ModuleStatus override : overrides) {
      modules.removeIf(existing -> existing.key().equals(override.key()));
      modules.add(override);
    }
    when(status.status())
        .thenReturn(new FactoryStatusResponse("software-factory", Instant.now(), modules));
    when(workflows.countsFor(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(NodeCounts.NONE);
    when(artifacts.linearCounts()).thenReturn(NodeCounts.NONE);
  }

  private static FactoryStatusResponse.ModuleStatus module(
      final String key, final boolean configured, final boolean ready, final String diagnostic) {
    return new FactoryStatusResponse.ModuleStatus(
        key, key, configured, key, 1, 1, "trigger", null, List.of(), ready, diagnostic);
  }

  private static FlowNode node(final FactoryFlowResponse flow, final String key) {
    return flow.nodes().stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No node " + key));
  }
}
```

Before writing the implementation, open `FactoryStatusResponse.java` and confirm the
`ModuleStatus` component order used in the helper above. If it differs, fix the helper — the
record is existing code and is not to be changed by this task.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :software-factory:test --tests '*FactoryFlowServiceTest'`
Expected: FAIL — `FactoryFlowService`, `FactoryFlowResponse` and `FlowNode` do not exist.

- [ ] **Step 3: Write the response types**

`.../flow/FlowNode.java`:

```java
package com.simonrowe.factory.flow;

import com.simonrowe.factory.flow.domain.Band;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.flow.domain.NodeHealth;
import com.simonrowe.factory.flow.domain.NodeKind;

/**
 * One node as the console renders it.
 *
 * @param key the stable identifier edges refer to
 * @param kind module or artifact
 * @param band the row it is drawn in
 * @param label the human name
 * @param counts live figures, or null when the source could not be read
 * @param health the badge
 * @param diagnostic one sentence explaining a non-READY health, or null
 */
public record FlowNode(
    String key,
    NodeKind kind,
    Band band,
    String label,
    NodeCounts counts,
    NodeHealth health,
    String diagnostic) {
}
```

`.../flow/FactoryFlowResponse.java`:

```java
package com.simonrowe.factory.flow;

import com.simonrowe.factory.flow.domain.FlowEdge;
import java.time.Instant;
import java.util.List;

/**
 * The whole graph, as one read.
 *
 * @param fetchedAt when this snapshot was taken
 * @param nodes every node with its live figures
 * @param edges every edge; fixed, and identical on every call
 */
public record FactoryFlowResponse(Instant fetchedAt, List<FlowNode> nodes, List<FlowEdge> edges) {
}
```

- [ ] **Step 4: Write the service**

`.../flow/FactoryFlowService.java`:

```java
package com.simonrowe.factory.flow;

import com.simonrowe.factory.admin.FactoryStatusResponse;
import com.simonrowe.factory.admin.FactoryStatusService;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import com.simonrowe.factory.flow.domain.NodeHealth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Puts live figures onto the fixed topology.
 *
 * <p>Health is resolved in a fixed precedence — disabled, then unavailable, then degraded — because
 * those three send an operator to a flag, a container and a prerequisite respectively, and a single
 * collapsed "not working" sends them to the wrong one.
 */
@Service
public class FactoryFlowService {

  private final FactoryStatusService status;
  private final WorkflowCountsReader workflows;
  private final ArtifactCountsReader artifacts;

  public FactoryFlowService(
      final FactoryStatusService status,
      final WorkflowCountsReader workflows,
      final ArtifactCountsReader artifacts) {
    this.status = status;
    this.workflows = workflows;
    this.artifacts = artifacts;
  }

  /**
   * Builds the graph as this container sees it.
   *
   * @return every node with its counts and health, and every edge
   */
  public FactoryFlowResponse flow() {
    Map<String, FactoryStatusResponse.ModuleStatus> modules =
        status.status().modules().stream()
            .collect(Collectors.toMap(
                FactoryStatusResponse.ModuleStatus::key, Function.identity()));
    NodeCounts linear = artifacts.linearCounts();
    List<FlowNode> nodes = new ArrayList<>();
    for (NodeDescriptor descriptor : FactoryFlowTopology.NODES) {
      nodes.add(node(descriptor, modules, linear));
    }
    return new FactoryFlowResponse(Instant.now(), nodes, FactoryFlowTopology.EDGES);
  }

  private FlowNode node(
      final NodeDescriptor descriptor,
      final Map<String, FactoryStatusResponse.ModuleStatus> modules,
      final NodeCounts linear) {
    NodeCounts counts = countsFor(descriptor, linear);
    FactoryStatusResponse.ModuleStatus module =
        descriptor.moduleKey() == null ? null : modules.get(descriptor.moduleKey());
    NodeHealth health = health(descriptor, module, counts, linear);
    String diagnostic = module == null ? null : module.diagnostic();
    return new FlowNode(
        descriptor.key(), descriptor.kind(), descriptor.band(), descriptor.label(),
        counts, health, diagnostic);
  }

  private NodeCounts countsFor(final NodeDescriptor descriptor, final NodeCounts linear) {
    if (descriptor.workflowType() != null) {
      return workflows.countsFor(descriptor.workflowType());
    }
    return switch (descriptor.key()) {
      case FactoryFlowTopology.LINEAR_NODE -> linear;
      case "pull-request" -> artifacts.pullRequestCounts();
      case "main" -> artifacts.mainCounts();
      case "agent-setup" -> artifacts.agentSetupCounts();
      // The build agent runs on a machine this container cannot reach, and production's state is
      // already reported by the platform status endpoint the console renders separately.
      default -> NodeCounts.NONE;
    };
  }

  private NodeHealth health(
      final NodeDescriptor descriptor,
      final FactoryStatusResponse.ModuleStatus module,
      final NodeCounts counts,
      final NodeCounts linear) {
    if (FactoryFlowTopology.BUILD.equals(descriptor.key())) {
      // Derived entirely from Linear: work waiting with nothing running means nothing is
      // listening. Nothing waiting means nothing to do. They are different facts.
      if (linear == null) {
        return NodeHealth.UNAVAILABLE;
      }
      return linear.inFlight() > 0 ? NodeHealth.OFFLINE : NodeHealth.IDLE;
    }
    if (module != null && Boolean.FALSE.equals(module.configured())) {
      return NodeHealth.DISABLED;
    }
    if (counts == null || module != null && module.configured() == null) {
      return NodeHealth.UNAVAILABLE;
    }
    if (module != null && !module.ready()) {
      return NodeHealth.DEGRADED;
    }
    return NodeHealth.READY;
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :software-factory:test --tests '*FactoryFlowServiceTest'`
Expected: PASS, 7 tests.

- [ ] **Step 6: Write the failing controller test**

```java
package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

class FactoryFlowControllerTest {

  @Test
  void servesTheFlow() {
    FactoryFlowService service = mock(FactoryFlowService.class);
    FactoryFlowResponse expected =
        new FactoryFlowResponse(Instant.now(), List.of(), List.of());
    when(service.flow()).thenReturn(expected);

    assertThat(new FactoryFlowController(service).flow()).isSameAs(expected);
  }

  @Test
  void isMountedUnderTheTokenProtectedApiPrefix() throws Exception {
    // /api/factory/flow carries Linear ticket titles and pull request subjects, so unlike
    // /api/factory/status it must sit behind FactoryTokenAuthenticator. That filter matches on
    // the path prefix, so the mapping is what enforces it.
    RequestMapping mapping = FactoryFlowController.class.getAnnotation(RequestMapping.class);
    assertThat(mapping.value()).containsExactly("/api/factory/flow");
    Method unused = FactoryFlowController.class.getMethod("flow");
    assertThat(unused).isNotNull();
  }
}
```

- [ ] **Step 7: Run it, watch it fail, then write the controller**

Run: `./gradlew :software-factory:test --tests '*FactoryFlowControllerTest'` — FAIL, no such class.

`.../flow/FactoryFlowController.java`:

```java
package com.simonrowe.factory.flow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The factory flow graph, for the admin console.
 *
 * <p><strong>Token-protected, unlike {@code /api/factory/status}.</strong> That endpoint is
 * deliberately open because it returns only booleans, queue names and poller counts, and because
 * the deployer holds no trigger token and must still be able to answer it. This one returns Linear
 * ticket counts and pull request figures, which is free text about work in progress, so it belongs
 * with {@code /api/factory/runs/&#123;id&#125;} behind {@code FactoryTokenAuthenticator}.
 */
@RestController
@RequestMapping("/api/factory/flow")
public class FactoryFlowController {

  private final FactoryFlowService service;

  public FactoryFlowController(final FactoryFlowService service) {
    this.service = service;
  }

  /**
   * Returns the whole graph.
   *
   * @return every node with its live figures, and every edge
   */
  @GetMapping
  public FactoryFlowResponse flow() {
    return service.flow();
  }
}
```

- [ ] **Step 8: Confirm the token filter covers the new path**

Open `software-factory/src/main/java/com/simonrowe/factory/admin/FactoryTokenAuthenticator.java`
and check which paths it protects. If it enumerates paths rather than matching a prefix, add
`/api/factory/flow` and add a test asserting an unauthenticated request is rejected. If it matches
`/api/**` minus an allowlist containing `/api/factory/status` and `/api/version`, no change is
needed — record which it was in the commit message.

- [ ] **Step 9: Run the module's whole suite and checkstyle**

Run: `./gradlew :software-factory:test :software-factory:checkstyleMain`
Expected: PASS. Baseline is 582 tests; expect 582 plus the ones added in Tasks 1–4.

- [ ] **Step 10: Commit**

```bash
git add software-factory/src
git commit -m "feat: serve the factory flow graph from the software factory"
```

---

## Task 5: Proxy the flow through the backend

**Files:**
- Create: `backend/src/main/java/com/simonrowe/factoryadmin/FactoryFlow.java`
- Modify: `backend/src/main/java/com/simonrowe/factoryadmin/FactoryAdminClient.java`
- Modify: `backend/src/main/java/com/simonrowe/factoryadmin/FactoryAdminService.java`
- Modify: `backend/src/main/java/com/simonrowe/factoryadmin/FactoryAdminController.java`
- Test: `backend/src/test/java/com/simonrowe/factoryadmin/FactoryAdminServiceFlowTest.java`

**Interfaces:**
- Consumes: `FactoryAdminClient` (existing), which already holds a `factory` and a `deployer`
  `RestClient` and the `X-Factory-Token` header constant.
- Produces: `FactoryAdminService.flow()` returning `FactoryFlow(Instant fetchedAt,
  List<FactoryFlow.Node> nodes, List<FactoryFlow.Edge> edges)`, served at
  `GET /api/admin/software-factory/flow`.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factoryadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class FactoryAdminServiceFlowTest {

  private final FactoryAdminClient client = mock(FactoryAdminClient.class);

  @Test
  void takesDeployerOwnedNodesFromTheDeployer() {
    // deploy and platformbackup run on the deployer. software-factory's own view of them is the
    // switched-off one, so forwarding it would report a working deploy as disabled.
    when(client.factoryFlow()).thenReturn(flow(node("deploy", "DISABLED"), node("logwatch", "READY")));
    when(client.deployerFlow()).thenReturn(flow(node("deploy", "READY"), node("logwatch", "DISABLED")));

    FactoryFlow merged = service().flow();

    assertThat(node(merged, "deploy").health()).isEqualTo("READY");
    assertThat(node(merged, "logwatch").health()).isEqualTo("READY");
  }

  @Test
  void reportsDeployerOwnedNodesAsUnavailableWhenTheDeployerCannotBeReached() {
    when(client.factoryFlow()).thenReturn(flow(node("deploy", "DISABLED"), node("logwatch", "READY")));
    when(client.deployerFlow()).thenThrow(new RestClientException("connection refused"));

    FactoryFlow merged = service().flow();

    assertThat(node(merged, "deploy").health()).isEqualTo("UNAVAILABLE");
    assertThat(node(merged, "logwatch").health()).isEqualTo("READY");
  }

  @Test
  void failsLoudlyWhenTheFactoryItselfCannotBeReached() {
    // Every node would be wrong, so a half-drawn graph is worse than an error the page can show.
    when(client.factoryFlow()).thenThrow(new RestClientException("connection refused"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().flow())
        .isInstanceOf(RuntimeException.class);
  }

  private FactoryAdminService service() {
    return new FactoryAdminService(client, mock(FactoryAdminProperties.class), null);
  }

  private static FactoryFlow flow(final FactoryFlow.Node... nodes) {
    return new FactoryFlow(Instant.now(), List.of(nodes), List.of());
  }

  private static FactoryFlow.Node node(final String key, final String health) {
    return new FactoryFlow.Node(key, "MODULE", "SHIP", key, null, health, null);
  }

  private static FactoryFlow.Node node(final FactoryFlow flow, final String key) {
    return flow.nodes().stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No node " + key));
  }
}
```

The `service()` helper's third constructor argument is `RunningVersion`; check
`FactoryAdminService`'s constructor and pass whatever it actually takes, mocked.

- [ ] **Step 2: Run test to verify it fails**

Run: `../gradlew :backend:test --tests '*FactoryAdminServiceFlowTest'` from `backend/`
Expected: FAIL — `FactoryFlow` does not exist.

- [ ] **Step 3: Write the wire record**

`backend/src/main/java/com/simonrowe/factoryadmin/FactoryFlow.java`:

```java
package com.simonrowe.factoryadmin;

import java.time.Instant;
import java.util.List;

/**
 * The factory flow graph as the console consumes it.
 *
 * <p>Health, kind and band are carried as strings rather than enums: this is a proxy, and a value
 * the factory adds should reach the browser rather than failing deserialisation here.
 *
 * @param fetchedAt when the snapshot was taken
 * @param nodes every node
 * @param edges every edge
 */
public record FactoryFlow(Instant fetchedAt, List<Node> nodes, List<Edge> edges) {

  /**
   * One node.
   *
   * @param key the stable identifier
   * @param kind MODULE or ARTIFACT
   * @param band the row it is drawn in
   * @param label the human name
   * @param counts live figures, or null when the source could not be read
   * @param health the badge
   * @param diagnostic one sentence explaining a non-READY health, or null
   */
  public record Node(
      String key, String kind, String band, String label, Counts counts,
      String health, String diagnostic) {
  }

  /**
   * A node's figures.
   *
   * @param inFlight runs executing now
   * @param ok24h runs that succeeded in the last 24 hours
   * @param failed24h runs that failed in the last 24 hours
   */
  public record Counts(int inFlight, int ok24h, int failed24h) {
  }

  /**
   * A directed edge.
   *
   * @param from source node key
   * @param to target node key
   * @param label what travels along it
   * @param loop FAST, MAIN or SLOW
   */
  public record Edge(String from, String to, String label, String loop) {
  }
}
```

- [ ] **Step 4: Add the two client calls**

In `FactoryAdminClient`, add a constant and two methods next to `factoryStatus()`:

```java
  private static final String FLOW_PATH = "/api/factory/flow";

  /**
   * Reads the flow graph from the factory.
   *
   * @return the graph as software-factory sees it
   */
  public FactoryFlow factoryFlow() {
    return factory.get().uri(FLOW_PATH).header(TOKEN_HEADER, token)
        .retrieve().body(FactoryFlow.class);
  }

  /**
   * Reads the flow graph from the deployer, which is the authority on deploy and platform backup.
   *
   * <p>Sends the token, unlike {@link #deployerStatus()}: the flow endpoint checks one, and the
   * deployer's copy of it is what makes those two nodes reportable at all.
   */
  public FactoryFlow deployerFlow() {
    return deployer.get().uri(FLOW_PATH).header(TOKEN_HEADER, token)
        .retrieve().body(FactoryFlow.class);
  }
```

**Before implementing, verify the deployer actually holds `FACTORY_TRIGGER_TOKEN`.** Run:

```bash
grep -n "FACTORY_TRIGGER_TOKEN" docker-compose.prod.yml
```

The deployer deliberately holds no trigger token — that is why `deployerStatus()` sends none. If
the grep confirms the token is absent under `deployer`, then `deployerFlow()` **cannot** be
token-protected on the deployer side. Resolve it this way: give `FactoryTokenAuthenticator` an
exemption for `/api/factory/flow` **only when `factory.runtime-role` is `deployer`**, and record
the reasoning in the class Javadoc. The deployer's flow response contains only its own two modules'
counts, which is the same class of information `/api/factory/status` already serves openly from
that container. Add a test in `software-factory` asserting the exemption applies only in the
deployer role.

- [ ] **Step 5: Merge in the service**

Add to `FactoryAdminService`, reusing the existing `DEPLOYER_OWNED` list:

```java
  /**
   * Builds the graph the console draws, from both containers.
   *
   * <p>Deploy and platform backup are taken from the deployer and reported unavailable when it
   * cannot be reached, never from software-factory's own switched-off view of them.
   *
   * @return the merged graph
   */
  public FactoryFlow flow() {
    FactoryFlow factory = client.factoryFlow();
    FactoryFlow deployer = null;
    try {
      deployer = client.deployerFlow();
    } catch (RuntimeException exception) {
      deployer = null;
    }
    List<FactoryFlow.Node> merged = new ArrayList<>();
    for (FactoryFlow.Node node : factory.nodes()) {
      if (!DEPLOYER_OWNED.contains(node.key())) {
        merged.add(node);
      } else if (deployer == null) {
        merged.add(new FactoryFlow.Node(
            node.key(), node.kind(), node.band(), node.label(), null,
            "UNAVAILABLE", "The deployer could not be reached"));
      } else {
        merged.add(fromDeployer(deployer, node));
      }
    }
    return new FactoryFlow(factory.fetchedAt(), merged, factory.edges());
  }
```

`fromDeployer` looks the node up by key in the deployer's response and falls back to the
UNAVAILABLE form when the deployer did not report it.

- [ ] **Step 6: Add the controller mapping**

In `FactoryAdminController`, beside `@GetMapping("/status")`:

```java
  /**
   * Returns the factory flow graph.
   *
   * @return every node with its live figures, and every edge
   */
  @GetMapping("/flow")
  public FactoryFlow flow() {
    return service.flow();
  }
```

- [ ] **Step 7: Run the tests and checkstyle**

Run from `backend/`: `../gradlew :backend:test --tests '*FactoryAdmin*' checkstyleMain`
Expected: PASS, 3 new tests plus the existing `FactoryAdmin*` tests.

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "feat: proxy the factory flow graph through the backend admin API"
```

---

## Task 6: Frontend API client

**Files:**
- Modify: `frontend/src/services/softwareFactoryApi.ts`
- Test: `frontend/tests/softwareFactoryFlowApi.test.ts`

**Interfaces:**
- Produces: `FactoryFlow`, `FactoryFlowNode`, `FactoryFlowEdge`, `FactoryNodeCounts`,
  `FactoryNodeHealth`, `FactoryNodeBand` types, and `fetchFactoryFlow(getAccessToken)`.
  Tasks 7 and 8 import all of these.

- [ ] **Step 1: Write the failing test**

`frontend/tests/softwareFactoryFlowApi.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchFactoryFlow } from '../src/services/softwareFactoryApi'

afterEach(() => { vi.unstubAllGlobals() })

describe('fetchFactoryFlow', () => {
  it('sends the bearer token and returns the graph', async () => {
    const body = {
      fetchedAt: '2026-09-04T10:00:00Z',
      nodes: [{
        key: 'logwatch', kind: 'MODULE', band: 'OBSERVE', label: 'Log watch',
        counts: { inFlight: 0, ok24h: 2, failed24h: 0 }, health: 'READY', diagnostic: null,
      }],
      edges: [{ from: 'logwatch', to: 'linear', label: 'files signature', loop: 'MAIN' }],
    }
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, json: () => Promise.resolve(body),
    })
    vi.stubGlobal('fetch', fetchMock)

    const flow = await fetchFactoryFlow(() => Promise.resolve('token-abc'))

    expect(flow.nodes[0].key).toBe('logwatch')
    expect(flow.edges[0].loop).toBe('MAIN')
    const [url, options] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/api/admin/software-factory/flow')
    expect(options.headers.Authorization).toBe('Bearer token-abc')
  })

  it('surfaces the server message when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 502,
      json: () => Promise.resolve({ message: 'The Software Factory is unreachable' }),
    }))

    await expect(fetchFactoryFlow(() => Promise.resolve('t')))
      .rejects.toThrow('The Software Factory is unreachable')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run from `frontend/`: `npm test -- softwareFactoryFlowApi`
Expected: FAIL — `fetchFactoryFlow` is not exported.

- [ ] **Step 3: Add the types and the fetch**

Append to `frontend/src/services/softwareFactoryApi.ts`:

```ts
export type FactoryNodeKind = 'MODULE' | 'ARTIFACT'

export type FactoryNodeBand = 'OBSERVE' | 'PLAN' | 'BUILD' | 'SHIP' | 'LEARN' | 'UTILITY'

export type FactoryLoop = 'FAST' | 'MAIN' | 'SLOW'

/**
 * IDLE and OFFLINE are separate on purpose: "nothing to do" and "nothing is listening" send an
 * operator to different places, and the build agent runs on a machine the server cannot reach.
 */
export type FactoryNodeHealth =
  | 'READY' | 'DEGRADED' | 'DISABLED' | 'UNAVAILABLE' | 'OFFLINE' | 'IDLE'

export interface FactoryNodeCounts {
  inFlight: number
  ok24h: number
  failed24h: number
}

export interface FactoryFlowNode {
  key: string
  kind: FactoryNodeKind
  band: FactoryNodeBand
  label: string
  /** null when the source could not be read, which is not the same as zero. */
  counts: FactoryNodeCounts | null
  health: FactoryNodeHealth
  diagnostic: string | null
}

export interface FactoryFlowEdge {
  from: string
  to: string
  label: string
  loop: FactoryLoop
}

export interface FactoryFlow {
  fetchedAt: string
  nodes: FactoryFlowNode[]
  edges: FactoryFlowEdge[]
}

export const fetchFactoryFlow = (getAccessToken: GetAccessToken) =>
  request<FactoryFlow>(getAccessToken, '/flow')
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- softwareFactoryFlowApi`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/services/softwareFactoryApi.ts frontend/tests/softwareFactoryFlowApi.test.ts
git commit -m "feat: add the factory flow client to the admin API service"
```

---

## Task 7: The graph component

The accessibility decision here is the one most easily got wrong, so it is tested first. Every node
is a real `<button>` in DOM order following the main loop; the SVG is `aria-hidden` decoration
layered over them.

**Files:**
- Create: `frontend/src/pages/admin/factoryFlowLayout.ts`
- Create: `frontend/src/pages/admin/FactoryFlowGraph.tsx`
- Modify: `frontend/src/styles.css`
- Test: `frontend/tests/factoryFlowGraph.test.tsx`

**Interfaces:**
- Consumes: `FactoryFlow`, `FactoryFlowNode`, `FactoryNodeHealth` from Task 6.
- Produces: `FACTORY_FLOW_ORDER: string[]` (the traversal order) and
  `<FactoryFlowGraph flow={FactoryFlow} selected={string | null}
  onSelect={(key: string) => void} />`. Task 8 mounts it.

- [ ] **Step 1: Write the failing test**

`frontend/tests/factoryFlowGraph.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FactoryFlowGraph } from '../src/pages/admin/FactoryFlowGraph'
import { FACTORY_FLOW_ORDER } from '../src/pages/admin/factoryFlowLayout'
import type { FactoryFlow, FactoryFlowNode } from '../src/services/softwareFactoryApi'

const node = (key: string, over: Partial<FactoryFlowNode> = {}): FactoryFlowNode => ({
  key, kind: 'MODULE', band: 'OBSERVE', label: key,
  counts: { inFlight: 0, ok24h: 0, failed24h: 0 }, health: 'READY', diagnostic: null, ...over,
})

const flow = (nodes: FactoryFlowNode[]): FactoryFlow => ({
  fetchedAt: '2026-09-04T10:00:00Z', nodes, edges: [],
})

describe('FactoryFlowGraph', () => {
  it('renders every node as a button so the graph is keyboard navigable', () => {
    render(<FactoryFlowGraph flow={flow(FACTORY_FLOW_ORDER.map((k) => node(k)))}
      selected={null} onSelect={vi.fn()} />)

    expect(screen.getAllByRole('button')).toHaveLength(FACTORY_FLOW_ORDER.length)
  })

  it('orders the buttons along the main loop, not by band', () => {
    // Tab order is the only traversal a keyboard user gets. Following the ring is what makes the
    // diagram legible without sight of the SVG.
    render(<FactoryFlowGraph flow={flow(FACTORY_FLOW_ORDER.map((k) => node(k, { label: k })))}
      selected={null} onSelect={vi.fn()} />)

    const labels = screen.getAllByRole('button').map((b) => b.getAttribute('data-node-key'))
    expect(labels).toEqual(FACTORY_FLOW_ORDER)
  })

  it('hides the decorative svg from assistive technology', () => {
    const { container } = render(
      <FactoryFlowGraph flow={flow([node('logwatch')])} selected={null} onSelect={vi.fn()} />)

    expect(container.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
  })

  it('gives every node a unique accessible name', () => {
    // "Dry run" collided with platform backup once already. The accessible name is all a screen
    // reader gets, and two identical ones make the graph unusable without sight of it.
    render(<FactoryFlowGraph flow={flow(FACTORY_FLOW_ORDER.map((k) => node(k, { label: k })))}
      selected={null} onSelect={vi.fn()} />)

    const names = screen.getAllByRole('button').map((b) => b.textContent?.trim())
    expect(new Set(names).size).toBe(names.length)
  })

  it('reports a null count as unknown rather than as zero', () => {
    render(<FactoryFlowGraph
      flow={flow([node('logwatch', { counts: null, health: 'UNAVAILABLE' })])}
      selected={null} onSelect={vi.fn()} />)

    expect(screen.getByRole('button', { name: /unknown/i })).toBeInTheDocument()
  })

  it('calls back with the node key when one is activated', async () => {
    const onSelect = vi.fn()
    render(<FactoryFlowGraph flow={flow([node('logwatch', { label: 'Log watch' })])}
      selected={null} onSelect={onSelect} />)

    await userEvent.click(screen.getByRole('button', { name: /Log watch/ }))

    expect(onSelect).toHaveBeenCalledWith('logwatch')
  })

  it('marks the selected node as pressed', () => {
    render(<FactoryFlowGraph flow={flow([node('logwatch', { label: 'Log watch' })])}
      selected="logwatch" onSelect={vi.fn()} />)

    expect(screen.getByRole('button', { name: /Log watch/ }))
      .toHaveAttribute('aria-pressed', 'true')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- factoryFlowGraph`
Expected: FAIL — the module does not exist.

- [ ] **Step 3: Write the layout module**

`frontend/src/pages/admin/factoryFlowLayout.ts`:

```ts
import type { FactoryNodeBand } from '../../services/softwareFactoryApi'

/**
 * Tab order, and therefore the order a screen reader reads the graph in.
 *
 * Deliberately the main loop rather than the bands: the ring is the thing being communicated, and
 * a keyboard user who cannot see the SVG gets it only from this sequence. Nodes off the ring
 * follow at the end.
 */
export const FACTORY_FLOW_ORDER = [
  'linear', 'build', 'pull-request', 'codereview', 'main', 'deploy', 'production',
  'logwatch', 'cvefix', 'feedback', 'agent-setup', 'platformbackup',
]

/** Fixed grid positions, in an arbitrary 1000x520 viewBox the SVG scales from. */
export const NODE_POSITIONS: Record<string, { x: number; y: number }> = {
  linear: { x: 120, y: 260 },
  build: { x: 300, y: 160 },
  'pull-request': { x: 480, y: 160 },
  codereview: { x: 480, y: 60 },
  main: { x: 660, y: 160 },
  deploy: { x: 820, y: 260 },
  production: { x: 660, y: 380 },
  logwatch: { x: 420, y: 400 },
  cvefix: { x: 660, y: 470 },
  feedback: { x: 300, y: 60 },
  'agent-setup': { x: 140, y: 60 },
  platformbackup: { x: 900, y: 440 },
}

export const BAND_LABELS: Record<FactoryNodeBand, string> = {
  OBSERVE: 'Observe',
  PLAN: 'Plan',
  BUILD: 'Build',
  SHIP: 'Ship',
  LEARN: 'Learn',
  UTILITY: 'Utility',
}
```

- [ ] **Step 4: Write the component**

`frontend/src/pages/admin/FactoryFlowGraph.tsx`. Structure:

```tsx
import type { FactoryFlow, FactoryFlowNode, FactoryNodeHealth } from '../../services/softwareFactoryApi'
import { FACTORY_FLOW_ORDER, NODE_POSITIONS } from './factoryFlowLayout'

const HEALTH_LABELS: Record<FactoryNodeHealth, string> = {
  READY: 'Ready',
  DEGRADED: 'Degraded',
  DISABLED: 'Disabled',
  UNAVAILABLE: 'Unknown',
  OFFLINE: 'Offline',
  IDLE: 'Idle',
}

/** A missing count is not a zero count, and the two must not read the same. */
function countSummary(node: FactoryFlowNode): string {
  if (node.counts === null) return 'counts unknown'
  const { inFlight, ok24h, failed24h } = node.counts
  return `${inFlight} in flight, ${ok24h} ok, ${failed24h} failed in 24h`
}

export function FactoryFlowGraph(
  { flow, selected, onSelect }:
  { flow: FactoryFlow; selected: string | null; onSelect: (key: string) => void },
) {
  const byKey = new Map(flow.nodes.map((node) => [node.key, node]))
  const ordered = FACTORY_FLOW_ORDER
    .map((key) => byKey.get(key))
    .filter((node): node is FactoryFlowNode => node !== undefined)

  return (
    <div className="factory-flow">
      <svg className="factory-flow__canvas" viewBox="0 0 1000 520" aria-hidden="true" focusable="false">
        {flow.edges.map((edge) => (
          <line
            key={`${edge.from}-${edge.to}`}
            className={`factory-flow__edge factory-flow__edge--${edge.loop.toLowerCase()}`}
            x1={NODE_POSITIONS[edge.from]?.x} y1={NODE_POSITIONS[edge.from]?.y}
            x2={NODE_POSITIONS[edge.to]?.x} y2={NODE_POSITIONS[edge.to]?.y}
          />
        ))}
      </svg>
      <ul className="factory-flow__nodes">
        {ordered.map((node) => (
          <li key={node.key} className="factory-flow__node-slot"
            style={{
              '--factory-flow-x': `${NODE_POSITIONS[node.key]?.x ?? 0}`,
              '--factory-flow-y': `${NODE_POSITIONS[node.key]?.y ?? 0}`,
            } as React.CSSProperties}>
            <button
              type="button"
              data-node-key={node.key}
              aria-pressed={selected === node.key}
              className={`factory-flow__node factory-flow__node--${node.health.toLowerCase()}`}
              onClick={() => onSelect(node.key)}
            >
              <span className="factory-flow__node-label">{node.label}</span>
              <span className="factory-flow__node-health">{HEALTH_LABELS[node.health]}</span>
              <span className="factory-flow__node-counts">{countSummary(node)}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
```

Arrowheads: add one `<defs><marker id="factory-flow-arrow">` and reference it with
`markerEnd="url(#factory-flow-arrow)"` on each line. Curved edges are a later refinement; straight
lines satisfy the tests and read correctly on the fixed grid.

- [ ] **Step 5: Add the CSS**

In `frontend/src/styles.css`, following BEM and the existing custom properties:

- `.factory-flow` — `position: relative`, fixed aspect ratio matching the viewBox.
- `.factory-flow__canvas` — absolutely positioned, `inset: 0`, `pointer-events: none`.
- `.factory-flow__nodes` — `list-style: none`, `position: relative`, `margin: 0`, `padding: 0`.
- `.factory-flow__node-slot` — absolutely positioned from `--factory-flow-x` / `--factory-flow-y`
  as percentages of the viewBox dimensions.
- `.factory-flow__edge--fast` / `--main` / `--slow` — increasing `stroke-width`; `--slow` gets
  `stroke-dasharray`.
- `.factory-flow__node--ready` / `--degraded` / `--disabled` / `--unavailable` / `--offline` /
  `--idle` — border colour from existing theme custom properties. **Colour is never the only
  signal**: the health word is always rendered as text.
- **Below `50rem`**: `.factory-flow__canvas { display: none }` and `.factory-flow__node-slot
  { position: static }`, so the buttons stack as a plain list. That is the mobile layout for free.

- [ ] **Step 6: Run test to verify it passes**

Run: `npm test -- factoryFlowGraph && npm run lint`
Expected: PASS, 7 tests; lint exits 0.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/admin/FactoryFlowGraph.tsx frontend/src/pages/admin/factoryFlowLayout.ts frontend/src/styles.css frontend/tests/factoryFlowGraph.test.tsx
git commit -m "feat: render the software factory as an accessible loop diagram"
```

---

## Task 8: The node drawer, and removing the cards

**Files:**
- Create: `frontend/src/pages/admin/FactoryNodeDrawer.tsx`
- Modify: `frontend/src/pages/admin/SoftwareFactoryAdmin.tsx`
- Modify: `frontend/src/styles.css`
- Test: `frontend/tests/factoryNodeDrawer.test.tsx`
- Test: `frontend/tests/softwareFactoryAdmin.test.tsx` (existing — update)

**Interfaces:**
- Consumes: `FactoryFlowNode`, `FactoryModuleStatus`, and the existing `ActionPanel` children
  already in `SoftwareFactoryAdmin.tsx`.
- Produces: `<FactoryNodeDrawer node={FactoryFlowNode | null} module={FactoryModuleStatus | null}
  onClose={() => void}>{actions}</FactoryNodeDrawer>`.

- [ ] **Step 1: Write the failing drawer test**

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FactoryNodeDrawer } from '../src/pages/admin/FactoryNodeDrawer'
import type { FactoryFlowNode } from '../src/services/softwareFactoryApi'

const node: FactoryFlowNode = {
  key: 'logwatch', kind: 'MODULE', band: 'OBSERVE', label: 'Log watch',
  counts: { inFlight: 1, ok24h: 2, failed24h: 0 }, health: 'DEGRADED',
  diagnostic: 'Enabled but not usable: GRAFANA_CLOUD_LOKI_ENDPOINT is unset',
}

describe('FactoryNodeDrawer', () => {
  it('renders nothing when no node is selected', () => {
    const { container } = render(
      <FactoryNodeDrawer node={null} module={null} onClose={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('names the node and shows its diagnostic', () => {
    render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()} />)
    expect(screen.getByRole('heading', { name: 'Log watch' })).toBeInTheDocument()
    expect(screen.getByText(/GRAFANA_CLOUD_LOKI_ENDPOINT is unset/)).toBeInTheDocument()
  })

  it('explains why platform backup has no edges', () => {
    // Otherwise it reads as an oversight rather than as a statement.
    render(<FactoryNodeDrawer node={{ ...node, key: 'platformbackup', label: 'Platform backup', band: 'UTILITY' }}
      module={null} onClose={vi.fn()} />)
    expect(screen.getByText(/participates in no loop/i)).toBeInTheDocument()
  })

  it('explains that the build agent is not yet staffed', () => {
    render(<FactoryNodeDrawer node={{ ...node, key: 'build', label: 'Build agent', health: 'IDLE' }}
      module={null} onClose={vi.fn()} />)
    expect(screen.getByText(/not yet running/i)).toBeInTheDocument()
  })

  it('closes on the close control', async () => {
    const onClose = vi.fn()
    render(<FactoryNodeDrawer node={node} module={null} onClose={onClose} />)
    await userEvent.click(screen.getByRole('button', { name: /close/i }))
    expect(onClose).toHaveBeenCalled()
  })

  it('closes on Escape', async () => {
    const onClose = vi.fn()
    render(<FactoryNodeDrawer node={node} module={null} onClose={onClose} />)
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Run it, watch it fail, then write the drawer**

Run: `npm test -- factoryNodeDrawer` — FAIL, module does not exist.

`frontend/src/pages/admin/FactoryNodeDrawer.tsx`:

```tsx
import { useEffect } from 'react'
import { X } from 'lucide-react'

import type {
  FactoryFlowNode, FactoryModuleStatus, FactoryNodeHealth,
} from '../../services/softwareFactoryApi'

const HEALTH_LABELS: Record<FactoryNodeHealth, string> = {
  READY: 'Ready',
  DEGRADED: 'Degraded',
  DISABLED: 'Disabled',
  UNAVAILABLE: 'Unknown',
  OFFLINE: 'Offline',
  IDLE: 'Idle',
}

/**
 * Why a node is the way it is, where that is not obvious from the graph.
 *
 * A total record rather than an if-chain with a fallthrough: the old console labelled any
 * unrecognised module "Dry run / backup" precisely because of a fallthrough, and an empty string
 * here is an explicit decision that a node needs no explanation.
 */
const NODE_NOTES: Record<string, string> = {
  platformbackup:
    'Platform backup participates in no loop, so it is drawn off the ring rather than on it. '
    + 'Placing it on the ring would imply a feedback path that does not exist.',
  build:
    'The build agent is declared but not yet running. It is designed to run on a developer '
    + 'machine rather than the production host, so this node is derived entirely from Linear and '
    + 'GitHub. Offline means work is waiting and nothing has picked it up; Idle means there is '
    + 'nothing waiting.',
  linear:
    'Linear is an artifact, and the badge on it is the health of the linear sink module. That '
    + 'module is the factory’s only activity-only task queue — nothing flows through '
    + 'it — so it is not drawn as a box of its own.',
  production:
    'Production state is reported by the platform status page rather than counted here.',
}

export function FactoryNodeDrawer(
  { node, module, onClose, children }: {
    node: FactoryFlowNode | null
    module: FactoryModuleStatus | null
    onClose: () => void
    children?: React.ReactNode
  },
) {
  useEffect(() => {
    if (!node) return undefined
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [node, onClose])

  if (!node) return null

  const note = NODE_NOTES[node.key]
  return (
    <aside
      className="factory-drawer"
      role="dialog"
      aria-modal="true"
      aria-labelledby="factory-drawer-title"
    >
      <header className="factory-drawer__header">
        <h2 id="factory-drawer-title">{node.label}</h2>
        <button className="admin-btn" type="button" onClick={onClose} aria-label="Close details">
          <X size={16} />
        </button>
      </header>

      <p className={`factory-drawer__health factory-drawer__health--${node.health.toLowerCase()}`}>
        {HEALTH_LABELS[node.health]}
      </p>

      {node.counts === null ? (
        <p className="factory-drawer__counts">Counts unknown — the source could not be read.</p>
      ) : (
        <dl className="factory-drawer__counts">
          <div><dt>In flight</dt><dd>{node.counts.inFlight}</dd></div>
          <div><dt>Succeeded (24h)</dt><dd>{node.counts.ok24h}</dd></div>
          <div><dt>Failed (24h)</dt><dd>{node.counts.failed24h}</dd></div>
        </dl>
      )}

      {node.diagnostic && <p className="factory-drawer__diagnostic">{node.diagnostic}</p>}
      {note && <p className="factory-drawer__note">{note}</p>}

      {module && (
        <dl className="factory-drawer__module">
          <div><dt>Task queue</dt><dd>{module.taskQueue}</dd></div>
          <div><dt>Trigger</dt><dd>{module.trigger}</dd></div>
          <div>
            <dt>Pollers</dt>
            <dd>
              {module.workflowPollers ?? '?'} workflow / {module.activityPollers ?? '?'} activity
            </dd>
          </div>
          {module.missingPrerequisites.length > 0 && (
            <div>
              <dt>Missing</dt>
              <dd>{module.missingPrerequisites.join('; ')}</dd>
            </div>
          )}
        </dl>
      )}

      {children}
    </aside>
  )
}
```

Recent runs and artifact item lists are **not** rendered here — they arrive in Task 11, which
adds the API surface for them. The drawer shell ships first so it is independently reviewable.

- [ ] **Step 3: Write the failing page test**

Add to `frontend/tests/softwareFactoryAdmin.test.tsx`:

```tsx
it('replaces the module rail with the flow graph', async () => {
  // Two representations of the same fact on one page is what made "keep the cards" unattractive.
  renderConsoleWithFlow()
  expect(await screen.findByRole('button', { name: /Log watch/ })).toBeInTheDocument()
  expect(screen.queryByRole('list', { name: 'Software Factory modules' })).not.toBeInTheDocument()
})

it('opens the drawer with that module\'s actions when a node is selected', async () => {
  renderConsoleWithFlow()
  await userEvent.click(await screen.findByRole('button', { name: /Log watch/ }))
  const drawer = screen.getByRole('dialog')
  expect(within(drawer).getByRole('button', { name: 'Scan logs now' })).toBeInTheDocument()
})
```

`renderConsoleWithFlow` mocks both `fetchSoftwareFactoryStatus` and `fetchFactoryFlow`. Follow
whatever mocking style the existing tests in this file already use.

- [ ] **Step 4: Rewire the page**

In `SoftwareFactoryAdmin.tsx`:
- Add `flow` state and fetch it alongside status, sharing the same error banner.
- Add `selectedNode` state, defaulting to `null`.
- Delete the `<div className="factory-rail">` block and the `ModuleRow`, `Checkpoint` and
  `actionFor` functions along with their imports. Delete their now-dead tests.
- Render `<FactoryFlowGraph flow={flow} selected={selectedNode} onSelect={setSelectedNode} />`.
- Render `<FactoryNodeDrawer node={...} module={modules.get(selectedNode)} onClose={...}>` with the
  matching `ActionPanel` as its child, chosen by a **total switch** on the node key — not an
  if-chain with a fallthrough, which is exactly what silently mislabelled an unrecognised module
  before. Nodes with no actions (`linear`, `pull-request`, `main`, `production`, `agent-setup`,
  `build`) render the drawer with no action panel.
- Keep every `ActionPanel`'s existing title, description, labels and disabled logic **verbatim**.
  The label uniqueness constraint that made them "Dry run scan" / "Scan logs now" still applies.

- [ ] **Step 5: Run the frontend suite and lint**

Run: `npm test && npm run lint`
Expected: PASS. Lint exits 0 (5 pre-existing `react-refresh` warnings are acceptable, 0 errors).

- [ ] **Step 6: Commit**

```bash
git add frontend/src frontend/tests
git commit -m "feat: open a drawer per factory node and retire the module cards"
```

---

## Task 9: Live refresh

**Files:**
- Modify: `frontend/src/pages/admin/SoftwareFactoryAdmin.tsx`
- Test: `frontend/tests/softwareFactoryAdmin.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
it('polls the flow at the chosen interval and stops when switched off', async () => {
  vi.useFakeTimers()
  const { fetchFlow } = renderConsoleWithFlow()
  await screen.findByRole('button', { name: /Log watch/ })
  const initial = fetchFlow.mock.calls.length

  await userEvent.click(screen.getByRole('radio', { name: '15s' }))
  await vi.advanceTimersByTimeAsync(15_000)
  expect(fetchFlow.mock.calls.length).toBe(initial + 1)

  await userEvent.click(screen.getByRole('radio', { name: 'Off' }))
  await vi.advanceTimersByTimeAsync(60_000)
  expect(fetchFlow.mock.calls.length).toBe(initial + 1)
  vi.useRealTimers()
})
```

- [ ] **Step 2: Run it, watch it fail, then implement**

Add to `SoftwareFactoryAdmin.tsx`:

```tsx
/** Off is the default: a console left open on a second monitor should not poll all day. */
const REFRESH_OPTIONS: { label: string; ms: number | null }[] = [
  { label: 'Off', ms: null },
  { label: '15s', ms: 15_000 },
  { label: '1m', ms: 60_000 },
  { label: '5m', ms: 300_000 },
]

function RefreshInterval(
  { value, onChange }: { value: number | null; onChange: (ms: number | null) => void },
) {
  return (
    <fieldset className="factory-console__refresh" role="radiogroup" aria-label="Refresh interval">
      {REFRESH_OPTIONS.map((option) => (
        <label key={option.label}>
          <input
            type="radio"
            name="factory-refresh"
            checked={value === option.ms}
            onChange={() => onChange(option.ms)}
          />
          {option.label}
        </label>
      ))}
    </fieldset>
  )
}
```

and in the page body:

```tsx
const [refreshMs, setRefreshMs] = useState<number | null>(null)
const loading = useRef(false)

useEffect(() => {
  if (refreshMs === null) return undefined
  const timer = setInterval(() => {
    // A slow response must not queue a second request behind the first; the interval is a
    // floor on spacing, not a guarantee of one request per tick.
    if (loading.current) return
    void reload()
  }, refreshMs)
  return () => clearInterval(timer)
}, [refreshMs, reload])
```

`reload` is the existing status-and-flow fetch, wrapped in `useCallback` and setting
`loading.current` around the await.

- [ ] **Step 3: Run the suite and commit**

```bash
npm test && npm run lint
git add frontend/src/pages/admin/SoftwareFactoryAdmin.tsx frontend/tests/softwareFactoryAdmin.test.tsx
git commit -m "feat: add a live refresh interval to the software factory console"
```

---

## Task 10: Drawer detail — recent runs and artifact items

Task 8 shipped the drawer shell with counts and diagnostics. The spec also requires the drawer to
list *the work itself*: a module's last ten Temporal runs, and an artifact's open items. That needs
API surface the counting endpoints do not provide, so it lands here as its own reviewable slice.

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/FlowDetail.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/flow/FactoryFlowDetailService.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/flow/FactoryFlowController.java`
- Modify: `backend/src/main/java/com/simonrowe/factoryadmin/FactoryAdminClient.java`
- Modify: `backend/src/main/java/com/simonrowe/factoryadmin/FactoryAdminService.java`
- Modify: `backend/src/main/java/com/simonrowe/factoryadmin/FactoryAdminController.java`
- Modify: `frontend/src/services/softwareFactoryApi.ts`
- Modify: `frontend/src/pages/admin/FactoryNodeDrawer.tsx`
- Test: `software-factory/src/test/java/com/simonrowe/factory/flow/FactoryFlowDetailServiceTest.java`
- Test: `frontend/tests/factoryNodeDrawer.test.tsx`

**Interfaces:**
- Consumes: `WorkflowClient.getWorkflowServiceStubs().blockingStub().listWorkflowExecutions(...)`,
  `FactoryFlowTopology.NODES` (for the node key to workflow type mapping),
  `ArtifactCountsReader`'s GitHub helper and `LinearIssueRepository`.
- Produces: `GET /api/factory/flow/{nodeKey}` returning
  `FlowDetail(String nodeKey, List<FlowDetail.Item> items)` where
  `Item(String id, String title, String status, Instant at, String url)`; proxied at
  `GET /api/admin/software-factory/flow/{nodeKey}`; and `fetchFactoryFlowDetail(getAccessToken,
  nodeKey)` on the frontend.

- [ ] **Step 1: Write the failing service test**

```java
package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class FactoryFlowDetailServiceTest {

  @Test
  void listsAModulesRecentRunsNewestFirst() {
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.listWorkflowExecutions(any())).thenReturn(
        ListWorkflowExecutionsResponse.newBuilder()
            .addExecutions(execution("logwatch-2", WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED))
            .addExecutions(execution("logwatch-1", WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED))
            .build());

    FlowDetail detail = service(stub).detail("logwatch");

    assertThat(detail.items()).extracting(FlowDetail.Item::id)
        .containsExactly("logwatch-2", "logwatch-1");
    assertThat(detail.items().get(1).status()).contains("FAILED");
  }

  @Test
  void asksTemporalForTheNodesOwnWorkflowTypeOnly() {
    // A drawer that listed every module's runs would be worse than useless: the operator opened
    // one node.
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.listWorkflowExecutions(any())).thenAnswer(invocation -> {
      ListWorkflowExecutionsRequest request = invocation.getArgument(0);
      assertThat(request.getQuery()).contains("WorkflowType = 'DeployWorkflow'");
      return ListWorkflowExecutionsResponse.getDefaultInstance();
    });

    service(stub).detail("deploy");
  }

  @Test
  void returnsAnEmptyDetailForANodeWithNoWorkflowType() {
    // Artifact nodes are handled by their own branch; an unknown key must not throw and take the
    // whole drawer down.
    assertThat(service(mock(WorkflowServiceBlockingStub.class)).detail("production").items())
        .isEmpty();
  }

  @Test
  void returnsAnEmptyDetailWhenTemporalCannotBeReached() {
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.listWorkflowExecutions(any())).thenThrow(new RuntimeException("unavailable"));

    assertThat(service(stub).detail("logwatch").items()).isEmpty();
  }

  private FactoryFlowDetailService service(final WorkflowServiceBlockingStub stub) {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    when(stubs.blockingStub()).thenReturn(stub);
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(stubs);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    return new FactoryFlowDetailService(client);
  }

  private static WorkflowExecutionInfo execution(
      final String id, final WorkflowExecutionStatus status) {
    return WorkflowExecutionInfo.newBuilder()
        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(id).build())
        .setStatus(status)
        .build();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :software-factory:test --tests '*FactoryFlowDetailServiceTest'`
Expected: FAIL — `FactoryFlowDetailService` and `FlowDetail` do not exist.

- [ ] **Step 3: Write the detail types and service**

`.../flow/FlowDetail.java`:

```java
package com.simonrowe.factory.flow;

import java.time.Instant;
import java.util.List;

/**
 * The work behind one node, for its drawer.
 *
 * @param nodeKey the node this describes
 * @param items the work, newest first; empty when there is none or the source could not be read
 */
public record FlowDetail(String nodeKey, List<Item> items) {

  /**
   * One piece of work.
   *
   * @param id the workflow id, pull request number or Linear identifier
   * @param title what it is
   * @param status its state, already normalised to a word an operator reads
   * @param at when it started or was last seen, or null
   * @param url somewhere to open it, or null
   */
  public record Item(String id, String title, String status, Instant at, String url) {
  }

  /** Nothing to show. Used for artifact nodes with no list and for an unreadable source alike. */
  public static FlowDetail empty(final String nodeKey) {
    return new FlowDetail(nodeKey, List.of());
  }
}
```

`.../flow/FactoryFlowDetailService.java` maps `nodeKey` to `NodeDescriptor.workflowType()` via
`FactoryFlowTopology.NODES`, returns `FlowDetail.empty(nodeKey)` when there is no workflow type,
and otherwise issues one `ListWorkflowExecutionsRequest` with
`query = "WorkflowType = '<type>'"`, `pageSize = 10`, mapping each `WorkflowExecutionInfo` to an
`Item` with `execution().getWorkflowId()` as both id and title, `status().name()` as status and
`getStartTime()` converted to an `Instant`. Every failure returns `FlowDetail.empty(nodeKey)` —
a drawer that throws takes the whole page down for a detail panel.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :software-factory:test --tests '*FactoryFlowDetailServiceTest' :software-factory:checkstyleMain`
Expected: PASS, 4 tests.

- [ ] **Step 5: Add the endpoint, the proxy and the client**

In `FactoryFlowController`:

```java
  /**
   * Returns the work behind one node.
   *
   * @param nodeKey the node whose drawer is open
   * @return that node's items, newest first
   */
  @GetMapping("/{nodeKey}")
  public FlowDetail detail(@PathVariable final String nodeKey) {
    return detailService.detail(nodeKey);
  }
```

In `FactoryAdminClient`, a `factoryFlowDetail(String nodeKey)` mirroring `factoryFlow()` but on
`/api/factory/flow/{nodeKey}`, and a `deployerFlowDetail(String nodeKey)` used only when
`DEPLOYER_OWNED.contains(nodeKey)`. In `FactoryAdminService`, a `flowDetail(String nodeKey)` that
picks the container by that same check and returns an empty detail when the deployer is
unreachable. In `FactoryAdminController`, `@GetMapping("/flow/{nodeKey}")`.

In `softwareFactoryApi.ts`:

```ts
export interface FactoryFlowDetailItem {
  id: string
  title: string
  status: string
  at: string | null
  url: string | null
}

export interface FactoryFlowDetail {
  nodeKey: string
  items: FactoryFlowDetailItem[]
}

export const fetchFactoryFlowDetail = (getAccessToken: GetAccessToken, nodeKey: string) =>
  request<FactoryFlowDetail>(getAccessToken, `/flow/${encodeURIComponent(nodeKey)}`)
```

- [ ] **Step 6: Write the failing drawer test, then render the list**

Add to `frontend/tests/factoryNodeDrawer.test.tsx`:

```tsx
it('lists the recent runs it was given', () => {
  render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()}
    detail={{ nodeKey: 'logwatch', items: [
      { id: 'logwatch-2', title: 'logwatch-2', status: 'COMPLETED', at: null, url: null },
    ] }} />)
  expect(screen.getByText('logwatch-2')).toBeInTheDocument()
})

it('says so plainly when a node has no recent work', () => {
  // An empty list and a list that failed to load must not look the same.
  render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()}
    detail={{ nodeKey: 'logwatch', items: [] }} />)
  expect(screen.getByText(/No runs in the last 30 days/i)).toBeInTheDocument()
})

it('says so plainly when the detail has not loaded', () => {
  render(<FactoryNodeDrawer node={node} module={null} onClose={vi.fn()} detail={null} />)
  expect(screen.getByText(/Loading/i)).toBeInTheDocument()
})
```

Then add an optional `detail: FactoryFlowDetail | null` prop to `FactoryNodeDrawer`, rendering an
`<ol>` of items, "No runs in the last 30 days." for an empty list and "Loading…" for `null`. The
page fetches the detail when `selectedNode` changes and passes it down.

- [ ] **Step 7: Run everything and commit**

```bash
./gradlew :software-factory:test :software-factory:checkstyleMain :backend:test :backend:checkstyleMain
cd frontend && npm test && npm run lint && cd ..
git add software-factory/src backend/src frontend/src frontend/tests
git commit -m "feat: list a factory node's recent work in its drawer"
```

---

## Task 11: Documentation

**Files:**
- Modify: `docs/runbooks/software-factory.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add a "Factory flow console" section to the runbook**

Cover: what the twelve nodes are; that the topology is code in `FactoryFlowTopology` and adding a
module without adding it to the graph fails `FactoryFlowTopologyTest`; that counts come from
Temporal visibility rather than any collection, which is why `codereview` is countable at all;
that `/api/factory/flow` is token-protected while `/api/factory/status` is not, and why; the
deployer exemption resolved in Task 5 Step 4; that `IDLE` and `OFFLINE` are different facts; and
that `platformbackup` has no edges by design.

- [ ] **Step 2: Add a `044-factory-flow-console` entry to CLAUDE.md's Recent Changes**

Lead with the load-bearing facts, in this repo's established style: the Temporal-visibility data
source and why no module gained persistence; the `linear`-module-is-not-a-node resolution; the
`IDLE`/`OFFLINE` distinction; the accessibility structure (buttons in main-loop order, SVG
`aria-hidden`); and the `build` node shipping unstaffed on purpose.

**Do not run `.specify/scripts/bash/update-agent-context.sh`** — it fails with
`grep: repetition-operator operand invalid` and silently strips the lead line from eight existing
entries.

- [ ] **Step 3: Run the full verification and commit**

```bash
./gradlew :software-factory:test :software-factory:checkstyleMain :backend:test :backend:checkstyleMain
cd frontend && npm test && npm run lint && cd ..
git add docs/runbooks/software-factory.md CLAUDE.md
git commit -m "docs: document the factory flow console"
```

---

## Verification before opening the pull request

- [ ] `./gradlew :software-factory:test` — expect 582 baseline plus the new tests, all passing.
- [ ] `./gradlew :backend:test` — expect 1160 baseline plus the new tests, all passing.
- [ ] `./gradlew :software-factory:checkstyleMain :backend:checkstyleMain` — clean.
- [ ] `cd frontend && npm test && npm run lint` — passing, lint 0 errors.
- [ ] `./scripts/test/run-tests.sh` — unchanged and passing.
- [ ] Start the local stack (`local-env` skill) and open `/admin/software-factory`. Confirm: twelve
      nodes render; tab order follows the main loop; each node opens a drawer; the log-watch drawer
      still offers "Dry run scan" and "Scan logs now"; the window narrower than 50rem drops the SVG
      and stacks the buttons; the `build` node reads Idle or Offline, not an error.
- [ ] Open the pull request with the `pr-review-loop` skill. Do not improvise the review loop.
