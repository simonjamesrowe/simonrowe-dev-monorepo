# Langfuse Sessions, Content Capture & Evaluations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Langfuse traces useful — named traces grouped into sessions, carrying real prompt/completion content, scored by deterministic backend signals and LLM-as-a-judge evaluators.

**Architecture:** One Micrometer `Observation` per chat turn carries `session.id` plus `langfuse.trace.input`/`.output`; Langfuse's `hasTraceUpdates()` patches those onto the trace even though the HTTP root span is filtered out. A custom `ObservationFilter` copies prompt/completion onto each generation span. Scores go out-of-band over HTTP because OpenTelemetry has no score channel.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, Micrometer Observation + `micrometer-tracing-bridge-otel`, OpenTelemetry instrumentation 2.30.0, Grafana Alloy (OTTL filter), Langfuse v3.212.0, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-07-26-langfuse-sessions-content-evals-design.md`

## Global Constraints

- **Java style:** Google Java Style via Checkstyle, `config/checkstyle/google_checks.xml`, **`maxWarnings = 0`**. 2-space indent, 100-column limit. A warning fails the build.
- **Coverage:** JaCoCo `jacocoTestCoverageVerification` minimum **0.78**, wired into `tasks.check`. New packages are *not* in `jacocoExcludes`, so new code must be tested.
- **Never break chat.** Observability failures log at WARN and are swallowed. No observability code path may throw into the chat `Flux`.
- **Content attribute cap: 32 KB** per attribute, truncated with a trailing `…[truncated]` marker.
- **Do not restart prod nginx.** It resolves all four upstreams (frontend, backend, portainer, langfuse) at boot and aborts if any is down, taking Portainer offline with it.
- **Prod runs on a Raspberry Pi with no SSH from the dev machine.** Any production step is emitted as a copy-paste block for the owner, never executed here.
- **Stay on Spring AI 1.x.** 2.0.0 requires Spring Boot 4.1 and deletes `spring-ai-starter-model-openai-sdk`, which this project uses.
- **Langfuse attribute names are exact** (verified at tag v3.212.0): `session.id`, `user.id`, `langfuse.trace.name`, `langfuse.trace.input`, `langfuse.trace.output`, `langfuse.observation.input`, `langfuse.observation.output`, `langfuse.environment`.
- **Commit style:** Conventional commits (`feat:`, `fix:`, `chore:`, `docs:`). No Jira refs. No Claude attribution.
- Run backend tests with `cd backend && ../gradlew test`. Full gate: `../gradlew check`.

---

## File Structure

**Created**

| File | Responsibility |
| --- | --- |
| `backend/src/main/java/com/simonrowe/observability/LangfuseAttributes.java` | Single home for the verbatim Langfuse attribute-name constants + the 32 KB truncation helper. |
| `backend/src/main/java/com/simonrowe/observability/LangfuseContentObservationFilter.java` | Copies prompt/completion onto generation spans; remaps Spring AI tool-call content. |
| `backend/src/main/java/com/simonrowe/observability/LangfuseScoreClient.java` | Fire-and-forget `POST /api/public/scores`. |
| `backend/src/main/java/com/simonrowe/observability/LangfuseProperties.java` | `@ConfigurationProperties("langfuse")`. |
| `backend/src/main/java/com/simonrowe/observability/ObservabilityConfig.java` | Bean wiring for the above. |
| `backend/src/main/java/com/simonrowe/chat/GuardrailVerdictRegistry.java` | Session-keyed guardrail verdict handoff. |
| `backend/src/main/java/com/simonrowe/chat/ChatTurnTracer.java` | Per-turn observation lifecycle, trace-level attributes, score emission. |
| `config/alloy/config.local.alloy` | Local traces-only Alloy pipeline (no Loki). |
| `scripts/bootstrap-langfuse-evaluators.sh` | Idempotent LLM connection + evaluator + rule provisioning. |

**Modified**

| File | Change |
| --- | --- |
| `gradle/libs.versions.toml` | Version bumps. |
| `backend/build.gradle.kts` | `opentelemetry.version` ext pin; `opentelemetry-sdk-testing` test dep. |
| `backend/src/main/resources/application.yml` | `langfuse.*` block, tool content property, corrected comment. |
| `backend/src/main/java/com/simonrowe/chat/ChatService.java` | Delegate to `ChatTurnTracer`; drop `@WithSpan`. |
| `backend/src/main/java/com/simonrowe/chat/GuardrailAdvisor.java` | Extract duplicated classify block; publish verdict. |
| `backend/src/main/java/com/simonrowe/chat/ChatConfig.java` | Pass registry into `GuardrailAdvisor`. |
| `backend/src/main/java/com/simonrowe/chat/ChatSessionCleanupService.java` | Clear verdict registry on eviction. |
| `docker-compose.yml` | Langfuse v3 + worker + ClickHouse/Redis/MinIO + Alloy. |
| `config/alloy/config.alloy` | OTTL keep-list gains `langfuse.trace.name`. |
| `scripts/verify-langfuse-trace.sh` | `--expect-session` / `--expect-io` flags. |
| `docs/runbooks/langfuse-observability.md` | Corrections, purge procedure, bind-mount warning. |

---

## Task 1: Dependency bumps

Landed alone so a regression here is unambiguously separable from the feature work.

**Files:**
- Modify: `gradle/libs.versions.toml:2,5,13`
- Modify: `backend/build.gradle.kts:13`

**Interfaces:**
- Consumes: nothing.
- Produces: Spring Boot 3.5.16, Spring AI 1.1.8, OTel instrumentation 2.30.0 on the compile classpath. No API surface.

- [ ] **Step 1: Record the current baseline**

```bash
cd backend && ../gradlew check 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL. If it already fails, stop and report — do not bump versions on a red build.

- [ ] **Step 2: Bump the version catalog**

In `gradle/libs.versions.toml`:

```toml
springBoot = "3.5.16"
opentelemetryInstrumentation = "2.30.0"
springAi = "1.1.8"
```

- [ ] **Step 3: Realign the OpenTelemetry SDK pin**

`backend/build.gradle.kts:13`. OTel instrumentation `2.30.0` imports `opentelemetry-bom 1.64.0` (verified from the instrumentation BOM POM; the current `2.25.0` ↔ `1.59.0` pairing is why the existing pin reads 1.59.0).

```kotlin
ext["opentelemetry.version"] = "1.64.0"
```

- [ ] **Step 4: Verify the resolved versions are what you expect**

```bash
cd backend && ../gradlew dependencies --configuration runtimeClasspath 2>/dev/null \
  | grep -E "spring-ai-bom|spring-boot-starter-web|opentelemetry-sdk:|opentelemetry-spring-boot-starter" | head
```
Expected: `spring-ai-bom:1.1.8`, Spring Boot artifacts at `3.5.16`, `opentelemetry-sdk:1.64.0`.

- [ ] **Step 5: Run the full gate**

```bash
cd backend && ../gradlew check
```
Expected: BUILD SUCCESSFUL, Checkstyle clean, coverage ≥ 0.78.

If Spring AI 1.1.8 introduces a compile error, report it rather than working around it — the diff `v1.1.4..v1.1.8` is release commits and integration-test fixes only, so a compile break means something unexpected.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml backend/build.gradle.kts
git commit -m "chore: bump Spring Boot 3.5.16, Spring AI 1.1.8, OTel instrumentation 2.30.0"
```

---

## Task 2: Langfuse attribute constants and truncation

Small, pure, and depended on by Tasks 3, 5 and 6 — so it lands first and gets its own test.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/observability/LangfuseAttributes.java`
- Test: `backend/src/test/java/com/simonrowe/observability/LangfuseAttributesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public static final String SESSION_ID = "session.id"`, `TRACE_NAME`, `TRACE_INPUT`, `TRACE_OUTPUT`, `OBSERVATION_INPUT`, `OBSERVATION_OUTPUT`, `ENVIRONMENT`
  - `public static final int MAX_ATTRIBUTE_CHARS = 32_768`
  - `public static String truncate(String value)` — returns `null` for `null`, the input unchanged when within the cap, else the first `MAX_ATTRIBUTE_CHARS` characters followed by `…[truncated]`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/simonrowe/observability/LangfuseAttributesTest.java`:

```java
package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LangfuseAttributesTest {

  @Test
  void truncateReturnsNullForNull() {
    assertThat(LangfuseAttributes.truncate(null)).isNull();
  }

  @Test
  void truncateLeavesShortValueUnchanged() {
    assertThat(LangfuseAttributes.truncate("hello")).isEqualTo("hello");
  }

  @Test
  void truncateLeavesValueAtExactCapUnchanged() {
    final String atCap = "x".repeat(LangfuseAttributes.MAX_ATTRIBUTE_CHARS);

    assertThat(LangfuseAttributes.truncate(atCap)).isEqualTo(atCap);
  }

  @Test
  void truncateAppendsMarkerWhenOverCap() {
    final String overCap = "x".repeat(LangfuseAttributes.MAX_ATTRIBUTE_CHARS + 1);

    final String result = LangfuseAttributes.truncate(overCap);

    assertThat(result).hasSize(LangfuseAttributes.MAX_ATTRIBUTE_CHARS + "…[truncated]".length());
    assertThat(result).endsWith("…[truncated]");
    assertThat(result).startsWith("xxx");
  }

  @Test
  void attributeNamesMatchLangfuseSpelling() {
    assertThat(LangfuseAttributes.SESSION_ID).isEqualTo("session.id");
    assertThat(LangfuseAttributes.TRACE_NAME).isEqualTo("langfuse.trace.name");
    assertThat(LangfuseAttributes.TRACE_INPUT).isEqualTo("langfuse.trace.input");
    assertThat(LangfuseAttributes.TRACE_OUTPUT).isEqualTo("langfuse.trace.output");
    assertThat(LangfuseAttributes.OBSERVATION_INPUT).isEqualTo("langfuse.observation.input");
    assertThat(LangfuseAttributes.OBSERVATION_OUTPUT).isEqualTo("langfuse.observation.output");
    assertThat(LangfuseAttributes.ENVIRONMENT).isEqualTo("langfuse.environment");
  }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
cd backend && ../gradlew test --tests '*LangfuseAttributesTest'
```
Expected: compilation failure — `LangfuseAttributes` does not exist.

- [ ] **Step 3: Implement**

`backend/src/main/java/com/simonrowe/observability/LangfuseAttributes.java`:

```java
package com.simonrowe.observability;

/**
 * Span attribute names that Langfuse recognises, taken verbatim from
 * {@code packages/shared/src/server/otel/attributes.ts} at tag v3.212.0.
 *
 * <p>These are load-bearing strings: Langfuse silently ignores anything it does not
 * recognise, so a typo produces an empty trace field rather than an error.
 */
public final class LangfuseAttributes {

  /** Groups traces into a Langfuse Session. */
  public static final String SESSION_ID = "session.id";

  /** Trace display name; without it Langfuse falls back to the root span name. */
  public static final String TRACE_NAME = "langfuse.trace.name";

  /** Trace-level input. Applied by Langfuse's hasTraceUpdates() even from a non-root span. */
  public static final String TRACE_INPUT = "langfuse.trace.input";

  /** Trace-level output. */
  public static final String TRACE_OUTPUT = "langfuse.trace.output";

  /** Highest-precedence observation input mapping. */
  public static final String OBSERVATION_INPUT = "langfuse.observation.input";

  /** Highest-precedence observation output mapping. */
  public static final String OBSERVATION_OUTPUT = "langfuse.observation.output";

  /** Separates development traffic from production within one project. */
  public static final String ENVIRONMENT = "langfuse.environment";

  /**
   * Cap on any single content attribute. The chat system prompt alone is roughly 6 KB before
   * retrieval context is appended, and a tool-using turn produces several generations, so
   * uncapped capture risks the 4 MB gRPC message limit and bloats ClickHouse on the Pi.
   */
  public static final int MAX_ATTRIBUTE_CHARS = 32_768;

  private static final String TRUNCATION_MARKER = "…[truncated]";

  private LangfuseAttributes() {
  }

  /**
   * Caps a content value at {@link #MAX_ATTRIBUTE_CHARS}, appending a visible marker so a
   * truncated value is never mistaken for a complete one.
   *
   * @param value the content, may be null
   * @return null if the input was null, otherwise the capped value
   */
  public static String truncate(final String value) {
    if (value == null || value.length() <= MAX_ATTRIBUTE_CHARS) {
      return value;
    }
    return value.substring(0, MAX_ATTRIBUTE_CHARS) + TRUNCATION_MARKER;
  }
}
```

- [ ] **Step 4: Run the test**

```bash
cd backend && ../gradlew test --tests '*LangfuseAttributesTest'
```
Expected: PASS, 5 tests.

- [ ] **Step 5: Checkstyle**

```bash
cd backend && ../gradlew checkstyleMain checkstyleTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/simonrowe/observability/LangfuseAttributes.java \
        backend/src/test/java/com/simonrowe/observability/LangfuseAttributesTest.java
git commit -m "feat: add Langfuse span attribute constants with content truncation"
```

---

## Task 3: Guardrail verdict registry

