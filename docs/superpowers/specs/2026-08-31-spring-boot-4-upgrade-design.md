# Spring Boot 3.5 → 4.1 upgrade — design

Date: 2026-08-31
Status: approved, ready to implement

Upgrade both JVM modules (`:backend:` and `:software-factory:`) from Spring Boot
3.5.16 to **4.1.1**, and the Java toolchain from 21 to **25 LTS**, in a single
pull request.

Every version claim below was verified against Maven Central or a live
experiment on 2026-08-31. Where this contradicts the `spring-boot-upgrade`
skill's playbook (written 2026-08-21), this document is the later reading.

---

## 1. Why 4.1.1 and not 4.0.x

The `UpgradeSpringBoot_4_0` OpenRewrite recipe pins the 4.0 line, and the
playbook left 4.0-vs-4.1 as an open decision. The dependency graph settles it:

| Artifact | Declares |
| --- | --- |
| `embabel-agent-platform-autoconfigure:1.5.1` | `spring-boot-*:4.1.0`, `spring-ai-*:2.0.0` |
| `spring-ai-starter-model-openai:2.0.1` | `spring-boot-starter-restclient:4.1.1` |

Embabel 1.5.x is the **first** Embabel line that supports Boot 4 at all — 1.0.0
and everything below it compile against Boot 3.5.14. So there is no Boot 4.0
configuration of this repo in which Embabel and Spring AI are both on a
supported release. Landing on 4.0.x would mean sitting below both libraries we
depend on, for no benefit.

Spring Boot 4.1.1 is the current GA release (`4.0.8` GA, `4.1.1` GA and flagged
current, `4.2.0-M1` prerelease).

Consequence for tooling: **there is no `UpgradeSpringBoot_4_1` recipe.** The
procedure is to run `UpgradeSpringBoot_4_0`, which lands 4.0.x, and then bump
the `springBoot` catalogue entry to `4.1.1` by hand as a separate step. Do not
attempt to retarget the recipe.

## 2. Mongock is not a blocker — verified

This was the identified primary risk and the one thing that could have stopped
the project. Mongock's latest release is 5.5.1 (same as our pin), there is no
`mongock-springboot-v4` artifact, and `spring-jdk17-5.5.1.pom` declares:

```xml
<springframework-6.version>[6.0.0-RC2, 7.0.0)</springframework-6.version>
<spring-boot-3.version>[3.0.0-RC1, 4.0.0)</spring-boot-3.version>
```

Spring Framework 7 and Spring Boot 4 are deliberately outside those ranges, so
Mongock does not *claim* Boot 4 support. It nonetheless works.

**Experiment.** A throwaway Gradle project: Spring Boot 4.1.1,
`spring-boot-starter-data-mongodb`, `mongock-springboot-v3:5.5.1`,
`mongodb-springdata-v4-driver:5.5.1`, one `@ChangeUnit` performing an insert and
an `indexOps().createIndex()`, run against a `mongo:8` Testcontainer with
`@ServiceConnection`. Result:

```
Mongock runner COMMUNITY version[[]]
Mongock trying to acquire the lock
Mongock acquired the lock until: Mon Aug 31 19:23:10 BST 2026
APPLIED - {"id"="system-change-00001_before", ...}
APPLIED - {"id"="system-change-00001",        ...}
Mongock released the lock
BUILD SUCCESSFUL
```

Resolved stack: Spring Framework 7.0.9, spring-data-mongodb 5.x,
mongodb-driver-core 5.8.1. Change units executed, the distributed lock was
acquired and released, and `mongockChangeLog` was written.

**Why the declared bounds do not bite.** Mongock's Spring dependencies are
`provided` (`spring-context`) and `optional` (`spring-tx`) scope, so the version
ranges never reach a consumer's classpath. On top of that,
`io.spring.dependency-management` forces every version the Boot BOM manages,
which beats a range outright.

**This does not make Mongock supported.** It makes it *observed to work on the
exact stack we are shipping*. The verification gate below keeps it honest: a
green suite proves nothing, because `application-test.yml` sets
`mongock.enabled: false`. The Mongock-enabled test class must be run and read
specifically.

## 3. Java 25 LTS, not 26