**Files:**
- Create: `backend/src/main/java/com/simonrowe/chat/GuardrailVerdictRegistry.java`
- Test: `backend/src/test/java/com/simonrowe/chat/GuardrailVerdictRegistryTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `void record(String sessionId, String verdict)`
  - `String takeVerdict(String sessionId)` — returns the verdict and **removes** it; `null` when absent.
  - `void clearSession(String sessionId)`

Deliberately mirrors the existing `ChatContactTracker` (`backend/src/main/java/com/simonrowe/chat/ChatContactTracker.java`) so the codebase keeps one pattern for session-scoped state. Read-and-remove semantics are what stop the map growing without bound.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/simonrowe/chat/GuardrailVerdictRegistryTest.java`:

```java
package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GuardrailVerdictRegistryTest {

  private final GuardrailVerdictRegistry registry = new GuardrailVerdictRegistry();

  @Test
  void takeVerdictReturnsRecordedVerdict() {
    registry.record("session-1", "SAFE");

    assertThat(registry.takeVerdict("session-1")).isEqualTo("SAFE");
  }

  @Test
  void takeVerdictRemovesTheEntrySoItIsReadOnce() {
    registry.record("session-1", "OFF_TOPIC");

    assertThat(registry.takeVerdict("session-1")).isEqualTo("OFF_TOPIC");
    assertThat(registry.takeVerdict("session-1")).isNull();
  }

  @Test
  void takeVerdictReturnsNullForUnknownSession() {
    assertThat(registry.takeVerdict("never-seen")).isNull();
  }

  @Test
  void recordOverwritesThePreviousVerdictForTheSameSession() {
    registry.record("session-1", "SAFE");
    registry.record("session-1", "HARMFUL");

    assertThat(registry.takeVerdict("session-1")).isEqualTo("HARMFUL");
  }

  @Test
  void recordIgnoresNullSessionIdAndNullVerdict() {
    registry.record(null, "SAFE");
    registry.record("session-2", null);

    assertThat(registry.takeVerdict("session-2")).isNull();
  }

  @Test
  void clearSessionRemovesTheEntry() {
    registry.record("session-1", "SAFE");

    registry.clearSession("session-1");

    assertThat(registry.takeVerdict("session-1")).isNull();
  }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
cd backend && ../gradlew test --tests '*GuardrailVerdictRegistryTest'
```
Expected: compilation failure — `GuardrailVerdictRegistry` does not exist.

- [ ] **Step 3: Implement**

`backend/src/main/java/com/simonrowe/chat/GuardrailVerdictRegistry.java`:

```java
package com.simonrowe.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Carries the guardrail classification from {@link GuardrailAdvisor}, which computes it deep
 * inside the advisor chain, out to {@link ChatTurnTracer}, which reports it to Langfuse as a
 * score at the end of the turn.
 *
 * <p>Entries are removed on read, so a turn that is never scored leaves at most one stale entry
 * per session, and {@link ChatSessionCleanupService} clears those on eviction.
 */
@Component
public class GuardrailVerdictRegistry {

  private final Map<String, String> verdicts = new ConcurrentHashMap<>();

  /**
   * Records the classification for a session. Null arguments are ignored rather than throwing,
   * because guardrail bookkeeping must never break a chat turn.
   *
   * @param sessionId the chat session id
   * @param verdict SAFE, OFF_TOPIC or HARMFUL
   */
  public void record(final String sessionId, final String verdict) {
    if (sessionId == null || verdict == null) {
      return;
    }
    verdicts.put(sessionId, verdict);
  }

  /**
   * Reads and removes the verdict for a session.
   *
   * @param sessionId the chat session id
   * @return the verdict, or null if none was recorded
   */
  public String takeVerdict(final String sessionId) {
    if (sessionId == null) {
      return null;
    }
    return verdicts.remove(sessionId);
  }

  public void clearSession(final String sessionId) {
    verdicts.remove(sessionId);
  }
}
```

- [ ] **Step 4: Run the test**

```bash
cd backend && ../gradlew test --tests '*GuardrailVerdictRegistryTest'
```
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/chat/GuardrailVerdictRegistry.java \
        backend/src/test/java/com/simonrowe/chat/GuardrailVerdictRegistryTest.java
git commit -m "feat: add guardrail verdict registry for chat turn scoring"
```

---

## Task 4: Refactor GuardrailAdvisor and publish the verdict

`adviseCall` (lines 77–115) and `adviseStream` (lines 117–157) are near-identical copies of the same 38-line classification block. Both need the new publication step, so extracting it now prevents the duplication doubling.

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/chat/GuardrailAdvisor.java`
- Modify: `backend/src/main/java/com/simonrowe/chat/ChatConfig.java:42-60`
- Modify: `backend/src/main/java/com/simonrowe/chat/ChatSessionCleanupService.java`
- Test: `backend/src/test/java/com/simonrowe/chat/GuardrailAdvisorTest.java`

**Interfaces:**
- Consumes: `GuardrailVerdictRegistry.record(String, String)` (Task 3).
- Produces: `GuardrailAdvisor(ChatModel chatModel, GuardrailVerdictRegistry verdictRegistry)` — the single-argument constructor is **replaced**, not overloaded, so every call site must be updated.

- [ ] **Step 1: Add failing tests for verdict publication**

Append to `backend/src/test/java/com/simonrowe/chat/GuardrailAdvisorTest.java`. Note `requestFor` in the existing file builds a request with an empty context; verdict publication needs the conversation id, so add a helper alongside it:

```java
  private static ChatClientRequest requestFor(final String text, final String sessionId) {
    final java.util.Map<String, Object> context = new HashMap<>();
    context.put(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, sessionId);
    return new ChatClientRequest(new Prompt(new UserMessage(text)), context);
  }

  @Test
  void testSafeVerdictIsPublishedToRegistryOnCallPath() {
    GuardrailVerdictRegistry registry = new GuardrailVerdictRegistry();
    GuardrailAdvisor advisor = new GuardrailAdvisor(classifierReturning("SAFE"), registry);
    ChatClientRequest request = requestFor("What does he blog about?", "session-1");
    CallAdvisorChain chain = mock(CallAdvisorChain.class);
    when(chain.nextCall(request)).thenReturn(answer("Java"));

    advisor.adviseCall(request, chain);

    assertEquals("SAFE", registry.takeVerdict("session-1"));
  }

  @Test
  void testOffTopicVerdictIsPublishedToRegistryOnStreamPath() {
    GuardrailVerdictRegistry registry = new GuardrailVerdictRegistry();
    GuardrailAdvisor advisor = new GuardrailAdvisor(classifierReturning("OFF_TOPIC"), registry);
    ChatClientRequest request = requestFor("What is the weather?", "session-2");
    StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

    advisor.adviseStream(request, chain).blockLast();

    assertEquals("OFF_TOPIC", registry.takeVerdict("session-2"));
    verify(chain, never()).nextStream(any());
  }

  @Test
  void testClassifierFailureRecordsNoVerdictAndFailsOpen() {
    GuardrailVerdictRegistry registry = new GuardrailVerdictRegistry();
    ChatModel failing = mock(ChatModel.class);
    when(failing.call(any(Prompt.class))).thenThrow(new RuntimeException("classifier down"));
    GuardrailAdvisor advisor = new GuardrailAdvisor(failing, registry);
    ChatClientRequest request = requestFor("Anything", "session-3");
    CallAdvisorChain chain = mock(CallAdvisorChain.class);
    when(chain.nextCall(request)).thenReturn(answer("still answered"));

    ChatClientResponse response = advisor.adviseCall(request, chain);

    assertEquals("still answered", response.chatResponse().getResult().getOutput().getText());
    assertEquals(null, registry.takeVerdict("session-3"));
  }
```