Boot 4.1.1 supports Java 17 through 26, so Java 26 would work. It should still
not be chosen:

| Release | GA | Kind | End of support |
| --- | --- | --- | --- |
| Java 21 | Sep 2023 | LTS | 2031 |
| **Java 25** | **Sep 2025** | **LTS** | ~2030 |
| Java 26 | 17 Mar 2026 | short-term | **18 Sep 2026** |
| Java 27 | 15 Sep 2026 | short-term | Mar 2027 |
| Java 29 | Sep 2027 | LTS | — |

Java 26 reaches end of support 18 days from this document's date, and Java 27 is
not an LTS either — the next LTS is Java 29 in September 2027. Adopting 26 means
running unsupported almost immediately, on a Raspberry Pi that is patched by
hand. Java 25 is the current LTS and delivers the same generational move.

Two mechanical consequences:

- `settings.gradle.kts` has **no foojay toolchain resolver**, so Gradle cannot
  auto-provision a JDK. Today the build works because `/usr/bin/java` is 21.
  Moving the toolchain to 25 without a resolver breaks any machine that has no
  JDK 25 installed. Add
  `org.gradle.toolchains.foojay-resolver-convention` so the toolchain
  self-provisions.
- Four `java-version: '21'` entries across `.github/workflows/ci.yml` (×3),
  `publish.yml` (×2) and `evals.yml` (×1) must move to `'25'`.

The backend image is built by `bootBuildImage` against
`paketobuildpacks/run-noble-base:latest`; the buildpack picks its JRE from the
compiled bytecode level, so no explicit `BP_JVM_VERSION` is expected to be
needed. Verify rather than assume — a wrong JRE here surfaces only in
production.

## 4. Scope of the change

### 4.1 Handled by the OpenRewrite recipe

`org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` from
`org.openrewrite.recipe:rewrite-spring:6.37.1` (the current latest, and served
by Maven Central, so no Code Genome credential is required). Its
`UpgradeSpringFramework_7_0` link transitively performs:

- **Jackson 2 → 3.** `com.fasterxml.jackson` → `tools.jackson` across ~100
  import sites in both modules; `IOException` → `JacksonException` in serde
  overrides; `ObjectMapper` setter chains → builder form. The three
  annotation-only imports (`JsonProperty`, `JsonInclude`, `JsonIgnoreProperties`,
  plus `JsonValue`/`JsonCreator`) **must not move** — `jackson-annotations`
  keeps the `com.fasterxml.jackson.core` groupId. Verify this rather than
  assuming it.
- **JUnit 5 → 6** across 153 backend test files. Boot 4.1.1 manages
  `junit-jupiter` 6.0.3.
- **Spring Kafka 3 → 4.** Boot 4.1.1 manages `spring-kafka` 4.1.1.
- **JSpecify** nullability annotations replacing Spring's own.
- **Modular starters**: `spring-boot-starter-web` → `-webmvc`,
  `spring-boot-starter-oauth2-resource-server` →
  `spring-boot-starter-security-oauth2-resource-server`,
  `spring-kafka` → `spring-boot-starter-kafka`, `spring-security-test` →
  `spring-boot-starter-security-test`.
- **Gradle wrapper** to ≥8.14.

### 4.2 Manual — dependency and build

| Item | From | To | Note |
| --- | --- | --- | --- |
| `springBoot` | 3.5.16 | **4.1.1** | hand-bump after the recipe lands 4.0.x |
| Java toolchain | 21 | **25** | plus foojay resolver, plus 6 CI entries |
| Gradle wrapper | 8.13 | **9.x** | recipe only guarantees ≥8.14; 9.7.1 verified working with Boot 4.1.1 locally |
| `cyclonedx` plugin | 2.1.0 | **3.0.0** | no recipe covers it; Boot 4 raises the floor. Feeds the Dependency-Track SBOM. |
| `testcontainers` | 1.20.4 | delete pin | Boot 4.1.1 BOM manages **2.0.5** |
| `springAi` | 1.1.8 | **2.0.1** | see 4.3 |
| `embabel` | 1.0.0 | **1.5.1** | first Boot 4 line |
| `repo.embabel.com` repository | present | **remove** | Embabel publishes to Maven Central |

**Testcontainers 2.x renames every artifact** — `org.testcontainers:mongodb` →
`org.testcontainers:testcontainers-mongodb`, and likewise for `junit-jupiter`,
`kafka` and `elasticsearch`. Four catalogue entries. This is not in the
playbook; it was found by running the spike, and it fails as an unresolvable
dependency rather than a compile error.

The six `ext[...]` managed-version overrides in the root `build.gradle.kts`
(opentelemetry, jackson-bom, commons-lang3, httpclient5, httpcore5, log4j2) exist
to clear Dependency-Track findings and **must each be re-checked against the Boot
4.1.1 BOM**. An override that is now *below* what Boot manages silently
downgrades a dependency. Note in particular that Boot 4.1.1 splits Jackson into
`jackson-bom.version` (3.1.5) and `jackson-2-bom.version` (2.21.5) — our
`ext["jackson-bom.version"] = "2.21.5"` would pin Jackson **3** to a version that
does not exist.

### 4.3 Manual — Spring AI 1.1.8 → 2.0.1

The largest hand-written part, and the part the recipe does not touch at all.

**Artifact changes:**

| Declared today | Action |
| --- | --- |
| `spring-ai-starter-model-openai` | bump to 2.0.1 |
| `spring-ai-starter-mcp-server-webmvc` | bump to 2.0.1 |
| `spring-ai-starter-vector-store-elasticsearch` | bump to 2.0.1 |
| `spring-ai-advisors-vector-store` | **renamed** → `spring-ai-vector-store-advisor` (2.0.0-M8 is the last release under the old name) |
| `spring-ai-starter-model-openai-sdk` | **deleted** — merged into `spring-ai-starter-model-openai` at 2.0.0-M5; its 2.0 line stopped at M4 |

The openai-sdk merge is the load-bearing one. Spring AI 2.0 uses the official
`openai-java` SDK for *all* OpenAI models in the single `spring-ai-openai`
module. Consumers drop the separate starter and remove the `Sdk` suffix from
class names. Our `application.yml` and `application-test.yml` both carry a
`spring.ai.model.openai-sdk` key and both exclude
`org.springframework.ai.model.openaisdk.autoconfigure.OpenAiSdk{Embedding,Image}AutoConfiguration`
by class name — those five config sites break silently (an exclusion naming an
absent class is not an error) and must be rewritten.

**API changes that hit our code.** The backend imports 47 distinct Spring AI
types. The ones that move:

- `internalToolExecutionEnabled` is **removed**; internal tool-execution loops
  are gone from every `ChatModel` and routed through an auto-registered
  `ToolCallingAdvisor` (renamed from `ToolCallAdvisor`). This is the premise
  `CountingToolCallingManager` is built on — its javadoc explains that it counts
  in `ToolCallingManager.executeToolCalls` precisely because the aggregated
  tool-call `ChatResponse` never reaches `ChatService`. That premise has to be
  re-established against 2.0's advisor-based flow, not assumed.
- Options are strictly immutable: `.copy()` → `.mutate()`,
  `.getDefaultOptions()` → `.getOptions()`, and `ChatClient.prompt().options()`
  now takes a *builder* rather than a built instance.
- `ChatMemory.DEFAULT_CONVERSATION_ID` removed; conversation id is required per
  call via advisor context. Affects `ToolFilteringChatMemory` and
  `MessageChatMemoryAdvisor` usage.
- Model config properties flatten: `spring.ai.openai.chat.options.temperature`
  → `spring.ai.openai.chat.temperature`. This interacts with a known trap
  recorded in memory — yml chat defaults leaking into every per-call
  `OpenAiChatOptions` — so re-verify chat and the guardrail after the move.
- `ModelOptionsUtils` JSON helpers → `JsonHelper`.
- MCP: `McpSyncClientCustomizer`/`McpAsyncClientCustomizer` unified into
  `McpClientCustomizer`; MCP annotations moved from `org.springaicommunity` to
  `org.springframework.ai` (we import neither today, so likely a no-op —
  confirm).

Affected first-party files, at minimum: `CountingToolCallingManager`,
`GuardrailAdvisor`, `ContextAwareQuestionAnswerAdvisor`, `ToolFilteringChatMemory`,
`ChatConfig`, `ChatService`, `ChatTurnTracer`, and the OTel/Langfuse observation
wiring that reads `ChatModelObservationContext` and
`ToolCallingObservationContext`.