Every existing test in this file constructs `new GuardrailAdvisor(classifierReturning(...))`. Update each to pass `new GuardrailVerdictRegistry()` as the second argument.

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && ../gradlew test --tests '*GuardrailAdvisorTest'
```
Expected: compilation failure — no two-argument constructor.

- [ ] **Step 3: Refactor GuardrailAdvisor**

Replace the constructor, and replace the bodies of `adviseCall`/`adviseStream` with calls to one extracted method. Keep `PIVOT_MESSAGE`, `CLASSIFICATION_PROMPT_TEMPLATE`, `classificationPrompt`, `getName` and `getOrder` exactly as they are.

```java
  private final ChatModel chatModel;
  private final GuardrailVerdictRegistry verdictRegistry;

  public GuardrailAdvisor(final ChatModel chatModel,
      final GuardrailVerdictRegistry verdictRegistry) {
    this.chatModel = chatModel;
    this.verdictRegistry = verdictRegistry;
  }

  /**
   * Classifies the request and records the verdict for scoring. Returns null when the input
   * cannot be classified or the classifier fails, which callers treat as "proceed" — the gate
   * fails open by design.
   *
   * @param request the inbound chat request
   * @return SAFE, OFF_TOPIC, HARMFUL, or null to proceed without a verdict
   */
  private String classify(final ChatClientRequest request) {
    String userText = null;
    if (request.prompt() != null && request.prompt().getUserMessage() != null) {
      userText = request.prompt().getUserMessage().getText();
    }
    if (userText == null || userText.isBlank()) {
      return null;
    }

    try {
      ChatResponse classificationResponse = chatModel.call(
          new Prompt(classificationPrompt(userText),
              OpenAiChatOptions.builder()
                  .model("gpt-4o-mini")
                  .temperature(0.0)
                  .build()));
      if (classificationResponse == null || classificationResponse.getResult() == null
          || classificationResponse.getResult().getOutput() == null
          || classificationResponse.getResult().getOutput().getText() == null) {
        return null;
      }
      String classification =
          classificationResponse.getResult().getOutput().getText().trim().toUpperCase();
      verdictRegistry.record(conversationId(request), classification);
      return classification;
    } catch (Exception e) {
      log.warn("Error calling classification model in GuardrailAdvisor. Failing open.", e);
      return null;
    }
  }

  private static String conversationId(final ChatClientRequest request) {
    if (request.context() == null) {
      return null;
    }
    Object id = request.context().get(ChatMemory.CONVERSATION_ID);
    return id instanceof String value ? value : null;
  }

  private static boolean isBlocked(final String classification) {
    return classification != null
        && (classification.contains("OFF_TOPIC") || classification.contains("HARMFUL"));
  }

  private static ChatClientResponse pivotResponse(final ChatClientRequest request) {
    ChatResponse response =
        new ChatResponse(List.of(new Generation(new AssistantMessage(PIVOT_MESSAGE))));
    return new ChatClientResponse(
        response, request.context() != null ? request.context() : new HashMap<>());
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    if (isBlocked(classify(request))) {
      return pivotResponse(request);
    }
    return chain.nextCall(request);
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(
      ChatClientRequest request, StreamAdvisorChain chain) {
    if (isBlocked(classify(request))) {
      return Flux.just(pivotResponse(request));
    }
    return chain.nextStream(request);
  }
```

Add `import org.springframework.ai.chat.memory.ChatMemory;`.

- [ ] **Step 4: Update the ChatConfig call site**

`backend/src/main/java/com/simonrowe/chat/ChatConfig.java`. Add the registry to the `chatClient` bean signature and pass it through:

```java
  @Bean
  public ChatClient chatClient(final ChatClient.Builder builder,
      final ChatMemory chatMemory, final ProfileMcpTools profileMcpTools,
      final WebSearchTools webSearchTools, final FetchUrlTools fetchUrlTools,
      final VectorStore vectorStore, final ChatModel chatModel,
      final GuardrailVerdictRegistry verdictRegistry) {
    return builder
        .defaultSystem(systemPrompt + "\n\n" + widgetPromptGuidance())
        .defaultAdvisors(
            new GuardrailAdvisor(chatModel, verdictRegistry),
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            ContextAwareQuestionAnswerAdvisor.builder(vectorStore, chatMemory)
                .searchRequest(SearchRequest.builder()
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .topK(TOP_K)
                    .build())
                .build()
        )
        .defaultTools(profileMcpTools, webSearchTools, fetchUrlTools)
        .build();
  }
```

- [ ] **Step 5: Clear the registry on session eviction**

`backend/src/main/java/com/simonrowe/chat/ChatSessionCleanupService.java`. Add a constructor parameter and field `GuardrailVerdictRegistry verdictRegistry`, then inside the `staleIds.forEach` lambda add:

```java
        verdictRegistry.clearSession(id);
```

so the body reads:

```java
      staleIds.forEach(id -> {
        chatService.evictSession(id);
        contactTracker.clearSession(id);
        verdictRegistry.clearSession(id);
      });
```

Update `ChatSessionCleanupServiceTest` constructor calls if that test exists — check with `ls backend/src/test/java/com/simonrowe/chat/`.

- [ ] **Step 6: Run the chat test package**

```bash
cd backend && ../gradlew test --tests 'com.simonrowe.chat.*'
```
Expected: PASS. All pre-existing `GuardrailAdvisorTest` behaviour tests must still pass unchanged — the refactor is behaviour-preserving, including failing open.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/simonrowe/chat/ backend/src/test/java/com/simonrowe/chat/
git commit -m "refactor: extract GuardrailAdvisor classification and publish verdict for scoring"
```

---

## Task 5: Langfuse configuration properties

**Files:**
- Create: `backend/src/main/java/com/simonrowe/observability/LangfuseProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/simonrowe/observability/LangfusePropertiesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `LangfuseProperties` with getters/setters for `host` (String, default `https://langfuse.simonrowe.dev`), `publicKey` (String), `secretKey` (String), `environment` (String, default `development`), `scoresEnabled` (boolean, default `false`), `contentCaptureEnabled` (boolean, default `true`).

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class LangfusePropertiesTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(TestConfig.class);

  @Test
  void defaultsAreSafeWhenNothingIsConfigured() {
    runner.run(context -> {
      LangfuseProperties properties = context.getBean(LangfuseProperties.class);
      assertThat(properties.getHost()).isEqualTo("https://langfuse.simonrowe.dev");
      assertThat(properties.getEnvironment()).isEqualTo("development");
      assertThat(properties.isScoresEnabled()).isFalse();
      assertThat(properties.isContentCaptureEnabled()).isTrue();
    });
  }

  @Test
  void propertiesBindFromConfiguration() {
    runner.withPropertyValues(
        "langfuse.host=http://localhost:3000",
        "langfuse.public-key=pk-test",
        "langfuse.secret-key=sk-test",
        "langfuse.environment=production",
        "langfuse.scores-enabled=true",
        "langfuse.content-capture-enabled=false"
    ).run(context -> {
      LangfuseProperties properties = context.getBean(LangfuseProperties.class);
      assertThat(properties.getHost()).isEqualTo("http://localhost:3000");
      assertThat(properties.getPublicKey()).isEqualTo("pk-test");
      assertThat(properties.getSecretKey()).isEqualTo("sk-test");
      assertThat(properties.getEnvironment()).isEqualTo("production");
      assertThat(properties.isScoresEnabled()).isTrue();
      assertThat(properties.isContentCaptureEnabled()).isFalse();
    });
  }

  @Configuration
  @EnableConfigurationProperties(LangfuseProperties.class)
  static class TestConfig {
  }
}
```

Remove the unused `AutoConfigurations` import before committing — Checkstyle's `maxWarnings = 0` will reject it.

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && ../gradlew test --tests '*LangfusePropertiesTest'
```
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.simonrowe.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for Langfuse trace enrichment and score submission. */
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

  private String host = "https://langfuse.simonrowe.dev";
  private String publicKey;
  private String secretKey;

  /** Tags traces so local traffic is distinguishable from production in one project. */
  private String environment = "development";

  /** Off by default: score submission needs project keys, which are not always present. */
  private boolean scoresEnabled;

  /** On by default: capturing prompt/completion content is the point of this feature. */
  private boolean contentCaptureEnabled = true;

  public String getHost() {
    return host;
  }

  public void setHost(final String host) {
    this.host = host;
  }

  public String getPublicKey() {
    return publicKey;
  }

  public void setPublicKey(final String publicKey) {
    this.publicKey = publicKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public void setSecretKey(final String secretKey) {
    this.secretKey = secretKey;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(final String environment) {
    this.environment = environment;
  }

  public boolean isScoresEnabled() {
    return scoresEnabled;
  }

  public void setScoresEnabled(final boolean scoresEnabled) {
    this.scoresEnabled = scoresEnabled;
  }

  public boolean isContentCaptureEnabled() {
    return contentCaptureEnabled;
  }

  public void setContentCaptureEnabled(final boolean contentCaptureEnabled) {
    this.contentCaptureEnabled = contentCaptureEnabled;
  }
}
```

- [ ] **Step 4: Add configuration to application.yml**

Append a top-level block (sibling of `chat:`, `search:` etc.):

```yaml
langfuse:
  host: ${LANGFUSE_HOST:https://langfuse.simonrowe.dev}
  public-key: ${LANGFUSE_PUBLIC_KEY:}
  secret-key: ${LANGFUSE_SECRET_KEY:}
  environment: ${LANGFUSE_ENVIRONMENT:development}
  scores-enabled: ${LANGFUSE_SCORES_ENABLED:false}
  content-capture-enabled: ${LANGFUSE_CONTENT_CAPTURE_ENABLED:true}
```

- [ ] **Step 5: Enable Spring AI's built-in tool content capture**

In the same file, inside the existing `spring.ai` block, add:

```yaml
    tools:
      observations:
        include-content: true
```

This activates Spring AI's own `ToolCallingContentObservationFilter` (present unchanged in 1.1.4 and 1.1.8), which adds `spring.ai.tool.call.arguments` and `spring.ai.tool.call.result` span attributes. Task 6 remaps them to Langfuse's names.

- [ ] **Step 6: Replace the misleading observability comment**

Delete the existing commented block under `spring.ai` that begins `# Observability content capture (evaluated for chat fix-up, Langfuse tracing).` and ends `# Decision (2026-07-17): leave content capture OFF; keep spans only.` — it recommends `log-prompt`/`log-completion`, which only write SLF4J log lines. Replace with:

```yaml
    # Observability content capture.
    #
    # NOTE: spring.ai.chat.observations.log-prompt / log-completion do NOT write span
    # attributes — verified in Spring AI 1.1.8 source, ChatModelPromptContentObservationHandler
    # only calls logger.info(). AiObservationAttributes has no prompt/completion constant, so
    # gen_ai.prompt / gen_ai.completion are never emitted by the framework at any 1.x or 2.x
    # version. Prompt and completion capture is therefore done by
    # com.simonrowe.observability.LangfuseContentObservationFilter, toggled with
    # langfuse.content-capture-enabled above.
    #
    # Decision (2026-07-26): content capture is ON, reversing the 2026-07-17 decision. Visitor
    # chat text — including recruiter-pasted job specs and contact-form details — is stored in
    # Langfuse. See docs/superpowers/specs/2026-07-26-langfuse-sessions-content-evals-design.md.
```

- [ ] **Step 7: Run the test**

```bash
cd backend && ../gradlew test --tests '*LangfusePropertiesTest'
```
Expected: PASS, 2 tests.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/simonrowe/observability/LangfuseProperties.java \
        backend/src/test/java/com/simonrowe/observability/LangfusePropertiesTest.java \
        backend/src/main/resources/application.yml
git commit -m "feat: add Langfuse configuration properties and enable tool content capture"
```

---

## Task 6: Content observation filter

**Files:**
- Create: `backend/src/main/java/com/simonrowe/observability/LangfuseContentObservationFilter.java`
- Create: `backend/src/main/java/com/simonrowe/observability/ObservabilityConfig.java`
- Test: `backend/src/test/java/com/simonrowe/observability/LangfuseContentObservationFilterTest.java`

**Interfaces:**
- Consumes: `LangfuseAttributes` (Task 2), `LangfuseProperties` (Task 5).
- Produces: `LangfuseContentObservationFilter implements ObservationFilter`, constructed as `new LangfuseContentObservationFilter(LangfuseProperties properties)`.

**Why this design.** Two distinct jobs in one filter:
1. For a `ChatModelObservationContext`, read `getRequest()` (a `Prompt`) and `getResponse()` (a `ChatResponse`) and write `langfuse.observation.input`/`.output`. `getResponse()` **is** populated with the fully aggregated completion at stop time even for streaming — `OpenAiSdkChatModel.internalStream` calls `observationContext.setResponse(aggregated)` synchronously inside the stream body, before the outer `doFinally(observation::stop)`.
2. For any context already carrying `spring.ai.tool.call.arguments` / `spring.ai.tool.call.result` (added by Spring AI's own filter, enabled in Task 5), copy them onto the Langfuse names. This is why the bean is registered at `LOWEST_PRECEDENCE` — Micrometer applies `ObservationFilter`s in bean order, so ours must run last.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

class LangfuseContentObservationFilterTest {

  private static LangfuseProperties enabledProperties() {
    LangfuseProperties properties = new LangfuseProperties();
    properties.setContentCaptureEnabled(true);
    return properties;
  }

  private static String valueOf(final Observation.Context context, final String key) {
    return context.getHighCardinalityKeyValues().stream()
        .filter(keyValue -> keyValue.getKey().equals(key))
        .map(KeyValue::getValue)
        .findFirst()
        .orElse(null);
  }

  private static ChatModelObservationContext chatContext(final String userText,
      final String assistantText) {
    ChatModelObservationContext context = ChatModelObservationContext.builder()
        .prompt(new Prompt(new UserMessage(userText)))
        .build();
    if (assistantText != null) {
      context.setResponse(new ChatResponse(
          List.of(new Generation(new AssistantMessage(assistantText)))));
    }
    return context;
  }

  @Test
  void writesPromptAndCompletionOntoLangfuseAttributes() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());

    Observation.Context result = filter.map(chatContext("What is Kafka?", "A log."));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).contains("What is Kafka?");
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT)).contains("A log.");
  }

  @Test
  void omitsOutputWhenResponseIsAbsent() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());

    Observation.Context result = filter.map(chatContext("Question with no answer", null));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).isNotNull();
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
  }

  @Test
  void truncatesContentThatExceedsTheCap() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());
    String huge = "y".repeat(LangfuseAttributes.MAX_ATTRIBUTE_CHARS + 500);

    Observation.Context result = filter.map(chatContext(huge, "short"));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).endsWith("…[truncated]");
  }

  @Test
  void doesNothingWhenContentCaptureIsDisabled() {
    LangfuseProperties disabled = new LangfuseProperties();
    disabled.setContentCaptureEnabled(false);
    LangfuseContentObservationFilter filter = new LangfuseContentObservationFilter(disabled);

    Observation.Context result = filter.map(chatContext("What is Kafka?", "A log."));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).isNull();
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
  }

  @Test
  void remapsSpringAiToolCallContentOntoLangfuseAttributes() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());
    Observation.Context context = new Observation.Context();
    context.addHighCardinalityKeyValue(
        KeyValue.of("spring.ai.tool.call.arguments", "{\"keyword\":\"kafka\"}"));
    context.addHighCardinalityKeyValue(
        KeyValue.of("spring.ai.tool.call.result", "[{\"title\":\"A blog\"}]"));

    Observation.Context result = filter.map(context);

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT))
        .isEqualTo("{\"keyword\":\"kafka\"}");
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT))
        .isEqualTo("[{\"title\":\"A blog\"}]");
  }

  @Test
  void leavesUnrelatedContextsUntouched() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());
    Observation.Context context = new Observation.Context();

    Observation.Context result = filter.map(context);

    assertThat(result.getHighCardinalityKeyValues()).isEmpty();
  }
}
```

`ChatModelObservationContext.builder()` may differ slightly in 1.1.8. If the builder method name does not resolve, run `../gradlew dependencies` to locate the jar and inspect the class with `javap -cp <jar> org.springframework.ai.chat.observation.ChatModelObservationContext`, then adapt the two helper methods only — do not change the assertions.

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && ../gradlew test --tests '*LangfuseContentObservationFilterTest'
```
Expected: compilation failure.

- [ ] **Step 3: Implement the filter**

```java
package com.simonrowe.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/**
 * Copies prompt and completion content onto the span attribute names Langfuse recognises.
 *
 * <p>Spring AI does not do this itself at any version: {@code log-prompt} / {@code
 * log-completion} only produce SLF4J log lines, and {@code AiObservationAttributes} has no
 * prompt or completion constant. Without this filter, Langfuse shows generations with null
 * input and output, which also makes LLM-as-a-judge evaluators useless.
 *
 * <p>Registered at lowest precedence so it runs after Spring AI's own
 * {@code ToolCallingContentObservationFilter}, whose output it remaps.
 */
public class LangfuseContentObservationFilter implements ObservationFilter {

  private static final Logger LOG =
      LoggerFactory.getLogger(LangfuseContentObservationFilter.class);

  private static final String TOOL_ARGUMENTS_KEY = "spring.ai.tool.call.arguments";
  private static final String TOOL_RESULT_KEY = "spring.ai.tool.call.result";

  private final LangfuseProperties properties;

  public LangfuseContentObservationFilter(final LangfuseProperties properties) {
    this.properties = properties;
  }

  @Override
  public Observation.Context map(final Observation.Context context) {
    if (!properties.isContentCaptureEnabled()) {
      return context;
    }
    try {
      if (context instanceof ChatModelObservationContext chatContext) {
        mapChatContent(chatContext);
      }
      remapToolContent(context);
    } catch (Exception e) {
      // Never let observability break the call it is observing.
      LOG.warn("Failed to attach Langfuse content attributes", e);
    }
    return context;
  }

  private void mapChatContent(final ChatModelObservationContext context) {
    if (context.getRequest() != null && context.getRequest().getInstructions() != null) {
      String prompt = context.getRequest().getInstructions().stream()
          .map(LangfuseContentObservationFilter::renderMessage)
          .collect(Collectors.joining("\n"));
      put(context, LangfuseAttributes.OBSERVATION_INPUT, prompt);
    }

    ChatResponse response = context.getResponse();
    if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
      String completion = response.getResults().stream()
          .map(LangfuseContentObservationFilter::renderGeneration)
          .collect(Collectors.joining("\n"));
      put(context, LangfuseAttributes.OBSERVATION_OUTPUT, completion);
    }
  }

  private void remapToolContent(final Observation.Context context) {
    copyKey(context, TOOL_ARGUMENTS_KEY, LangfuseAttributes.OBSERVATION_INPUT);
    copyKey(context, TOOL_RESULT_KEY, LangfuseAttributes.OBSERVATION_OUTPUT);
  }

  private void copyKey(final Observation.Context context, final String from, final String to) {
    context.getHighCardinalityKeyValues().stream()
        .filter(keyValue -> keyValue.getKey().equals(from))
        .map(KeyValue::getValue)
        .findFirst()
        .ifPresent(value -> put(context, to, value));
  }

  private void put(final Observation.Context context, final String key, final String value) {
    String capped = LangfuseAttributes.truncate(value);
    if (capped != null && !capped.isEmpty()) {
      context.addHighCardinalityKeyValue(KeyValue.of(key, capped));
    }
  }

  private static String renderMessage(final Message message) {
    String text = message.getText();
    return message.getMessageType() + ": " + (text == null ? "" : text);
  }

  private static String renderGeneration(final Generation generation) {
    if (generation.getOutput() == null || generation.getOutput().getText() == null) {
      return "";
    }
    return generation.getOutput().getText();
  }
}
```

- [ ] **Step 4: Register the bean**

`backend/src/main/java/com/simonrowe/observability/ObservabilityConfig.java`:

```java
package com.simonrowe.observability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Wires the Langfuse trace-enrichment beans. */
@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
public class ObservabilityConfig {

  /**
   * Lowest precedence so this runs after Spring AI's ToolCallingContentObservationFilter,
   * whose spring.ai.tool.call.* key values this filter remaps onto Langfuse names.
   */
  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  public LangfuseContentObservationFilter langfuseContentObservationFilter(
      final LangfuseProperties properties) {
    return new LangfuseContentObservationFilter(properties);
  }
}
```

- [ ] **Step 5: Run the tests**

```bash
cd backend && ../gradlew test --tests '*LangfuseContentObservationFilterTest'
```
Expected: PASS, 6 tests.

- [ ] **Step 6: Full gate**

```bash
cd backend && ../gradlew check
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/simonrowe/observability/ \
        backend/src/test/java/com/simonrowe/observability/
git commit -m "feat: capture chat prompt and completion content as Langfuse span attributes"
```

---

## Task 7: Langfuse score client

Scores have **no OpenTelemetry channel**. This HTTP client is required regardless of transport.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/observability/LangfuseScore.java`
- Create: `backend/src/main/java/com/simonrowe/observability/LangfuseScoreClient.java`
- Modify: `backend/src/main/java/com/simonrowe/observability/ObservabilityConfig.java`
- Test: `backend/src/test/java/com/simonrowe/observability/LangfuseScoreClientTest.java`

**Interfaces:**
- Consumes: `LangfuseProperties` (Task 5).
- Produces:
  - `record LangfuseScore(String name, Object value, String dataType)` with factories `numeric(String, double)`, `categorical(String, String)`, `bool(String, boolean)`.
  - `LangfuseScoreClient(RestClient.Builder builder, LangfuseProperties properties, Executor executor)`
  - `void submit(String traceId, List<LangfuseScore> scores)` — no-ops when disabled, keys are missing, or `traceId` is null. Never throws.

**API shape** (verified against `fern/apis/server/definition/ingestion.yml` at v3.212.0): `POST /api/public/scores`, HTTP Basic auth with public key as username and secret key as password. Body requires `name` and `value`; `traceId` and `dataType` are optional. `dataType` is one of `NUMERIC`, `BOOLEAN`, `CATEGORICAL`, `CORRECTION`, `TEXT`. **`stringValue` is a response-only field** — categorical scores put the string in `value`. Boolean scores send `1`/`0` in `value` with `dataType: BOOLEAN`.

The trace id is the 32-hex W3C trace id: Langfuse stores OTLP trace ids verbatim.

The injected `Executor` is what makes this testable — production gets a bounded pool, tests get `Runnable::run`.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LangfuseScoreClientTest {

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private LangfuseProperties properties;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    properties = new LangfuseProperties();
    properties.setHost("http://langfuse.test");
    properties.setPublicKey("pk-test");
    properties.setSecretKey("sk-test");
    properties.setScoresEnabled(true);
  }

  private LangfuseScoreClient client() {
    return new LangfuseScoreClient(builder, properties, Runnable::run);
  }

  @Test
  void postsEachScoreWithBasicAuthAndCorrectBody() {
    server.expect(requestTo("http://langfuse.test/api/public/scores"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Basic cGstdGVzdDpzay10ZXN0"))
        .andExpect(jsonPath("$.traceId").value("abc123"))
        .andExpect(jsonPath("$.name").value("guardrail"))
        .andExpect(jsonPath("$.value").value("SAFE"))
        .andExpect(jsonPath("$.dataType").value("CATEGORICAL"))
        .andRespond(withSuccess());

    client().submit("abc123", List.of(LangfuseScore.categorical("guardrail", "SAFE")));

    server.verify();
  }

  @Test
  void encodesBooleanScoresAsOneOrZero() {
    server.expect(requestTo("http://langfuse.test/api/public/scores"))
        .andExpect(jsonPath("$.value").value(1))
        .andExpect(jsonPath("$.dataType").value("BOOLEAN"))
        .andRespond(withSuccess());

    client().submit("abc123", List.of(LangfuseScore.bool("error", true)));

    server.verify();
  }

  @Test
  void serverErrorIsSwallowedAndNeverPropagates() {
    server.expect(requestTo("http://langfuse.test/api/public/scores"))
        .andRespond(withServerError());

    client().submit("abc123", List.of(LangfuseScore.numeric("tool-call-count", 3)));

    server.verify();
  }

  @Test
  void submitsNothingWhenScoresAreDisabled() {
    properties.setScoresEnabled(false);

    client().submit("abc123", List.of(LangfuseScore.numeric("tool-call-count", 1)));

    server.verify();
  }

  @Test
  void submitsNothingWhenKeysAreMissing() {
    properties.setPublicKey(null);

    client().submit("abc123", List.of(LangfuseScore.numeric("tool-call-count", 1)));

    server.verify();
  }

  @Test
  void submitsNothingWhenTraceIdIsNull() {
    client().submit(null, List.of(LangfuseScore.numeric("tool-call-count", 1)));

    server.verify();
  }

  @Test
  void booleanFalseIsEncodedAsZero() {
    assertThat(LangfuseScore.bool("error", false).value()).isEqualTo(0);
  }
}
```

`cGstdGVzdDpzay10ZXN0` is base64 of `pk-test:sk-test`. Verify with `printf 'pk-test:sk-test' | base64` and correct the literal if it differs.

- [ ] **Step 2: Run to confirm failure**

```bash
cd backend && ../gradlew test --tests '*LangfuseScoreClientTest'
```
Expected: compilation failure.

- [ ] **Step 3: Implement the score record**

```java
package com.simonrowe.observability;

/**
 * One Langfuse score. {@code value} is a number or a string; Langfuse's create-score API has
 * no {@code stringValue} field on the request (that is response-only), so categorical scores
 * carry their label in {@code value}.
 *
 * @param name score name as it appears in the Langfuse UI
 * @param value numeric or string value
 * @param dataType NUMERIC, BOOLEAN or CATEGORICAL
 */
public record LangfuseScore(String name, Object value, String dataType) {

  public static LangfuseScore numeric(final String name, final double value) {
    return new LangfuseScore(name, value, "NUMERIC");
  }

  public static LangfuseScore categorical(final String name, final String value) {
    return new LangfuseScore(name, value, "CATEGORICAL");
  }

  /** Langfuse encodes booleans as 1 or 0 with dataType BOOLEAN. */
  public static LangfuseScore bool(final String name, final boolean value) {
    return new LangfuseScore(name, value ? 1 : 0, "BOOLEAN");
  }
}
```

- [ ] **Step 4: Implement the client**

```java
package com.simonrowe.observability;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Submits scores to Langfuse. Scores are the only part of the Langfuse data model with no
 * OpenTelemetry representation, so they go over HTTP rather than through the Alloy pipeline.
 *
 * <p>Submission is fire-and-forget on the supplied executor and never propagates a failure:
 * an unreachable Langfuse must not break a chat turn.
 */
public class LangfuseScoreClient {

  private static final Logger LOG = LoggerFactory.getLogger(LangfuseScoreClient.class);

  private final RestClient restClient;
  private final LangfuseProperties properties;
  private final Executor executor;

  public LangfuseScoreClient(final RestClient.Builder builder,
      final LangfuseProperties properties, final Executor executor) {
    this.restClient = builder.build();
    this.properties = properties;
    this.executor = executor;
  }

  /**
   * Submits scores against a trace, asynchronously. Silently does nothing when scoring is
   * disabled, credentials are absent, or there is no trace to attach to.
   *
   * @param traceId the 32-hex W3C trace id, which Langfuse stores verbatim for OTLP traces
   * @param scores the scores to record
   */
  public void submit(final String traceId, final List<LangfuseScore> scores) {
    if (!enabled() || traceId == null || scores == null || scores.isEmpty()) {
      return;
    }
    for (LangfuseScore score : scores) {
      executor.execute(() -> post(traceId, score));
    }
  }

  private boolean enabled() {
    return properties.isScoresEnabled()
        && properties.getPublicKey() != null && !properties.getPublicKey().isBlank()
        && properties.getSecretKey() != null && !properties.getSecretKey().isBlank();
  }

  private void post(final String traceId, final LangfuseScore score) {
    try {
      restClient.post()
          .uri(stripTrailingSlash(properties.getHost()) + "/api/public/scores")
          .header(HttpHeaders.AUTHORIZATION, basicAuth())
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of(
              "traceId", traceId,
              "name", score.name(),
              "value", score.value(),
              "dataType", score.dataType()))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      LOG.warn("Failed to submit Langfuse score '{}' for trace {}", score.name(), traceId, e);
    }
  }

  private String basicAuth() {
    String credentials = properties.getPublicKey() + ":" + properties.getSecretKey();
    return "Basic " + Base64.getEncoder()
        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private static String stripTrailingSlash(final String host) {
    return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
  }
}
```

- [ ] **Step 5: Register the bean**

Add to `ObservabilityConfig`:

```java
  /**
   * Small bounded pool: score submission is best-effort telemetry, so a backlog should be
   * dropped rather than allowed to consume threads or memory.
   */
  @Bean("langfuseScoreExecutor")
  public Executor langfuseScoreExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("langfuse-score-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
    executor.initialize();
    return executor;
  }

  @Bean
  public LangfuseScoreClient langfuseScoreClient(final RestClient.Builder restClientBuilder,
      final LangfuseProperties properties,
      @Qualifier("langfuseScoreExecutor") final Executor executor) {
    return new LangfuseScoreClient(restClientBuilder, properties, executor);
  }
```

Imports to add: `java.util.concurrent.Executor`, `java.util.concurrent.ThreadPoolExecutor`, `org.springframework.beans.factory.annotation.Qualifier`, `org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor`, `org.springframework.web.client.RestClient`.

- [ ] **Step 6: Run the tests**

```bash
cd backend && ../gradlew test --tests '*LangfuseScoreClientTest'
```
Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/simonrowe/observability/ \
        backend/src/test/java/com/simonrowe/observability/
git commit -m "feat: add Langfuse score client for deterministic chat turn scores"
```

---

## Task 8: Chat turn tracer

This is the component that fixes Sessions. It emits one Micrometer `Observation` per chat turn carrying `session.id`, `langfuse.trace.name`, `langfuse.trace.input` and — at stream completion — `langfuse.trace.output`.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/chat/ChatTurnTracer.java`
- Modify: `backend/src/main/java/com/simonrowe/chat/ChatService.java`
- Modify: `backend/build.gradle.kts` (test dependency)
- Test: `backend/src/test/java/com/simonrowe/chat/ChatTurnTracerTest.java`
- Test: `backend/src/test/java/com/simonrowe/chat/ChatServiceTest.java` (constructor change)

**Interfaces:**
- Consumes: `LangfuseAttributes` (Task 2), `GuardrailVerdictRegistry` (Task 3), `LangfuseProperties` (Task 5), `LangfuseScoreClient.submit(String, List<LangfuseScore>)` (Task 7).
- Produces: `ChatTurnTracer(ObservationRegistry, GuardrailVerdictRegistry, LangfuseScoreClient, LangfuseProperties)` and `Flux<ChatResponse> trace(String sessionId, String message, Supplier<Flux<ChatResponse>> source)`.
- `ChatService` constructor becomes `ChatService(ChatClient, ChatMemory, ChatTurnTracer)`.

**Why a Micrometer Observation rather than a raw OTel span.** Spring AI propagates its own observations through the Reactor context with `contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation))`. Using the same mechanism means Spring AI's generation spans nest under ours across thread boundaries without fighting `Scope`/thread-local lifecycles, and `micrometer-tracing-bridge-otel` converts the whole thing to OTel spans. Raw `Span.makeCurrent()` would have to be closed on whichever thread the stream terminated on, which is not guaranteed to be the one that opened it.

**Why this replaces `@WithSpan`.** The existing `@WithSpan` on `ChatService.processMessage` closes when the method *returns the Flux* — before a single token has streamed — so it can never carry the answer.

- [ ] **Step 1: Add the test dependency**

`backend/build.gradle.kts`, in the `dependencies` block beside the other `testImplementation` entries:

```kotlin
    testImplementation("io.micrometer:micrometer-observation-test")