### 4.4 Manual — Embabel 1.0.0 → 1.5.1

Eight backend main-source files and eleven test files touch `com.embabel`.
Embabel's own Boot 4 / Spring AI 2.0 migration notes flag, for consumers:
`.defaultOptions(...)` → `.options(...)`, `.retryTemplate(...)` removed,
nullable `Generation.getResult()` / `Message.getText()`, and
`StructuredOutputConverter` needing explicit `Class<Any>` at construction.
`AbstractIntegrationTest`'s mock of Embabel's `Ai` is the most likely test-side
break.

### 4.5 Manual — `software-factory` and the Temporal risk

`io.temporal:temporal-spring-boot-autoconfigure` up to the latest release
(1.38.0) references
`org.springframework.boot.context.properties.ConstructorBinding`, which **does
not exist in Boot 4** — it moved to
`org.springframework.boot.context.properties.bind.ConstructorBinding`. It is an
annotation, so an absent class is skipped at class-load rather than being fatal,
but constructor binding of Temporal's own `@ConfigurationProperties` may behave
differently. This is the residual unknown of the whole project and it is not
settleable by reading POMs.

**Operational hazard, accepted deliberately.** `software-factory` hosts the
`Code Review` check run that `.github/rulesets/main.json` requires on every PR
to `main`. A single PR that breaks this module takes the merge gate down with
it. Repository admins bypass every rule (`actor_id: 5`, `bypass_mode: always`),
so this is recoverable, but the bypass is an escape hatch and every use lands in
rule insights. Mitigation: `:software-factory:test` must be green locally before
the PR is opened, not after.

## 5. Verification gates

In order. Nothing is claimed to work before its output has been read.

1. `./gradlew :backend:checkstyleMain :backend:checkstyleTest`
   `:software-factory:checkstyleMain :software-factory:checkstyleTest`
2. `./gradlew :software-factory:test` — **before** the backend, because it gates
   merging.
3. `./gradlew :backend:test`
4. `./gradlew :backend:jacocoTestCoverageVerification` — the 0.78 floor.
5. **The Mongock-enabled test, named explicitly.** The suite sets
   `mongock.enabled: false`, so step 3 passing says nothing about migrations.
   Run `V011SeedAndBackfillDanVegaBlogIntegrationTest`
   (`@TestPropertySource(properties = "mongock.enabled=true")`) and read it.
6. **A runtime smoke test** via the `local-env` skill. A Boot major upgrade
   breaks at context startup in ways that compile and unit-test perfectly:
   missing autoconfiguration, a removed bean, a renamed property. The Spring AI
   property flattening in 4.3 makes this mandatory, not optional.
7. **A chat end-to-end pass** via `chat-e2e-verify`. The Spring AI tool-calling
   rework changes the path every chat turn takes; unit tests mock it.
8. `./gradlew cyclonedxBom` produces a non-empty SBOM — the Dependency-Track
   pipeline reads it, and an empty BOM uploads fine and reads as clean.
9. Spring-specific validation: `getProjectDiagnostics` per project from the
   spring-tools MCP server, after `refreshWorkspace`.

## 6. Rollback

The branch is the rollback. The working tree must be clean before the recipe
runs — `git checkout -- .` cannot separate our edits from the recipe's, so a
dirty tree means a bad run destroys both.

The OpenRewrite Gradle plugin block is temporary scaffolding and must not reach
a commit. It goes in the **root** `build.gradle.kts`, which the recipe also
edits (it bumps `org.springframework.boot`), so `git checkout -- build.gradle.kts`
would discard recipe output alongside the scaffolding. Remove the scaffolding by
hand, or `git add -p` the recipe's hunks first.

## 7. Out of scope

- **Flamingock.** Mongock's successor is a rewrite with a different API, its
  MongoDB module is `0.0.38-beta`, and section 2 shows Mongock works. Migrating
  is a project of its own.
- **The frontend.** Not on the JVM LST; untouched.
- **Java 26/27.** Section 3.
- **Spring Boot 4.2.** Prerelease only.