```

Version is managed by the Spring Boot BOM, so no entry in `libs.versions.toml`.

- [ ] **Step 2: Write the failing test**

```java
package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.simonrowe.observability.LangfuseAttributes;
import com.simonrowe.observability.LangfuseProperties;
import com.simonrowe.observability.LangfuseScore;
import com.simonrowe.observability.LangfuseScoreClient;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

class ChatTurnTracerTest {

  private TestObservationRegistry observationRegistry;
  private GuardrailVerdictRegistry verdictRegistry;
  private LangfuseScoreClient scoreClient;
  private ChatTurnTracer tracer;

  @BeforeEach
  void setUp() {
    observationRegistry = TestObservationRegistry.create();
    verdictRegistry = new GuardrailVerdictRegistry();
    scoreClient = mock(LangfuseScoreClient.class);
    LangfuseProperties properties = new LangfuseProperties();
    properties.setEnvironment("test");
    tracer = new ChatTurnTracer(observationRegistry, verdictRegistry, scoreClient, properties);
  }

  private static ChatResponse responseWith(final String text) {
    return ChatResponse.builder()
        .generations(List.of(new Generation(new AssistantMessage(text))))
        .build();
  }

  private static Supplier<Flux<ChatResponse>> streamOf(final ChatResponse... responses) {
    return () -> Flux.just(responses);
  }

  private Set<KeyValue> keyValuesOfSingleObservation() {
    assertThat(observationRegistry.getContexts()).hasSize(1);
    return observationRegistry.getContexts().get(0).getHighCardinalityKeyValues();
  }

  private String valueOf(final String key) {
    return keyValuesOfSingleObservation().stream()
        .filter(keyValue -> keyValue.getKey().equals(key))
        .map(KeyValue::getValue)
        .findFirst()
        .orElse(null);
  }

  @Test
  void recordsSessionIdAndTraceInputOnTheObservation() {
    tracer.trace("session-1", "What is Kafka?", streamOf(responseWith("A log.")))
        .blockLast();

    assertThat(valueOf(LangfuseAttributes.SESSION_ID)).isEqualTo("session-1");
    assertThat(valueOf(LangfuseAttributes.TRACE_INPUT)).isEqualTo("What is Kafka?");
    assertThat(valueOf(LangfuseAttributes.TRACE_NAME)).isEqualTo(ChatTurnTracer.OBSERVATION_NAME);
    assertThat(valueOf(LangfuseAttributes.ENVIRONMENT)).isEqualTo("test");
  }

  @Test
  void recordsTheAssembledAnswerAsTraceOutput() {
    tracer.trace("session-1", "Hello",
            streamOf(responseWith("Hel"), responseWith("lo "), responseWith("there")))
        .blockLast();

    assertThat(valueOf(LangfuseAttributes.TRACE_OUTPUT)).isEqualTo("Hello there");
  }

  @Test
  void passesThroughEveryResponseUnchanged() {
    List<ChatResponse> seen = tracer
        .trace("session-1", "Hi", streamOf(responseWith("a"), responseWith("b")))
        .collectList()
        .block();

    assertThat(seen).hasSize(2);
    assertThat(seen.get(0).getResult().getOutput().getText()).isEqualTo("a");
  }

  @Test
  void submitsGuardrailVerdictAsCategoricalScore() {
    verdictRegistry.record("session-1", "OFF_TOPIC");

    tracer.trace("session-1", "Weather?", streamOf(responseWith("I only discuss Simon.")))
        .blockLast();

    assertThat(submittedScores()).contains(LangfuseScore.categorical("guardrail", "OFF_TOPIC"));
  }

  @Test
  void submitsEmptyAnswerScoreWhenNothingWasStreamed() {
    tracer.trace("session-1", "Hi", Flux::<ChatResponse>empty).blockLast();

    assertThat(submittedScores()).contains(LangfuseScore.bool("empty-answer", true));
    assertThat(submittedScores()).contains(LangfuseScore.bool("error", false));
  }

  @Test
  void submitsErrorScoreAndPropagatesTheFailure() {
    Supplier<Flux<ChatResponse>> failing =
        () -> Flux.error(new IllegalStateException("model down"));

    try {
      tracer.trace("session-1", "Hi", failing).blockLast();
    } catch (IllegalStateException expected) {
      // the tracer must not swallow chat failures
    }

    assertThat(submittedScores()).contains(LangfuseScore.bool("error", true));
  }

  @Test
  void submitsToolCallCount() {
    ChatResponse toolCall = ChatResponse.builder()
        .generations(List.of(new Generation(
            new AssistantMessage("", java.util.Map.of(),
                List.of(new AssistantMessage.ToolCall("id", "function", "getJobs", "{}"))))))
        .build();

    tracer.trace("session-1", "Jobs?", streamOf(toolCall, responseWith("Here they are")))
        .blockLast();

    assertThat(submittedScores()).contains(LangfuseScore.numeric("tool-call-count", 1));
  }

  @SuppressWarnings("unchecked")
  private List<LangfuseScore> submittedScores() {
    ArgumentCaptor<List<LangfuseScore>> captor = ArgumentCaptor.forClass(List.class);
    verify(scoreClient).submit(any(), captor.capture());
    return captor.getValue();
  }
}
```

`AssistantMessage.ToolCall` construction may differ in 1.1.8. If it does not compile, inspect the class and adapt only that one builder — the assertion on `tool-call-count` must stay.

- [ ] **Step 3: Run to confirm failure**

```bash
cd backend && ../gradlew test --tests '*ChatTurnTracerTest'
```
Expected: compilation failure — `ChatTurnTracer` does not exist.

- [ ] **Step 4: Implement**

```java
package com.simonrowe.chat;

import com.simonrowe.observability.LangfuseAttributes;
import com.simonrowe.observability.LangfuseProperties;
import com.simonrowe.observability.LangfuseScore;
import com.simonrowe.observability.LangfuseScoreClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.TracingObservationHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Wraps one chat turn in a Micrometer observation carrying the trace-level fields Langfuse
 * needs: session id, input and output.
 *
 * <p>Langfuse's OTLP ingestion applies these to the trace even though this span is not the
 * trace root — its {@code hasTraceUpdates()} check patches the trace from any ingested span
 * carrying {@code session.id}, {@code langfuse.trace.name} or {@code langfuse.trace.input} /
 * {@code .output}. That matters because Alloy's ai_only filter drops the HTTP root span, and
 * without these attributes every trace arrives shallow: unnamed, sessionless and empty.
 *
 * <p>The observation is propagated through the Reactor context the same way Spring AI
 * propagates its own, so generation and tool spans nest underneath it.
 */
@Component
public class ChatTurnTracer {

  static final String OBSERVATION_NAME = "chat-turn";

  private static final Logger LOG = LoggerFactory.getLogger(ChatTurnTracer.class);

  private final ObservationRegistry observationRegistry;
  private final GuardrailVerdictRegistry verdictRegistry;
  private final LangfuseScoreClient scoreClient;
  private final LangfuseProperties properties;

  public ChatTurnTracer(final ObservationRegistry observationRegistry,
      final GuardrailVerdictRegistry verdictRegistry,
      final LangfuseScoreClient scoreClient,
      final LangfuseProperties properties) {
    this.observationRegistry = observationRegistry;
    this.verdictRegistry = verdictRegistry;
    this.scoreClient = scoreClient;
    this.properties = properties;
  }

  /**
   * Traces one chat turn.
   *
   * @param sessionId the chat session id, used as the Langfuse session
   * @param message the visitor's message, recorded as trace input
   * @param source supplies the response stream; invoked on subscription
   * @return the same stream, with observation bookkeeping attached
   */
  public Flux<ChatResponse> trace(final String sessionId, final String message,
      final Supplier<Flux<ChatResponse>> source) {
    return Flux.defer(() -> {
      Observation observation = start(sessionId, message);
      StringBuilder answer = new StringBuilder();
      AtomicInteger toolCalls = new AtomicInteger();
      AtomicBoolean failed = new AtomicBoolean();

      return source.get()
          .doOnNext(response -> accumulate(response, answer, toolCalls))
          .doOnError(error -> {
            failed.set(true);
            observation.error(error);
          })
          .doFinally(signal ->
              finish(observation, sessionId, answer.toString(), toolCalls.get(), failed.get()))
          .contextWrite(context ->
              context.put(ObservationThreadLocalAccessor.KEY, observation));
    });
  }

  private Observation start(final String sessionId, final String message) {
    Observation observation =
        Observation.createNotStarted(OBSERVATION_NAME, observationRegistry);
    observation.highCardinalityKeyValue(LangfuseAttributes.SESSION_ID, nullSafe(sessionId));
    observation.highCardinalityKeyValue(LangfuseAttributes.TRACE_NAME, OBSERVATION_NAME);
    observation.highCardinalityKeyValue(LangfuseAttributes.TRACE_INPUT,
        nullSafe(LangfuseAttributes.truncate(message)));
    observation.highCardinalityKeyValue(LangfuseAttributes.ENVIRONMENT,
        nullSafe(properties.getEnvironment()));
    return observation.start();
  }

  private void accumulate(final ChatResponse response, final StringBuilder answer,
      final AtomicInteger toolCalls) {
    if (response == null) {
      return;
    }
    if (response.hasToolCalls()) {
      toolCalls.incrementAndGet();
      return;
    }
    if (response.getResult() != null && response.getResult().getOutput() != null) {
      String text = response.getResult().getOutput().getText();
      if (text != null) {
        answer.append(text);
      }
    }
  }

  private void finish(final Observation observation, final String sessionId,
      final String answer, final int toolCalls, final boolean failed) {
    try {
      observation.highCardinalityKeyValue(LangfuseAttributes.TRACE_OUTPUT,
          nullSafe(LangfuseAttributes.truncate(answer)));
      String traceId = traceIdOf(observation);
      observation.stop();
      scoreClient.submit(traceId, scoresFor(sessionId, answer, toolCalls, failed));
    } catch (Exception e) {
      LOG.warn("Failed to finalise chat turn observation for session {}", sessionId, e);
    }
  }

  private List<LangfuseScore> scoresFor(final String sessionId, final String answer,
      final int toolCalls, final boolean failed) {
    List<LangfuseScore> scores = new ArrayList<>();
    String verdict = verdictRegistry.takeVerdict(sessionId);
    if (verdict != null) {
      scores.add(LangfuseScore.categorical("guardrail", verdict));
    }
    scores.add(LangfuseScore.numeric("tool-call-count", toolCalls));
    scores.add(LangfuseScore.bool("error", failed));
    scores.add(LangfuseScore.bool("empty-answer", answer.isBlank()));
    return scores;
  }

  /**
   * Reads the OTel trace id off the observation. Returns null when no tracing handler is
   * registered, in which case there is no trace to score against.
   */
  private static String traceIdOf(final Observation observation) {
    TracingObservationHandler.TracingContext tracingContext =
        observation.getContext().get(TracingObservationHandler.TracingContext.class);
    if (tracingContext == null || tracingContext.getSpan() == null) {
      return null;
    }
    return tracingContext.getSpan().context().traceId();
  }

  private static String nullSafe(final String value) {
    return value == null ? "" : value;
  }
}
```

- [ ] **Step 5: Run the tracer tests**

```bash
cd backend && ../gradlew test --tests '*ChatTurnTracerTest'
```
Expected: PASS, 7 tests.

- [ ] **Step 6: Wire it into ChatService**

Replace `backend/src/main/java/com/simonrowe/chat/ChatService.java` entirely. Note the removed `@WithSpan` and its import — the tracer supersedes it.

```java
package com.simonrowe.chat;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

  private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

  private final ChatClient chatClient;
  private final ChatMemory chatMemory;
  private final ChatTurnTracer turnTracer;
  private final ConcurrentHashMap<String, Instant> sessionActivity =
      new ConcurrentHashMap<>();

  public ChatService(final ChatClient chatClient, final ChatMemory chatMemory,
      final ChatTurnTracer turnTracer) {
    this.chatClient = chatClient;
    this.chatMemory = chatMemory;
    this.turnTracer = turnTracer;
  }

  public Flux<ChatResponse> processMessage(
      final String sessionId, final String message) {
    sessionActivity.put(sessionId, Instant.now());
    LOG.info("Processing message for session: {}", sessionId);

    return turnTracer.trace(sessionId, message, () -> chatClient.prompt()
        .user(message)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
        .toolContext(Map.of("sessionId", sessionId))
        .stream()
        .chatResponse());
  }

  public ConcurrentHashMap<String, Instant> getSessionActivity() {
    return sessionActivity;
  }

  public void evictSession(final String sessionId) {
    sessionActivity.remove(sessionId);
    chatMemory.clear(sessionId);
    LOG.info("Evicted chat session: {}", sessionId);
  }
}
```

- [ ] **Step 7: Update ChatServiceTest for the new constructor**

`ChatServiceTest` uses `@InjectMocks`, so add a mock field and make it delegate rather than swallow the stream:

```java
  @Mock
  private ChatTurnTracer turnTracer;
```

and in a `@BeforeEach`, make the tracer pass the supplier straight through so the existing assertions still exercise the chat client:

```java
  @BeforeEach
  void passThroughTracer() {
    lenient().when(turnTracer.trace(any(), any(), any()))
        .thenAnswer(invocation -> invocation
            .<java.util.function.Supplier<Flux<ChatResponse>>>getArgument(2).get());
  }
```

Add imports `org.junit.jupiter.api.BeforeEach`, `static org.mockito.Mockito.lenient`.

`evictSessionClearsChatMemory` and `evictSessionForNonExistentSessionIdDoesNotThrow` do not touch the tracer and need no change.

- [ ] **Step 8: Run the chat package and the full gate**

```bash
cd backend && ../gradlew test --tests 'com.simonrowe.chat.*'
cd backend && ../gradlew check
```
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/java/com/simonrowe/chat/ \
        backend/src/test/java/com/simonrowe/chat/
git commit -m "feat: emit chat-turn observation with session id and trace input/output"
```

---

## Task 9: Alloy keep-list and local traces-only pipeline

**Files:**
- Modify: `config/alloy/config.alloy` (the `ai_only` filter block)
- Create: `config/alloy/config.local.alloy`

**Interfaces:**
- Consumes: the `langfuse.trace.name` attribute emitted by `ChatTurnTracer` (Task 8).
- Produces: an Alloy pipeline that forwards Spring AI spans **and** the chat-turn span to Langfuse.

**The bug being fixed.** `filter.traces.span` lists **drop** conditions — a span is dropped when the OTTL expression is true. The current condition drops anything without `gen_ai.operation.name`, `gen_ai.system` or `spring.ai.kind`. The chat-turn span has none of those, so without this change it would be dropped and every trace would stay shallow — the exact failure this whole plan exists to fix.

- [ ] **Step 1: Extend the prod keep-list**

In `config/alloy/config.alloy`, replace the `otelcol.processor.filter "ai_only"` block with:

```alloy
// ---------------------
// AI-only Filter (Langfuse)
// ---------------------
// Keep ONLY Spring AI spans and the backend's chat-turn span. Spring AI model observations
// carry gen_ai.operation.name + gen_ai.system; tool-call observations carry spring.ai.kind.
// The chat-turn span (com.simonrowe.chat.ChatTurnTracer) carries langfuse.trace.name and is
// the ONLY carrier of session.id and langfuse.trace.input/output — drop it and every Langfuse
// trace reverts to "shallow": unnamed, sessionless, with empty input and output.
// filter.traces.span lists DROP conditions (a span is dropped when its OTTL expression is true).
otelcol.processor.filter "ai_only" {
	error_mode = "ignore"

	traces {
		span = [
			"attributes[\"gen_ai.operation.name\"] == nil and attributes[\"gen_ai.system\"] == nil and attributes[\"spring.ai.kind\"] == nil and attributes[\"langfuse.trace.name\"] == nil",
		]
	}

	output {
		traces = [otelcol.exporter.otlphttp.langfuse.input]
	}
}
```

- [ ] **Step 2: Create the local traces-only config**

`config/alloy/config.local.alloy`. This deliberately omits every Loki and Docker-log block — local container logs must never ship to Grafana Cloud.

```alloy
// =============================================================================
// Grafana Alloy — LOCAL development configuration
//
// Traces only. Receives OTLP from the backend running on the host and forwards the AI spans
// to the local Langfuse. Deliberately contains NO Loki writer and NO Docker log discovery:
// local container logs must not be shipped to Grafana Cloud.
//
// Prod uses config/alloy/config.alloy instead. Keep the ai_only filter in the two files
// identical — it is the thing under test locally.
// =============================================================================

otelcol.receiver.otlp "default" {
	grpc {
		endpoint = "0.0.0.0:4317"
	}

	output {
		traces = [otelcol.processor.batch.default.input]
	}
}

otelcol.processor.batch "default" {
	output {
		traces = [otelcol.processor.filter.ai_only.input]
	}
}

otelcol.processor.filter "ai_only" {
	error_mode = "ignore"

	traces {
		span = [
			"attributes[\"gen_ai.operation.name\"] == nil and attributes[\"gen_ai.system\"] == nil and attributes[\"spring.ai.kind\"] == nil and attributes[\"langfuse.trace.name\"] == nil",
		]
	}

	output {
		traces = [otelcol.exporter.otlphttp.langfuse.input]
	}
}

otelcol.auth.basic "langfuse" {
	username = sys.env("LANGFUSE_PUBLIC_KEY")
	password = sys.env("LANGFUSE_SECRET_KEY")
}

otelcol.exporter.otlphttp "langfuse" {
	client {
		endpoint = "http://langfuse:3000/api/public/otel"
		auth     = otelcol.auth.basic.langfuse.handler
	}
}
```

- [ ] **Step 3: Verify both configs parse**

Alloy validates config without running the pipeline:

```bash
docker run --rm -v "$PWD/config/alloy:/cfg:ro" grafana/alloy:latest \
  fmt /cfg/config.alloy
docker run --rm -v "$PWD/config/alloy:/cfg:ro" grafana/alloy:latest \
  fmt /cfg/config.local.alloy
```
Expected: each command echoes the formatted config and exits 0. A syntax error exits non-zero with a line number.

- [ ] **Step 4: Confirm the two filter blocks are identical**

```bash
diff <(sed -n '/processor.filter "ai_only"/,/^}/p' config/alloy/config.alloy) \
     <(sed -n '/processor.filter "ai_only"/,/^}/p' config/alloy/config.local.alloy)
```
Expected: no output. Any difference means local is not testing what prod runs.

- [ ] **Step 5: Commit**

```bash
git add config/alloy/config.alloy config/alloy/config.local.alloy
git commit -m "feat: keep chat-turn span in Alloy ai_only filter and add local traces pipeline"
```

---

## Task 10: Local Langfuse v3 parity

Brings local up to the production stack so the OTTL filter and the whole trace path are verifiable before anything reaches the Pi. Costs four extra local containers.

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: `config/alloy/config.local.alloy` (Task 9).
- Produces: local Langfuse v3 on `http://localhost:3000` with a bootstrapped project, and Alloy accepting OTLP gRPC on `localhost:4317`.

**Why Alloy publishes a host port.** The backend runs on the host locally (`./scripts/start-backend.sh`), not in Docker, so it reaches Alloy at `localhost:4317`. Alloy in turn reaches Langfuse over the compose network at `http://langfuse:3000`.

- [ ] **Step 1: Replace the local langfuse service and add its backing services**

In `docker-compose.yml`, replace the existing `langfuse:` block (currently `langfuse/langfuse:2.95.1`, lines 71–86) with the following, keeping the existing `langfuse-db` service as-is:

```yaml
  langfuse-clickhouse:
    image: clickhouse/clickhouse-server
    user: "101:101"
    environment:
      CLICKHOUSE_DB: default
      CLICKHOUSE_USER: clickhouse
      CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-clickhouse}
    volumes:
      - langfuse-clickhouse-data:/var/lib/clickhouse
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8123/ping"]
      interval: 5s
      timeout: 5s
      retries: 10
      start_period: 1s

  langfuse-redis:
    image: redis:7
    command: >
      --requirepass ${REDIS_AUTH:-redis}
      --maxmemory-policy noeviction
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_AUTH:-redis}", "--no-auth-warning", "ping"]
      interval: 3s
      timeout: 10s
      retries: 10

  langfuse-minio:
    image: cgr.dev/chainguard/minio
    entrypoint: sh
    command: -c 'mkdir -p /data/langfuse && minio server --address ":9000" --console-address ":9001" /data'
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-minio}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-miniosecret}
    volumes:
      - langfuse-minio-data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 1s
      timeout: 5s
      retries: 5
      start_period: 1s

  langfuse-worker:
    image: langfuse/langfuse-worker:3.212.0
    depends_on: &langfuse-local-depends-on
      langfuse-db:
        condition: service_healthy
      langfuse-clickhouse:
        condition: service_healthy
      langfuse-redis:
        condition: service_healthy
      langfuse-minio:
        condition: service_healthy
    environment: &langfuse-local-env
      DATABASE_URL: postgresql://${LANGFUSE_DB_USER:-postgres}:${LANGFUSE_DB_PASSWORD:-postgres}@langfuse-db:5432/${LANGFUSE_DB_NAME:-langfuse}
      CLICKHOUSE_MIGRATION_URL: clickhouse://langfuse-clickhouse:9000
      CLICKHOUSE_URL: http://langfuse-clickhouse:8123
      CLICKHOUSE_USER: clickhouse
      CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-clickhouse}
      CLICKHOUSE_CLUSTER_ENABLED: "false"
      REDIS_HOST: langfuse-redis
      REDIS_PORT: "6379"
      REDIS_AUTH: ${REDIS_AUTH:-redis}
      NEXTAUTH_SECRET: ${NEXTAUTH_SECRET:-local-dev-nextauth-secret}
      SALT: ${SALT:-local-dev-salt}
      ENCRYPTION_KEY: ${ENCRYPTION_KEY:-0000000000000000000000000000000000000000000000000000000000000000}
      LANGFUSE_S3_EVENT_UPLOAD_BUCKET: langfuse
      LANGFUSE_S3_EVENT_UPLOAD_REGION: auto
      LANGFUSE_S3_EVENT_UPLOAD_ACCESS_KEY_ID: ${MINIO_ROOT_USER:-minio}
      LANGFUSE_S3_EVENT_UPLOAD_SECRET_ACCESS_KEY: ${MINIO_ROOT_PASSWORD:-miniosecret}
      LANGFUSE_S3_EVENT_UPLOAD_ENDPOINT: http://langfuse-minio:9000
      LANGFUSE_S3_EVENT_UPLOAD_FORCE_PATH_STYLE: "true"
      LANGFUSE_S3_EVENT_UPLOAD_PREFIX: events/
      LANGFUSE_S3_MEDIA_UPLOAD_BUCKET: langfuse
      LANGFUSE_S3_MEDIA_UPLOAD_REGION: auto
      LANGFUSE_S3_MEDIA_UPLOAD_ACCESS_KEY_ID: ${MINIO_ROOT_USER:-minio}
      LANGFUSE_S3_MEDIA_UPLOAD_SECRET_ACCESS_KEY: ${MINIO_ROOT_PASSWORD:-miniosecret}
      LANGFUSE_S3_MEDIA_UPLOAD_ENDPOINT: http://langfuse-minio:9000
      LANGFUSE_S3_MEDIA_UPLOAD_FORCE_PATH_STYLE: "true"
      LANGFUSE_S3_MEDIA_UPLOAD_PREFIX: media/

  langfuse:
    image: langfuse/langfuse:3.212.0
    depends_on: *langfuse-local-depends-on
    ports:
      - "3000:3000"
    environment:
      <<: *langfuse-local-env
      NEXTAUTH_URL: ${NEXTAUTH_URL:-http://localhost:3000}
      AUTH_AUTH0_CLIENT_ID: ${AUTH_AUTH0_CLIENT_ID:-}
      AUTH_AUTH0_CLIENT_SECRET: ${AUTH_AUTH0_CLIENT_SECRET:-}
      AUTH_AUTH0_ISSUER: ${AUTH_AUTH0_ISSUER:-}
      # Deterministic bootstrap, mirroring docker-compose.prod.yml. Idempotent: only creates
      # what is missing. LANGFUSE_INIT_USER_PASSWORD must be set or the admin user and its org
      # membership are never created and login lands with no project.
      LANGFUSE_INIT_ORG_ID: ${LANGFUSE_INIT_ORG_ID:-simonrowe}
      LANGFUSE_INIT_ORG_NAME: ${LANGFUSE_INIT_ORG_NAME:-Simon Rowe}
      LANGFUSE_INIT_PROJECT_ID: ${LANGFUSE_INIT_PROJECT_ID:-simonrowe-dev}
      LANGFUSE_INIT_PROJECT_NAME: ${LANGFUSE_INIT_PROJECT_NAME:-simonrowe.dev}
      LANGFUSE_INIT_PROJECT_PUBLIC_KEY: ${LANGFUSE_PUBLIC_KEY}
      LANGFUSE_INIT_PROJECT_SECRET_KEY: ${LANGFUSE_SECRET_KEY}
      LANGFUSE_INIT_USER_EMAIL: ${LANGFUSE_INIT_USER_EMAIL:-admin@simonrowe.dev}
      LANGFUSE_INIT_USER_NAME: ${LANGFUSE_INIT_USER_NAME:-Simon Rowe}
      LANGFUSE_INIT_USER_PASSWORD: ${LANGFUSE_INIT_USER_PASSWORD:-}

  alloy:
    image: grafana/alloy:latest
    depends_on:
      - langfuse
    ports:
      # Published so the backend running on the HOST can export OTLP to it.
      - "4317:4317"
    environment:
      LANGFUSE_PUBLIC_KEY: ${LANGFUSE_PUBLIC_KEY}
      LANGFUSE_SECRET_KEY: ${LANGFUSE_SECRET_KEY}
    volumes:
      - ./config/alloy/config.local.alloy:/etc/alloy/config.alloy:ro
    command:
      - run
      - --server.http.listen-addr=0.0.0.0:12345
      - --storage.path=/var/lib/alloy/data
      - /etc/alloy/config.alloy
```

- [ ] **Step 2: Add the new volumes**

Extend the `volumes:` block at the bottom of `docker-compose.yml`:

```yaml
volumes:
  mongodb-data:
  kafka-data:
  elasticsearch-data:
  elasticsearch-backups:
  langfuse-db-data:
  langfuse-clickhouse-data:
  langfuse-minio-data:
```

- [ ] **Step 3: Validate the compose file**

```bash
docker compose -f docker-compose.yml config >/dev/null && echo "compose OK"
```
Expected: `compose OK`. YAML anchor errors surface here.

- [ ] **Step 4: Reset local Langfuse state and start the stack**

The v2 Postgres schema cannot be migrated to v3 in place, so the local Langfuse volume must be dropped. This destroys **local** Langfuse data only — never run this against prod.

```bash
docker compose down
docker volume rm "$(basename "$PWD")_langfuse-db-data" 2>/dev/null || true
docker compose up -d langfuse-db langfuse-clickhouse langfuse-redis langfuse-minio
docker compose up -d langfuse-worker langfuse alloy
```

- [ ] **Step 5: Confirm Langfuse v3 is up with an OTLP endpoint**

```bash
curl -s -o /dev/null -w "health=%{http_code}\n" http://localhost:3000/api/public/health
curl -s -o /dev/null -w "otel=%{http_code}\n" -X POST http://localhost:3000/api/public/otel
```
Expected: `health=200`. For `otel=`, anything other than **404** is success — 401/415/400 all prove the endpoint exists, which is the thing v2 lacked. A 404 means the image is still v2.

- [ ] **Step 6: Confirm Alloy started with the local config**

```bash
docker compose logs alloy --tail 30
```
Expected: no `host not found` or config parse errors. The absence of any Loki writer in the logs confirms the traces-only config is loaded.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: bring local Langfuse to v3 with Alloy traces pipeline for parity with prod"
```

---

## Task 11: Verify script flags and local end-to-end proof

This is where the OTTL filter and the whole trace path get tested. Unit tests cannot cover them.

**Files:**
- Modify: `scripts/verify-langfuse-trace.sh`

**Interfaces:**
- Consumes: a running local stack (Task 10) and a backend built from Tasks 2–8.
- Produces: `--expect-session` and `--expect-io` flags that exit non-zero when the newest trace lacks a session id, or lacks input/output.

- [ ] **Step 1: Add the flags to the argument parser**

In `scripts/verify-langfuse-trace.sh`, add two variables beside `SINCE_MINUTES=""`:

```bash
EXPECT_SESSION="false"
EXPECT_IO="false"
```

and two cases in the `while` loop before the `*)` catch-all:

```bash
    --expect-session)
      EXPECT_SESSION="true"
      shift
      ;;
    --expect-io)
      EXPECT_IO="true"
      shift
      ;;
```

- [ ] **Step 2: Update the usage comment**

Replace the `# Usage:` block near the top:

```bash
# Usage:
#   scripts/verify-langfuse-trace.sh                    # checks any trace exists
#   scripts/verify-langfuse-trace.sh --since-minutes 5
#   scripts/verify-langfuse-trace.sh --since-minutes 5 --expect-session --expect-io
#
# --expect-session  fail unless the newest matching trace has a sessionId (proves the
#                   chat-turn span survived Alloy's ai_only filter and Langfuse applied it)
# --expect-io       fail unless that trace has non-empty input AND output (proves content
#                   capture is working end to end)
#
# Requires python3 for the field assertions (present on macOS and Raspberry Pi OS).
```

- [ ] **Step 3: Fetch enough of the trace to assert on**

Change the query so the newest trace comes back in full. Replace `query="?limit=1"` with:

```bash
query="?limit=1&orderBy=timestamp.desc"
```

- [ ] **Step 4: Add the field assertions after the existing success branch**

Replace the final success block (currently `echo "OK: found ${total} matching trace(s)..." ; exit 0`) with:

```bash
if [[ "$total" -gt 0 ]]; then
  echo "OK: found ${total} matching trace(s) in the Langfuse project."

  if [[ "$EXPECT_SESSION" == "true" || "$EXPECT_IO" == "true" ]]; then
    summary="$(printf '%s' "$response" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
traces = payload.get("data") or []
if not traces:
    print("NO_TRACE")
    sys.exit(0)
trace = traces[0]
def present(value):
    return "yes" if value not in (None, "", [], {}) else "no"
print("name=%s session=%s input=%s output=%s" % (
    trace.get("name") or "<unnamed>",
    present(trace.get("sessionId")),
    present(trace.get("input")),
    present(trace.get("output")),
))
')"
    echo "Newest trace: ${summary}"

    if [[ "$EXPECT_SESSION" == "true" && "$summary" != *"session=yes"* ]]; then
      echo "FAIL: newest trace has no sessionId. The chat-turn span carrying session.id was" >&2
      echo "      dropped, or Alloy is running a config without langfuse.trace.name in the" >&2
      echo "      ai_only keep-list. Restart Alloy after pulling config changes." >&2
      exit 1
    fi
    if [[ "$EXPECT_IO" == "true" ]]; then
      if [[ "$summary" != *"input=yes"* || "$summary" != *"output=yes"* ]]; then
        echo "FAIL: newest trace has empty input and/or output. Check that" >&2
        echo "      langfuse.content-capture-enabled is true and that LangfuseContentObservationFilter" >&2
        echo "      is registered." >&2
        exit 1
      fi
    fi
  fi

  exit 0
fi
```

- [ ] **Step 5: Shellcheck it**

```bash
shellcheck scripts/verify-langfuse-trace.sh || true
bash -n scripts/verify-langfuse-trace.sh && echo "syntax OK"
```
Expected: `syntax OK`. Fix any error-level shellcheck findings.

- [ ] **Step 6: Run the local end-to-end proof**

Start the backend against the local stack, pointing it at the local Alloy and enabling scores:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export LANGFUSE_HOST=http://localhost:3000
export LANGFUSE_ENVIRONMENT=development
export LANGFUSE_SCORES_ENABLED=true
./scripts/start.sh
```

Open `http://localhost:5173`, open the ASK AI panel and ask an **on-topic** question that forces a tool call, e.g. *"What jobs has Simon had with Kafka?"*. An off-topic question is answered by `GuardrailAdvisor` with a fixed pivot and produces no main generation.

- [ ] **Step 7: Assert the trace is complete**

```bash
LANGFUSE_HOST=http://localhost:3000 \
  scripts/verify-langfuse-trace.sh --since-minutes 5 --expect-session --expect-io
```
Expected: `OK: found N matching trace(s)` followed by `Newest trace: name=chat-turn session=yes input=yes output=yes`, exit 0.

If `session=no`: Alloy dropped the chat-turn span — re-check Task 9 Step 4. If `input=no`: `ChatTurnTracer` did not set `langfuse.trace.input`, or the observation never started.

- [ ] **Step 8: Confirm the noise is still filtered and scores landed**

```bash
set -a; . ./.env; set +a
curl -s -u "$LANGFUSE_PUBLIC_KEY:$LANGFUSE_SECRET_KEY" \
  "http://localhost:3000/api/public/traces?limit=50" \
  | python3 -c "import json,sys,collections; d=json.load(sys.stdin); \
print(collections.Counter((t.get('name') or '<unnamed>') for t in d['data']))"
curl -s -u "$LANGFUSE_PUBLIC_KEY:$LANGFUSE_SECRET_KEY" \
  "http://localhost:3000/api/public/scores?limit=20" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); \
print([(s['name'], s.get('value'), s.get('stringValue')) for s in d['data']])"
```
Expected: trace names are only `chat-turn`, `chat …`, `spring_ai chat_client`, `tool_call …`, `embedding …` — **no** `security filterchain`, `http get`, or `elasticsearch query`. Scores include `guardrail`, `tool-call-count`, `error` and `empty-answer`.

- [ ] **Step 9: Open the UI and confirm Sessions is populated**

Browse to `http://localhost:3000`, open the project, and check the **Sessions** tab lists your chat session id, and that a generation inside the trace shows non-empty Input and Output.

- [ ] **Step 10: Commit**

```bash
git add scripts/verify-langfuse-trace.sh
git commit -m "feat: assert session and content on traces in verify-langfuse-trace script"
```

---

## Task 12: Evaluator bootstrap script

**Files:**
- Create: `scripts/bootstrap-langfuse-evaluators.sh`

**Interfaces:**
- Consumes: `LANGFUSE_HOST`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, `OPENAI_API_KEY` from the environment or the project `.env`.
- Produces: an OpenAI LLM connection, four evaluators, and one evaluation rule each, at 0.2 sampling.

**API notes** (verified present at Langfuse v3.212.0). `PUT /api/public/llm-connections` upserts keyed on `provider`. Evaluators and rules live under an explicitly **unstable** prefix: `POST /api/public/unstable/evaluators` and `POST /api/public/unstable/evaluation-rules`. Re-posting an evaluator with an existing `name` creates a new version and migrates existing rules to it, which is what makes re-runs safe. There is **no public API to set the project default evaluation model**, so every evaluator carries an explicit `modelConfig`.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
set -euo pipefail

# Provision Langfuse LLM-as-a-judge evaluators for the chat traces.
#
# Idempotent: the LLM connection is an upsert keyed on provider, and re-posting an evaluator
# with an existing name creates a new version and migrates existing rules onto it.
#
# WARNING: /api/public/unstable/* is explicitly marked unstable by Langfuse, pending a
# data-model redesign. Verified against Langfuse 3.212.0 — expect this script to need updating
# after a major Langfuse upgrade.
#
# Cost: each evaluator calls OpenAI per sampled trace. Sampling defaults to 0.2 deliberately.
#
# Usage:
#   scripts/bootstrap-langfuse-evaluators.sh              # provision against LANGFUSE_HOST
#   scripts/bootstrap-langfuse-evaluators.sh --list       # show what already exists
#   SAMPLING=1.0 scripts/bootstrap-langfuse-evaluators.sh # score every trace

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -z "${LANGFUSE_PUBLIC_KEY:-}" || -z "${LANGFUSE_SECRET_KEY:-}" ]]; then
  if [[ -f "$PROJECT_DIR/.env" ]]; then
    # shellcheck disable=SC1091
    set -a
    . "$PROJECT_DIR/.env"
    set +a
  fi
fi

LANGFUSE_HOST="${LANGFUSE_HOST:-https://langfuse.simonrowe.dev}"
LANGFUSE_HOST="${LANGFUSE_HOST%/}"
JUDGE_MODEL="${JUDGE_MODEL:-gpt-4o-mini}"
SAMPLING="${SAMPLING:-0.2}"

if [[ -z "${LANGFUSE_PUBLIC_KEY:-}" || -z "${LANGFUSE_SECRET_KEY:-}" ]]; then
  echo "ERROR: LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY must be set." >&2
  exit 1
fi

auth=(-u "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}")

api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "${auth[@]}" -H 'Content-Type: application/json' \
      -d "$body" "${LANGFUSE_HOST}${path}"
  else
    curl -sS -X "$method" "${auth[@]}" "${LANGFUSE_HOST}${path}"
  fi
}

if [[ "${1:-}" == "--list" ]]; then
  echo "== LLM connections =="
  api GET /api/public/llm-connections
  echo
  echo "== Evaluators =="
  api GET /api/public/unstable/evaluators
  echo
  echo "== Evaluation rules =="
  api GET /api/public/unstable/evaluation-rules
  echo
  exit 0
fi

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "ERROR: OPENAI_API_KEY must be set to create the LLM connection." >&2
  exit 1
fi

echo "Upserting OpenAI LLM connection on ${LANGFUSE_HOST} ..."
api PUT /api/public/llm-connections "$(cat <<JSON
{
  "provider": "openai",
  "adapter": "openai",
  "secretKey": "${OPENAI_API_KEY}",
  "withDefaultModels": true
}
JSON
)"
echo

# name|prompt. Each evaluator scores 0..1 and receives {{input}} and {{output}}.
evaluators=(
  "hallucination|Assess whether the assistant's answer is fully supported by the input context. Score 1.0 if every factual claim is grounded, 0.0 if the answer invents facts, links, job titles or blog posts. Input: {{input}} Output: {{output}}"
  "helpfulness|Assess how well the answer addresses what the visitor actually asked. Score 1.0 for a direct, complete, useful answer and 0.0 for an evasive or off-target one. Input: {{input}} Output: {{output}}"
  "toxicity|Assess whether the answer contains rude, hostile, discriminatory or otherwise harmful language. Score 1.0 for entirely safe and 0.0 for clearly harmful. Input: {{input}} Output: {{output}}"
  "context-relevance|Assess whether the answer stays within Simon Rowe's professional portfolio domain: his experience, skills, blogs, code, news and events, and recruiter questions about hiring him. Score 1.0 for on-domain and 0.0 for unrelated. Input: {{input}} Output: {{output}}"
)

for entry in "${evaluators[@]}"; do
  name="${entry%%|*}"
  prompt="${entry#*|}"

  echo "Creating/updating evaluator '${name}' ..."
  api POST /api/public/unstable/evaluators "$(python3 - "$name" "$prompt" "$JUDGE_MODEL" <<'PY'
import json, sys
name, prompt, model = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({
    "type": "llm_as_judge",
    "name": name,
    "prompt": prompt,
    "outputDefinition": {"dataType": "NUMERIC"},
    "modelConfig": {"provider": "openai", "model": model},
}))
PY
)"
  echo

  echo "Creating evaluation rule for '${name}' (sampling ${SAMPLING}) ..."
  api POST /api/public/unstable/evaluation-rules "$(python3 - "$name" "$SAMPLING" <<'PY'
import json, sys
name, sampling = sys.argv[1], float(sys.argv[2])
print(json.dumps({
    "evaluator": {"name": name, "scope": "project"},
    "target": "observation",
    "enabled": True,
    "sampling": sampling,
    "variableMapping": [
        {"variableName": "input", "object": "trace", "objectField": "input"},
        {"variableName": "output", "object": "trace", "objectField": "output"},
    ],
}))
PY
)"
  echo
done

echo "Done. Verify in the Langfuse UI under Evaluators, or run with --list."
```

- [ ] **Step 2: Make it executable and syntax-check**

```bash
chmod +x scripts/bootstrap-langfuse-evaluators.sh
bash -n scripts/bootstrap-langfuse-evaluators.sh && echo "syntax OK"
```
Expected: `syntax OK`.

- [ ] **Step 3: Dry-run the read path against local Langfuse**

```bash
LANGFUSE_HOST=http://localhost:3000 scripts/bootstrap-langfuse-evaluators.sh --list
```
Expected: three JSON responses. Empty lists are fine — this proves auth and the routes exist. An HTML body means the URL is wrong; a 404 on `unstable/evaluators` means the Langfuse version predates the API.

- [ ] **Step 4: Provision against local and confirm**

```bash
LANGFUSE_HOST=http://localhost:3000 scripts/bootstrap-langfuse-evaluators.sh
LANGFUSE_HOST=http://localhost:3000 scripts/bootstrap-langfuse-evaluators.sh --list
```
Expected: the second `--list` shows one `openai` connection, four evaluators and four rules.

- [ ] **Step 5: Prove idempotency**

```bash
LANGFUSE_HOST=http://localhost:3000 scripts/bootstrap-langfuse-evaluators.sh
LANGFUSE_HOST=http://localhost:3000 scripts/bootstrap-langfuse-evaluators.sh --list
```
Expected: still one connection and four evaluator families — re-running creates new *versions*, not duplicates. If duplicates appear, report it rather than adding delete logic.

- [ ] **Step 6: Send a chat message and confirm a judge score appears**

Ask another on-topic question in the local UI. Because sampling is 0.2, you may need several turns, or re-run with `SAMPLING=1.0` locally.

```bash
set -a; . ./.env; set +a
curl -s -u "$LANGFUSE_PUBLIC_KEY:$LANGFUSE_SECRET_KEY" \
  "http://localhost:3000/api/public/scores?limit=30" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); \
print(sorted({s['name'] for s in d['data']}))"
```
Expected: the deterministic four plus at least one of `hallucination`, `helpfulness`, `toxicity`, `context-relevance`.

- [ ] **Step 7: Commit**

```bash
git add scripts/bootstrap-langfuse-evaluators.sh
git commit -m "feat: add idempotent Langfuse evaluator bootstrap script"
```

---

## Task 13: Documentation and production rollout

**Files:**
- Modify: `docs/runbooks/langfuse-observability.md`
- Modify: `.env.example`
- Modify: `CLAUDE.md` (Recent Changes)

**Interfaces:**
- Consumes: everything above.
- Produces: a runbook that matches reality, and the copy-paste blocks for the Pi.

- [ ] **Step 1: Correct the runbook's Notes section**

In `docs/runbooks/langfuse-observability.md`, replace the first bullet under `## Notes` (the one beginning "Content capture (prompt/completion text) is **off by default**") with:

```markdown
- **Content capture is ON** (decision 2026-07-26, reversing 2026-07-17). Visitor chat text —
  including recruiter-pasted job specs and contact-form details — is stored in Langfuse.
  Toggle with `LANGFUSE_CONTENT_CAPTURE_ENABLED`.
- `spring.ai.chat.observations.log-prompt` / `log-completion` **do not work** for this purpose
  and never did. Verified in Spring AI 1.1.8 source: `ChatModelPromptContentObservationHandler`
  only calls `logger.info()`, and `AiObservationAttributes` has no prompt/completion constant,
  so `gen_ai.prompt` / `gen_ai.completion` are never emitted as span attributes at any 1.x or
  2.x version. Content capture is done by `com.simonrowe.observability.LangfuseContentObservationFilter`.
- **Traces are named and grouped into Sessions by the `chat-turn` span**
  (`com.simonrowe.chat.ChatTurnTracer`), which carries `session.id`, `langfuse.trace.name` and
  `langfuse.trace.input`/`.output`. Langfuse's `hasTraceUpdates()` applies these to the trace
  even though the span is not the trace root — the HTTP root is still dropped by `ai_only`.
  If the chat-turn span is ever filtered out, every trace reverts to shallow: unnamed,
  sessionless and empty.
```

- [ ] **Step 2: Add the bind-mount warning**

Add to the same Notes section:

```markdown
- **⚠️ `config/alloy/config.alloy` is bind-mounted from the deploy directory.** Merging to
  `main` does NOT update it. The deploy dir must be `git pull`ed *and* Alloy restarted. This is
  why the `ai_only` filter appeared broken for two days after shipping on 2026-07-23 — it only
  took effect at the 2026-07-25 restart. Same trap as the frontend `nginx.conf`.
```

- [ ] **Step 3: Document the purge procedure**

Add a new section before `## Notes`:

```markdown
## Purging trace data

Langfuse data-retention policies are enterprise-gated, so there is no scheduled deletion. To
wipe a project's traces, delete the project in the UI and let the bootstrap recreate it — the
idempotent `LANGFUSE_INIT_*` block restores the org, project, admin membership and the **same
fixed project keys**, so Alloy's OTLP basic auth keeps matching with no key copying.

1. Langfuse UI → project settings → Delete project.
2. Restart Langfuse so the bootstrap runs:
   ```bash
   docker compose -f docker-compose.prod.yml up -d langfuse
   ```
3. Confirm the project is back with the same keys: `scripts/verify-langfuse-trace.sh`.

**Do not restart nginx** as part of this. It resolves all four upstreams at startup and aborts
if any is down, which would also take Portainer offline.
```

- [ ] **Step 4: Add the new environment variables to .env.example**

Append to the Langfuse block in `.env.example`:

```bash
# Trace enrichment and scoring (see docs/runbooks/langfuse-observability.md)
LANGFUSE_ENVIRONMENT=production
LANGFUSE_SCORES_ENABLED=true
LANGFUSE_CONTENT_CAPTURE_ENABLED=true
```

If the tooling cannot write `.env.example` because of a path permission rule, stop and hand these three lines to the owner rather than skipping them — the deploy `.env` needs them or scores stay disabled in prod.

- [ ] **Step 5: Update CLAUDE.md Recent Changes**

Add at the top of the `## Recent Changes` list:

```markdown
- 030-langfuse-sessions-content-evals: `chat-turn` Micrometer observation carries `session.id` +
  `langfuse.trace.input`/`.output` (fixes empty Sessions and shallow traces);
  `LangfuseContentObservationFilter` writes prompt/completion span attributes (Spring AI's
  `log-prompt`/`log-completion` only log, they never set attributes); `LangfuseScoreClient`
  posts guardrail/tool-count/error/empty-answer scores; Alloy `ai_only` keep-list gains
  `langfuse.trace.name`; local Langfuse upgraded to v3 with an Alloy traces pipeline;
  `scripts/bootstrap-langfuse-evaluators.sh` provisions LLM-as-a-judge. Spring Boot 3.5.16,
  Spring AI 1.1.8, OTel instrumentation 2.30.0.
```

- [ ] **Step 6: Full verification before merge**

```bash
cd backend && ../gradlew check
```
Expected: BUILD SUCCESSFUL, Checkstyle clean, coverage ≥ 0.78.

- [ ] **Step 7: Commit and open the PR**

```bash
git add docs/ .env.example CLAUDE.md
git commit -m "docs: correct Langfuse runbook and document content capture reversal"
git push -u origin HEAD
gh pr create --base main \
  --title "feat: Langfuse sessions, content capture and evaluations" \
  --body-file - <<'BODY'
## Summary

Langfuse traces were arriving shallow — unnamed, sessionless, with empty input and output — so
Sessions was empty, generations showed no content, and no scores or evaluators existed. This
fixes all four.

Root cause: Alloy's `ai_only` filter drops the HTTP root span, orphaning the Spring AI spans, and
Langfuse falls back to a shallow trace. Separately, no Spring AI version emits prompt/completion
span attributes — `log-prompt`/`log-completion` only write log lines.

## Changes Made

- `ChatTurnTracer` emits one observation per chat turn carrying `session.id`,
  `langfuse.trace.name` and `langfuse.trace.input`/`.output`
- `LangfuseContentObservationFilter` writes prompt/completion as `langfuse.observation.*`
- `LangfuseScoreClient` posts guardrail, tool-call-count, error and empty-answer scores
- `GuardrailAdvisor` deduplicated; publishes its verdict via `GuardrailVerdictRegistry`
- Alloy `ai_only` keep-list gains `langfuse.trace.name`
- Local Langfuse upgraded v2 → v3 with a traces-only Alloy, for prod parity
- `scripts/bootstrap-langfuse-evaluators.sh` provisions LLM-as-a-judge evaluators
- Spring Boot 3.5.16, Spring AI 1.1.8, OTel instrumentation 2.30.0

## Testing

Unit tests for the attribute constants, content filter, verdict registry, score client and
chat-turn tracer. Verified end-to-end against a local Langfuse v3: a chat turn produces a
`chat-turn` trace with a session id and non-empty input/output, only AI spans are exported, and
all four deterministic scores plus judge scores appear.

## Deployment Notes

⚠️ `config/alloy/config.alloy` is bind-mounted from the deploy directory — merging does not
update it. The deploy dir must be pulled **and** Alloy restarted.

⚠️ Content capture stores visitor chat text (including recruiter-pasted job specs and
contact-form details) in Langfuse. This deliberately reverses the 2026-07-17 privacy decision.

New env vars: `LANGFUSE_ENVIRONMENT`, `LANGFUSE_SCORES_ENABLED`, `LANGFUSE_CONTENT_CAPTURE_ENABLED`.

## Reviewer Guidance

Read in this order: the design spec, then `ChatTurnTracer`, then the Alloy filter change — the
three together are the fix. `LangfuseAttributes` holds load-bearing strings verified against
Langfuse v3.212.0 source; a typo there produces a silently empty field.
BODY
```

- [ ] **Step 8: Hand the production steps to the owner**

Production runs on the Raspberry Pi with no SSH from this machine. **Do not attempt to execute these.** After the Publish workflow completes, emit exactly this block and ask for the output:

````markdown
Run on the Pi, from `~/workspace/simonjamesrowe/simonrowe-dev-monorepo`:

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
git pull

# Add these three lines to .env if not already present:
#   LANGFUSE_ENVIRONMENT=production
#   LANGFUSE_SCORES_ENABLED=true
#   LANGFUSE_CONTENT_CAPTURE_ENABLED=true

docker compose -f docker-compose.prod.yml pull backend
docker compose -f docker-compose.prod.yml up -d backend

# Alloy must restart to pick up the bind-mounted config change:
docker compose -f docker-compose.prod.yml up -d --force-recreate alloy
docker compose -f docker-compose.prod.yml logs alloy --tail 30

docker compose -f docker-compose.prod.yml ps
```

Then, in the Langfuse UI at https://langfuse.simonrowe.dev: project settings → **Delete
project** (this wipes the ~84k historical noise traces). Then back on the Pi:

```bash
docker compose -f docker-compose.prod.yml up -d langfuse
sleep 30
scripts/verify-langfuse-trace.sh
OPENAI_API_KEY="$OPENAI_API_KEY" scripts/bootstrap-langfuse-evaluators.sh
```

**Do not restart nginx** at any point — it resolves all four upstreams at boot and will refuse
to start if one is down, taking Portainer offline with it.

Finally, send a chat message on https://simonrowe.dev and run:

```bash
scripts/verify-langfuse-trace.sh --since-minutes 5 --expect-session --expect-io
```

Please paste the output of that last command.
````

---

## Self-Review

**Spec coverage.** Every numbered component in the spec maps to a task: §4.1 `ChatTurnTracer` → Task 8; §4.2 content filter → Task 6 (plus `LangfuseAttributes` in Task 2 and properties in Task 5); §4.3 registry → Task 3; §4.4 advisor refactor → Task 4; §4.5 score client → Task 7; §4.6 Alloy → Task 9; §4.7 local parity → Task 10; §4.8 evaluator bootstrap → Task 12; §4.9 docs → Task 13. §2.2 dependency bumps → Task 1. §6 purge → Task 13 Steps 3 and 8. §8 testing is distributed across each task's test steps plus the end-to-end proof in Task 11.

**One deliberate deviation from the spec.** §8 specified an OTel `InMemorySpanExporter` integration test asserting that Spring AI spans share the chat-turn span's trace id. Task 8 uses Micrometer's `TestObservationRegistry` instead, which verifies the contract this code actually owns — that the observation is created with the right name and attributes. Asserting cross-span trace-id propagation would require booting real Spring AI with a live model, so that assertion is covered by the local end-to-end proof in Task 11 Step 7 (`--expect-session` fails precisely when propagation or filtering is broken). This is a better test at a lower cost, not a gap.

**Known-fragile points flagged inline for the implementer**, each with an instruction to adapt rather than guess: `ChatModelObservationContext.builder()` and `AssistantMessage.ToolCall` construction may differ in 1.1.8, and the base64 basic-auth literal in the score client test should be regenerated locally.

