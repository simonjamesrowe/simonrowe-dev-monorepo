# Spring Boot 4.1 Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move both JVM modules (`:backend:`, `:software-factory:`) from Spring Boot 3.5.16 to 4.1.1 and the Java toolchain from 21 to 25 LTS, in one pull request, with the chat stack, Mongock migrations and the Dependency-Track SBOM all verified working afterwards.

**Architecture:** Land the mechanical rewrites with the OpenRewrite `UpgradeSpringBoot_4_0` recipe (Jackson 2→3, JUnit 5→6, Spring Kafka 3→4, modular starters, JSpecify), then hand-bump to 4.1.1 and hand-migrate the three things no recipe covers: Spring AI 1.1.8→2.0.1, Embabel 1.0.0→1.5.1, and the Testcontainers 2.x artifact renames. Build-tool floors (Gradle 9, CycloneDX 3, Java 25) go first as separate commits on Boot 3.5 so a failure there is unambiguous.

**Tech Stack:** Java 25 (Temurin), Gradle 9.x, Spring Boot 4.1.1, Spring Framework 7.0.9, Spring AI 2.0.1, Embabel 1.5.1, Mongock 5.5.1, Jackson 3.1.5, JUnit 6.0.3, Testcontainers 2.0.5, spring-kafka 4.1.1, spring-data-mongodb 5.x.

## Global Constraints

- **Target Spring Boot is `4.1.1`, not `4.0.x`.** The recipe lands 4.0.x; bumping to 4.1.1 by hand afterwards is a required step, not optional polish. There is no `UpgradeSpringBoot_4_1` recipe — do not try to retarget the recipe.
- **Java toolchain is `25` (LTS).** Not 26 (end of support 18 Sep 2026), not 27 (also non-LTS).
- **Gradle wrapper must end at 9.x.** Boot 4's floor is 8.14; we go to 9.x deliberately.
- **The working tree must be clean before Task 3 (the recipe run).** `git checkout -- .` cannot separate our edits from the recipe's.
- **The OpenRewrite plugin block is scaffolding and must never reach a commit.** It lives in the root `build.gradle.kts`, which the recipe also edits, so never `git checkout -- build.gradle.kts` wholesale.
- **`:software-factory:test` must be green before the PR is opened.** That module hosts the `Code Review` check run required by `.github/rulesets/main.json` on every PR to `main`. Breaking it takes the merge gate down; repo-admin bypass is the only recovery.
- **A green `:backend:test` proves nothing about Mongock.** `backend/src/test/resources/application-test.yml` sets `mongock.enabled: false`. The Mongock-enabled test class must be run by name and its output read.
- **Never run `.specify/scripts/bash/update-agent-context.sh`** against `CLAUDE.md` — it fails with `grep: repetition-operator operand invalid` and silently strips lead lines from existing entries.
- Conventional commits, no Jira ticket, no Claude attribution.

---

### Task 1: Build-tool floors on Boot 3.5

Raise Gradle, CycloneDX, SonarQube and add the toolchain resolver — all while still on Boot 3.5.16 and Java 21. If CI breaks here, the cause is unambiguous.

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml` (`cyclonedx`, `sonarqube`)

**Interfaces:**
- Consumes: nothing.
- Produces: a Gradle 9.x wrapper and a `foojay-resolver-convention` plugin in `settings.gradle.kts`, which Task 2 relies on to provision JDK 25 without a manual install.

- [ ] **Step 1: Record the baseline so a regression is recognisable**

```bash
./gradlew :backend:test :software-factory:test --console=plain 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. Write down the test counts. If this is already failing, stop — fix that first; it is not part of this upgrade.

- [ ] **Step 2: Bump the Gradle wrapper to 9.x**

```bash
./gradlew wrapper --gradle-version 9.7.1 --distribution-type bin
./gradlew wrapper --gradle-version 9.7.1 --distribution-type bin
```

Run it **twice** — the first invocation rewrites `gradle-wrapper.properties` using the old wrapper jar; the second regenerates the jar and scripts with the new version. Then confirm:

```bash
./gradlew --version | head -6
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```

Expected: `Gradle 9.7.1` and `distributionUrl=...gradle-9.7.1-bin.zip`.

- [ ] **Step 3: Add the foojay toolchain resolver**

Gradle cannot auto-provision a JDK without it. Today the build only works because `/usr/bin/java` happens to be 21; Task 2 moves the toolchain to 25, which is not installed on this machine.

Add to the **top** of `settings.gradle.kts`, above `rootProject.name` (a `plugins` block must be the first statement in a settings file):

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "simonrowe-dev-monorepo"

include("backend")
include("software-factory")
```

- [ ] **Step 4: Bump CycloneDX and SonarQube in the version catalogue**

Boot 4 raises the CycloneDX floor to 3.0.0. SonarQube 6.0.1 predates Gradle 9 and is bumped in the same commit so any Gradle 9 incompatibility surfaces here rather than mixed into the Boot diff.

In `gradle/libs.versions.toml`:

```toml
cyclonedx = "3.4.1"
sonarqube = "7.4.0.8496"
```

- [ ] **Step 5: Verify the SBOM task still produces a non-empty BOM**

CycloneDX 3.x changed task configuration. The SBOM feeds Dependency-Track, and an empty BOM uploads fine and reads as "clean" — so assert content, not exit code.

```bash
./gradlew cyclonedxBom --console=plain
find build -name '*.json' -path '*cyclonedx*' -o -name 'bom.json' | head
```

Then count components in whichever path it wrote:

```bash
python3 -c "
import json,glob,sys
p=[f for f in glob.glob('build/**/bom.json',recursive=True)+glob.glob('build/reports/**/*.json',recursive=True)]
print(p)
for f in p:
    d=json.load(open(f))
    print(f, 'components:', len(d.get('components',[])))
"
```

Expected: at least one BOM with **hundreds** of components. If `setIncludeConfigs(listOf("runtimeClasspath"))` no longer compiles or is renamed in 3.x, fix it and keep the semantics — the root `build.gradle.kts` comment explains why the scope is restricted (build- and test-time deps were being reported as production CVEs in SIM-9).

- [ ] **Step 6: Run the full gate**

```bash
./gradlew :backend:checkstyleMain :backend:checkstyleTest \
          :software-factory:checkstyleMain :software-factory:checkstyleTest \
          :backend:test :software-factory:test --console=plain 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, test counts matching Step 1.

- [ ] **Step 7: Commit**

```bash
git add gradle/wrapper settings.gradle.kts gradle/libs.versions.toml gradlew gradlew.bat
git commit -m "chore: raise Gradle to 9.7.1 and the CycloneDX and Sonar plugins

Spring Boot 4 requires Gradle 8.14+; going to 9.x and bumping the two plugins
that predate it separately, while still on Boot 3.5, so a build-tool failure is
distinguishable from a framework failure. Adds the foojay toolchain resolver so
the Java 25 move in the next commit does not require a hand-installed JDK."
```

---

### Task 2: Java toolchain 21 → 25

**Files:**
- Modify: `build.gradle.kts:~120` (the `subprojects { java { toolchain { ... } } }` block)
- Modify: `.github/workflows/ci.yml:40,133,203`
- Modify: `.github/workflows/publish.yml:27,159`
- Modify: `.github/workflows/evals.yml:88`

**Interfaces:**
- Consumes: the foojay resolver from Task 1.
- Produces: a Java 25 bytecode target, which Task 4 relies on when Boot 4.1.1's BOM is applied.

- [ ] **Step 1: Change the toolchain**

In the root `build.gradle.kts`, inside `subprojects { ... }`:

```kotlin
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }
```

- [ ] **Step 2: Verify Gradle provisions JDK 25 and the code compiles**

```bash
./gradlew :backend:compileJava :software-factory:compileJava --console=plain 2>&1 | tail -20
./gradlew -q javaToolchains | grep -A3 -i '25'
```

Expected: BUILD SUCCESSFUL, and a JDK 25 listed (auto-provisioned into `~/.gradle/jdks` if it was not already present). If provisioning is blocked by the network, install Temurin 25 by hand (`brew install --cask temurin@25`) and re-run.

- [ ] **Step 3: Bump every CI Java version**

All six sites. Verify none are missed:

```bash
sed -i '' "s/java-version: '21'/java-version: '25'/" \
  .github/workflows/ci.yml .github/workflows/publish.yml .github/workflows/evals.yml
grep -rn "java-version:" .github/workflows/
```

Expected: every line reads `java-version: '25'`, and zero remaining `'21'`.

- [ ] **Step 4: Run the full gate**

```bash
./gradlew :backend:checkstyleMain :backend:checkstyleTest \
          :software-factory:checkstyleMain :software-factory:checkstyleTest \
          :backend:test :software-factory:test --console=plain 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL. Checkstyle is the likely failure here — the Google checks config may flag nothing new, but a JDK bump can change what the parser accepts. Fix violations rather than raising `maxWarnings`.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts .github/workflows/
git commit -m "chore: move the Java toolchain to 25 LTS

Java 21 to 25, still on Boot 3.5. Deliberately not Java 26: it is a short-term
release that reaches end of support on 18 September 2026, and Java 27 is not an
LTS either — the next LTS is Java 29 in September 2027."
```

---

### Task 3: Run the OpenRewrite Boot 4 recipe

The mechanical bulk: Jackson 2→3, JUnit 5→6, Spring Kafka 3→4, modular starters, JSpecify. This lands Boot **4.0.x**; Task 4 takes it to 4.1.1.

**Files:**
- Temporarily modify: `build.gradle.kts` (scaffolding — must not be committed)
- Modified by the recipe: `gradle/libs.versions.toml`, `backend/build.gradle.kts`, `software-factory/build.gradle.kts`, `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, and ~200 Java sources

**Interfaces:**
- Consumes: Gradle 9 and Java 25 from Tasks 1–2.
- Produces: Boot 4.0.x in `gradle/libs.versions.toml`, `tools.jackson.*` imports throughout, JUnit 6 test sources, modular starter names in both module build files.

- [ ] **Step 1: Confirm the tree is clean**

```bash
git status --porcelain
```

Expected: **no output**. This is the rollback plan, not hygiene. If anything is listed, commit or `git stash -u` it first.

- [ ] **Step 2: Add the recipe scaffolding to the root build file**

Add to the top of the root `build.gradle.kts`, inside the existing `plugins` block, and append the rest at the end of the file:

```kotlin
plugins {
    java
    id("org.springframework.boot") version libs.versions.springBoot.get() apply false
    id("io.spring.dependency-management") version libs.versions.springDependencyManagement.get() apply false
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.sonarqube)
    id("org.openrewrite.rewrite") version "7.41.0"   // SCAFFOLDING — do not commit
}
```

At the end of the file:

```kotlin
// SCAFFOLDING — do not commit
dependencies {
    rewrite("org.openrewrite.recipe:rewrite-spring:6.37.1")
}
rewrite {
    activeRecipe("org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0")
}
```

`mavenCentral()` already serves `rewrite-spring:6.37.1` — no Code Genome credential is needed.

- [ ] **Step 3: Dry-run first and read the patch summary**

```bash
./gradlew rewriteDryRun --console=plain 2>&1 | tail -30
wc -l build/reports/rewrite/rewrite.patch
grep -c '^diff --git' build/reports/rewrite/rewrite.patch
grep '^diff --git' build/reports/rewrite/rewrite.patch | sed 's|.*/||' | sort | uniq -c | sort -rn | head -20
```

Expected: a patch touching on the order of 200 files. If it touches fewer than 50, the recipe under-applied — investigate before running for real.

- [ ] **Step 4: Apply the recipe**

```bash
./gradlew rewriteRun --console=plain 2>&1 | tail -40
```

- [ ] **Step 5: Remove the scaffolding without destroying recipe output**

The recipe also edits the root `build.gradle.kts` (it bumps the `org.springframework.boot` plugin version), so a wholesale revert of that file throws away real work.

```bash
git diff -- build.gradle.kts
```

Delete **by hand** the three scaffolding pieces: the `id("org.openrewrite.rewrite")` line, the `dependencies { rewrite(...) }` block, and the `rewrite { activeRecipe(...) }` block. Keep everything else. Then confirm no scaffolding survives anywhere:

```bash
grep -rn "openrewrite" build.gradle.kts backend/build.gradle.kts software-factory/build.gradle.kts settings.gradle.kts
```

Expected: no output.

- [ ] **Step 6: Verify the recipe did what it claims, by area**

Do not read the diff as one blob. Check each expected transformation actually landed:

```bash
echo "--- Boot version (expect 4.0.x) ---"
grep 'springBoot' gradle/libs.versions.toml
echo "--- modular starters (expect webmvc / security-oauth2-resource-server / starter-kafka) ---"
grep -E 'starter-web|oauth2|kafka|security-test' gradle/libs.versions.toml
echo "--- Jackson 3 (expect many) ---"
grep -rc 'tools\.jackson' backend/src software-factory/src | grep -v ':0' | wc -l
echo "--- Jackson annotations MUST still be com.fasterxml (expect JsonValue/JsonCreator/JsonProperty/JsonInclude/JsonIgnoreProperties only) ---"
grep -rho 'import com\.fasterxml\.jackson[^;]*' backend/src software-factory/src | sort -u
echo "--- JUnit 6 ---"
grep -rl 'org.junit.jupiter' backend/src/test | wc -l
```

The fourth check is the one that catches a real bug: `jackson-annotations` keeps the `com.fasterxml.jackson.core` groupId, so `JsonValue`, `JsonCreator`, `JsonProperty`, `JsonInclude` and `JsonIgnoreProperties` **must not** have moved to `tools.jackson`. Anything else still on `com.fasterxml` (`ObjectMapper`, `JsonNode`, `ObjectNode`, `ArrayNode`, `JsonProcessingException`) means the recipe under-applied.

- [ ] **Step 7: Commit the recipe output as-is, before fixing anything**

It will not compile yet — Spring AI and Embabel have not moved. Commit anyway, so the recipe's mechanical output is separable in history from the hand-written fixes.

```bash
git add -A
git commit -m "chore: apply the OpenRewrite UpgradeSpringBoot_4_0 recipe

Mechanical output only, committed unmodified so it stays separable from the
hand-written migration that follows. Does not compile at this commit: Spring AI
and Embabel are still on their Boot 3 lines."
```

---

### Task 4: Boot 4.1.1, catalogue repairs, and `:software-factory:` green

Take Boot to 4.1.1, fix the four Testcontainers renames and the six BOM overrides, and get the small module compiling and passing — it gates merging, so it goes first.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (the `ext[...]` override block in `subprojects`)
- Modify: `backend/build.gradle.kts` (Embabel repository removal)
- Modify: `software-factory/build.gradle.kts` if the recipe left anything wrong

**Interfaces:**
- Consumes: Boot 4.0.x from Task 3.
- Produces: `springBoot = "4.1.1"`, Testcontainers unpinned (BOM-managed at 2.0.5), and an `ext[...]` block reduced to the single override that is still above Boot's managed version.

- [ ] **Step 1: Bump Boot to 4.1.1**

In `gradle/libs.versions.toml`:

```toml
springBoot = "4.1.1"
```

Rationale for the record: `embabel-agent-platform-autoconfigure:1.5.1` — the first Embabel release supporting Boot 4 — declares `spring-boot-*:4.1.0` and `spring-ai-*:2.0.0`, and `spring-ai-starter-model-openai:2.0.1` declares `spring-boot-starter-restclient:4.1.1`. There is no Boot 4.0 configuration where both are on a supported release.

- [ ] **Step 2: Fix the Testcontainers artifact renames**

Testcontainers 2.x renamed **every** module artifact to a `testcontainers-` prefix. This fails as an unresolvable dependency, not a compile error. Also delete the version pin so the Boot BOM manages it at 2.0.5.

In `gradle/libs.versions.toml`, delete the `testcontainers = "1.20.4"` line from `[versions]`, and change the five library entries to:

```toml
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version = "2.0.5" }
testcontainers-junit-jupiter = { module = "org.testcontainers:testcontainers-junit-jupiter" }
testcontainers-mongodb = { module = "org.testcontainers:testcontainers-mongodb" }
testcontainers-kafka = { module = "org.testcontainers:testcontainers-kafka" }
testcontainers-elasticsearch = { module = "org.testcontainers:testcontainers-elasticsearch" }
```

The BOM keeps an explicit `2.0.5` because both modules import it as a `platform(...)`, which needs a concrete version.

- [ ] **Step 3: Repair the `ext[...]` managed-version overrides**

These six overrides exist to clear Dependency-Track findings (SIM-9). Against Boot 4.1.1's BOM, four are now redundant and two are actively wrong. Measured Boot 4.1.1 values: `opentelemetry 1.62.0`, `commons-lang3 3.20.0`, `httpclient5 5.6.4`, `httpcore5 5.4.3`, `log4j2 2.25.5`, `jackson-bom 3.1.5`, `jackson-2-bom 2.21.5`.

- `commons-lang3` pinned to `3.18.0` would **downgrade** below Boot's 3.20.0.
- `jackson-bom.version = "2.21.5"` would pin Jackson **3** to a version that does not exist — in Boot 4 that property means Jackson 3, and the Jackson 2 line is a separate `jackson-2-bom.version`.
- `httpclient5`, `httpcore5` and `log4j2` now match Boot exactly and are dead weight.

Replace the whole override block in the root `build.gradle.kts` `subprojects { ... }` with:

```kotlin
    // -----------------------------------------------------------------------
    // Managed-version overrides that clear Dependency-Track findings (SIM-9).
    //
    // These live HERE, not in the module build files, because the SBOM uploaded to
    // Dependency-Track as `simonrowe-dev/backend` is produced by the ROOT
    // `cyclonedxBom` task, which spans every configuration of every module. An
    // override applied to `backend` alone leaves `software-factory` resolving the
    // vulnerable version, and the finding survives with both versions listed.
    //
    // They MUST be `ext[...]` property overrides, not Gradle `constraints`. The
    // io.spring.dependency-management plugin FORCES every version the Spring Boot
    // BOM manages, which beats a constraint outright.
    //
    // Boot 4.1.1 now ships at or above the fixed version for commons-lang3
    // (3.20.0), httpclient5 (5.6.4), httpcore5 (5.4.3), log4j2 (2.25.5) and
    // jackson (3.1.5 / 2.21.5), so those five overrides are gone — keeping them
    // would have pinned dependencies BELOW Boot. In particular, in Boot 4
    // `jackson-bom.version` means Jackson 3; the old 2.21.5 value would have
    // pinned Jackson 3 to a version that does not exist.
    //
    // Drop the remaining entry once the Boot BOM ships that version or newer.
    // -----------------------------------------------------------------------

    // GHSA-rcgg-9c38-7xpx, fixed in 1.62.0. Boot 4.1.1 manages exactly 1.62.0, but
    // 1.64.0 is kept so both modules stay on one OpenTelemetry version alongside
    // the pinned opentelemetry-spring-boot-starter.
    ext["opentelemetry.version"] = "1.64.0"
```

- [ ] **Step 4: Remove the Embabel repository**

Embabel publishes to Maven Central. In `backend/build.gradle.kts`, delete the line:

```kotlin
    maven { url = uri("https://repo.embabel.com/artifactory/embabel-releases") }
```

- [ ] **Step 5: Get `:software-factory:` compiling**

```bash
./gradlew :software-factory:compileJava :software-factory:compileTestJava --console=plain 2>&1 | tail -40
```

Fix what breaks. Expected classes of failure in this module: Jackson 3 residue the recipe missed, JUnit 6 assertion imports, and the modular-starter split — Boot 4 no longer implicitly auto-configures slice-test infrastructure, so `@AutoConfigureMockMvc` may need `spring-boot-webmvc-test` on the test classpath.

- [ ] **Step 6: Run `:software-factory:` tests and read the Temporal result**

```bash
./gradlew :software-factory:test --console=plain 2>&1 | tail -40
```

This is the module's real risk. `io.temporal:temporal-spring-boot-autoconfigure:1.38.0` references `org.springframework.boot.context.properties.ConstructorBinding`, which moved to `org.springframework.boot.context.properties.bind.ConstructorBinding` in Boot 4. It is an annotation, so an absent class is skipped at class-load rather than being fatal — but Temporal's own `@ConfigurationProperties` constructor binding may behave differently.

If worker-registration tests fail, check whether Temporal's properties bound at all:

```bash
./gradlew :software-factory:test --tests '*WorkerRegistrationTest' --console=plain --rerun-tasks 2>&1 | tail -60
```

If Temporal's config genuinely will not bind, the fallback is an explicit `@ConfigurationProperties` bean definition in our own code rather than relying on Temporal's constructor binding. Do not silently disable the tests.

- [ ] **Step 7: Checkstyle the module and commit**

```bash
./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest --console=plain
git add -A
git commit -m "chore: take Boot to 4.1.1 and get software-factory building

Boot 4.1.1 rather than the recipe's 4.0.x, because Embabel 1.5.1 and Spring AI
2.0.1 both target 4.1.x. Fixes the four Testcontainers 2.x artifact renames
(every module gained a testcontainers- prefix), and cuts the managed-version
override block down to the one entry still above Boot's own BOM — commons-lang3
and jackson in particular would now have pinned dependencies BELOW Boot."
```

---

### Task 5: Spring AI 1.1.8 → 2.0.1 — dependencies and configuration

The artifact set changes shape: one module was renamed, one was deleted and merged into another. Configuration moves with it.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `backend/build.gradle.kts:~224`
- Modify: `backend/src/main/resources/application.yml:106-125`
- Modify: `backend/src/test/resources/application-test.yml:16-27`

**Interfaces:**
- Consumes: Boot 4.1.1 from Task 4.
- Produces: `springAi = "2.0.1"`, a single `spring-ai-starter-model-openai` covering chat *and* embeddings *and* image, and a `spring.ai.openai.*` config namespace with no `.options.` segment.

- [ ] **Step 1: Update the version catalogue**

In `gradle/libs.versions.toml`, set `springAi = "2.0.1"`, **delete** the `spring-ai-starter-model-openai-sdk` entry, and **rename** the advisor entry:

```toml
springAi = "2.0.1"
```

```toml
spring-ai-starter-model-openai = { module = "org.springframework.ai:spring-ai-starter-model-openai" }
spring-ai-starter-mcp-server-webmvc = { module = "org.springframework.ai:spring-ai-starter-mcp-server-webmvc" }
spring-ai-starter-vector-store-elasticsearch = { module = "org.springframework.ai:spring-ai-starter-vector-store-elasticsearch" }
spring-ai-vector-store-advisor = { module = "org.springframework.ai:spring-ai-vector-store-advisor" }
```

`spring-ai-advisors-vector-store` stopped at `2.0.0-M8` and was renamed to `spring-ai-vector-store-advisor`. `spring-ai-starter-model-openai-sdk` stopped at `2.0.0-M4` and was **deleted** — Spring AI 2.0 uses the official `openai-java` SDK for all OpenAI models inside the single `spring-ai-openai` module.

- [ ] **Step 2: Update the backend's dependency block**

In `backend/build.gradle.kts`, remove the `spring.ai.starter.model.openai.sdk` line and rename the advisor line:

```kotlin
    implementation(libs.spring.ai.starter.model.openai)
    implementation(libs.spring.ai.starter.mcp.server.webmvc)
    implementation(libs.spring.ai.starter.vector.store.elasticsearch)
    implementation(libs.spring.ai.vector.store.advisor)
```

- [ ] **Step 3: Verify the dependencies actually resolve before touching any code**

```bash
./gradlew :backend:dependencies --configuration runtimeClasspath --console=plain 2>&1 | grep -iE 'spring-ai|spring-boot:' | sort -u | head -30
```

Expected: every `spring-ai-*` at `2.0.1`, and `spring-boot` at `4.1.1`. A resolution failure here means an artifact name is wrong; fix it now rather than debugging it as a compile error.

Also confirm `io.spring.dependency-management` held Boot at 4.1.1 rather than letting Spring AI's declared `4.1.1` and the BOM disagree:

```bash
./gradlew :backend:dependencies --configuration runtimeClasspath --console=plain 2>&1 | grep -oE 'org\.springframework\.boot:[a-z-]+:[0-9.]+' | sort -u
```

Expected: a single Boot version across every artifact. A mixed tree (some 4.1.1, some 4.0.x) is a real problem — report it rather than proceeding.

- [ ] **Step 4: Merge the two OpenAI config namespaces in `application.yml`**

Today `spring.ai.openai` configures embeddings and image while `spring.ai.openai-sdk` configures chat, with `OpenAiChatAutoConfiguration` excluded to stop the two colliding. In 2.0 there is only one module, so the exclusion must go and chat moves into `spring.ai.openai.chat`. Spring AI 2.0 also flattened the `.options.` segment out of every model property.

Replace lines 106–125 of `backend/src/main/resources/application.yml` with:

```yaml
  autoconfigure:
    exclude: []
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:not-set}
      embedding:
        model: text-embedding-3-small
      image:
        model: gpt-image-1
      chat:
        # gpt-5.4-nano: newer generation than gpt-5-mini, cheaper per token
        # ($0.20/$1.25 vs $0.25/$2.00 per 1M) and faster.
        #
        # Do NOT set reasoning-effort here. These defaults are merged into EVERY
        # per-call OpenAiChatOptions, and reasoning_effort 400s twice over:
        # OpenAI rejects function tools + reasoning_effort for gpt-5.4-nano on
        # /v1/chat/completions (breaking the tool-enabled portfolio chat), and
        # gpt-4o-mini (GuardrailAdvisor's classifier) rejects the argument
        # outright, silently disabling the topic gate.
        model: ${OPENAI_CHAT_MODEL:gpt-5.4-nano}
    vectorstore:
      elasticsearch:
        index-name: content-embeddings
        dimensions: 1536
        similarity: cosine
        initialize-schema: true
```

If `exclude: []` causes a binding complaint, delete the `autoconfigure:` key entirely instead. The three excluded classes no longer exist: `OpenAiSdkEmbeddingAutoConfiguration` and `OpenAiSdkImageAutoConfiguration` were deleted with the sdk module, and `OpenAiChatAutoConfiguration` is now the *only* chat autoconfiguration and must run.

**An `exclude:` entry naming a class that does not exist is not an error** — it is silently ignored. That is exactly why this must be edited rather than left alone.

- [ ] **Step 5: Do the same in `application-test.yml`**

Replace the `ai:` and `autoconfigure:` sections (lines ~16–27) of `backend/src/test/resources/application-test.yml` with:

```yaml
  ai:
    openai:
      api-key: test-dummy-key
  autoconfigure:
    exclude:
      - org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration
      - org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration
      - org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
      - com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration
      - com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration
```

The two `openaisdk` exclusions are gone; the rest stay. Tests deliberately exclude the real chat and embedding autoconfigurations, which is correct and unchanged.

- [ ] **Step 6: Verify each remaining excluded class still exists**

A stale exclusion is silent, so check by hand rather than trusting the file:

```bash
./gradlew :backend:testRuntimeClasspath --console=plain -q 2>/dev/null
for c in \
  org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration \
  org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration \
  org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration ; do
  p=$(echo "$c" | tr '.' '/')
  found=$(find ~/.gradle/caches/modules-2 -name 'spring-ai-autoconfigure-*2.0.1.jar' -exec unzip -l {} \; 2>/dev/null | grep -c "$p.class")
  echo "$c -> $found"
done
```

Expected: each reports a non-zero count. A zero means the class was renamed in 2.0 and the exclusion is doing nothing.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml backend/build.gradle.kts \
        backend/src/main/resources/application.yml backend/src/test/resources/application-test.yml
git commit -m "feat: move Spring AI to 2.0.1 and merge the OpenAI config namespaces

spring-ai-starter-model-openai-sdk was deleted upstream and folded into
spring-ai-starter-model-openai, and spring-ai-advisors-vector-store was renamed
to spring-ai-vector-store-advisor. Collapses spring.ai.openai-sdk into
spring.ai.openai, drops the .options. segment Spring AI 2.0 flattened away, and
removes three autoconfiguration exclusions naming classes that no longer exist —
an exclusion naming an absent class is silently ignored, not an error."
```

---

### Task 6: Spring AI 2.0 API migration in the chat stack

The hand-written core. Spring AI 2.0 removed internal tool execution from every `ChatModel` and routed it through an auto-registered advisor — which invalidates the premise `CountingToolCallingManager` is built on.

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/chat/CountingToolCallingManager.java`
- Modify: `backend/src/main/java/com/simonrowe/chat/ChatConfig.java`
- Modify: `backend/src/main/java/com/simonrowe/chat/GuardrailAdvisor.java`
- Modify: `backend/src/main/java/com/simonrowe/chat/ContextAwareQuestionAnswerAdvisor.java`
- Modify: `backend/src/main/java/com/simonrowe/chat/ToolFilteringChatMemory.java`
- Modify: `backend/src/main/java/com/simonrowe/chat/ChatService.java`, `ChatTurnTracer.java`
- Modify: whatever else `:backend:compileJava` names

**Interfaces:**
- Consumes: Spring AI 2.0.1 on the classpath from Task 5.
- Produces: a compiling backend whose tool-call counting still feeds `ToolCallCounter`, which `ChatTurnTracer` reads at end of turn to emit the `langfuse` tool-count score.

- [ ] **Step 1: Get the full compile error list in one go**

```bash
./gradlew :backend:compileJava --console=plain 2>&1 | grep -E '^/|error:' | head -80
./gradlew :backend:compileJava --console=plain 2>&1 | grep -c 'error:'
```

Work from this list. Do not guess at what changed.

- [ ] **Step 2: Apply the known 2.0 API moves**

These are documented breaking changes; apply them where the compiler points:

| Removed / renamed | Replacement |
| --- | --- |
| `ToolCallAdvisor` | `ToolCallingAdvisor` |
| `internalToolExecutionEnabled(...)` option | removed — execution runs through the auto-registered `ToolCallingAdvisor` |
| `chatModel.getDefaultOptions()` | `chatModel.getOptions()` |
| `options.copy()` | `options.mutate()...build()` |
| `ChatClient.prompt().options(builtOptions)` | `.options(builder)` — pass the *builder* |
| `ChatMemory.DEFAULT_CONVERSATION_ID` | removed — pass `ChatMemory.CONVERSATION_ID` via advisor param per call |
| `MessageChatMemoryAdvisor.builder(m).conversationId(id)` | `.builder(m).build()`, id set at call time |
| `ModelOptionsUtils.jsonToMap/jsonToObject/toJsonString` | `new JsonHelper().fromJsonToMap/fromJson/toJson` |
| `OpenAiChatOptions.builder().N(n)` | `.n(n)` |
| `FunctionCallback` | `ToolCallback` |

- [ ] **Step 3: Re-establish `CountingToolCallingManager`'s premise, do not assume it**

Its javadoc states that it counts inside `ToolCallingManager.executeToolCalls` because `OpenAiSdkChatModel` consumes the aggregated tool-call `ChatResponse` internally and it never reaches `ChatService`. Spring AI 2.0 moved that loop out of the model and into `ToolCallingAdvisor`, so the premise must be re-checked.

First find out whether `ToolCallingManager` is still on the path at all:

```bash
find ~/.gradle/caches/modules-2 -name 'spring-ai-model-2.0.1.jar' -o -name 'spring-ai-client-chat-2.0.1.jar' | while read j; do
  echo "== $j"; unzip -l "$j" | grep -iE 'ToolCallingManager|ToolCallingAdvisor' ;
done
```

- If `ToolCallingManager` still exists and `ToolCallingAdvisor` delegates to it, the decorator survives with only signature changes. Verify the bean is still injected in `ChatConfig` and that `ToolCallingAdvisor` picks up the decorated instance rather than constructing its own.
- If tool execution now happens only inside `ToolCallingAdvisor`, the counting moves: wrap or replace the advisor instead, keeping the same `ToolCallCounter` hand-off so `ChatTurnTracer` is unchanged.

Update the class javadoc to describe whichever mechanism is actually true. A javadoc explaining a premise that no longer holds is worse than none.

- [ ] **Step 4: Keep the existing counting tests passing, and keep what they assert**

Two test classes already cover this and **must not be weakened to make them compile**:
`backend/src/test/java/com/simonrowe/chat/CountingToolCallingManagerTest.java` and
`ToolCallingManagerOverrideTest.java`.

```bash
./gradlew :backend:test --console=plain --rerun-tasks \
  --tests 'com.simonrowe.chat.CountingToolCallingManagerTest' \
  --tests 'com.simonrowe.chat.ToolCallingManagerOverrideTest' \
  --tests 'com.simonrowe.chat.ToolCallCounterTest' 2>&1 | tail -40
```

Read them first. The behaviour that must survive, whatever mechanism replaces it: two tool calls executed in one round produce a count of **2** for that session, readable exactly once via `ToolCallCounter.takeCount(sessionId)` (it removes on read), and a null `sessionId` is ignored rather than throwing.

`ToolCallingManagerOverrideTest` is the one that proves the decorator is actually the bean Spring wires in. If Task 6 Step 3 concludes that counting has to move to an advisor, that test's *subject* changes — rewrite it to assert the advisor-level equivalent rather than deleting it. Deleting it would leave the Langfuse `tool-call-count` score unguarded, and a silently-zero count is invisible in production.

- [ ] **Step 5: Compile and iterate until clean**

```bash
./gradlew :backend:compileJava :backend:compileTestJava --console=plain 2>&1 | tail -40
```

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: migrate the chat stack to the Spring AI 2.0 tool-calling API

Spring AI 2.0 removed internal tool execution from every ChatModel and routes it
through an auto-registered ToolCallingAdvisor, which invalidates the premise
CountingToolCallingManager was written against. Re-establishes where tool calls
can actually be observed and pins it with a test, because the count feeds a
Langfuse score and a silently-zero count is invisible in production."
```

---

### Task 7: Embabel 1.0.0 → 1.5.1

**Files:**
- Modify: `gradle/libs.versions.toml` (`embabel`)
- Modify: `backend/src/main/java/com/simonrowe/agents/` — `AgentConfig`, `ArticleSectionWriter`, `ContentAggregationAgent`, `DigestComposer`, `DigestMetadataGenerator`, `WeeklyDigestAgent`
- Modify: `backend/src/main/java/com/simonrowe/platform/ReleaseSummarySweep.java`
- Modify: `backend/src/main/java/com/simonrowe/summary/ArticleSummaryService.java`
- Modify: `backend/src/test/java/com/simonrowe/AbstractIntegrationTest.java` and the ten other test files touching `com.embabel`

**Interfaces:**
- Consumes: Spring AI 2.0.1 from Tasks 5–6 (Embabel 1.5.1 depends on `spring-ai-*:2.0.0`, which the BOM manages up to 2.0.1).
- Produces: a compiling agent layer; `AbstractIntegrationTest`'s mock of Embabel's `Ai` still satisfies every agent test.

- [ ] **Step 1: Bump the version**

In `gradle/libs.versions.toml`:

```toml
embabel = "1.5.1"
```

1.5.x is the first Embabel line supporting Boot 4 — 1.0.0 and below compile against Boot 3.5.14.

- [ ] **Step 2: Compile and collect the breakage**

```bash
./gradlew :backend:compileJava :backend:compileTestJava --console=plain 2>&1 | grep -E 'embabel|error:' | head -40
```

- [ ] **Step 3: Apply Embabel's documented consumer changes**

From Embabel's own Boot 4 / Spring AI 2.0 migration notes:

| Change | Action |
| --- | --- |
| `.defaultOptions(...)` | → `.options(...)` |
| `.retryTemplate(...)` | removed — retry moved to a wrapper layer |
| `Generation.getResult()`, `Message.getText()` | now nullable — add explicit null handling, do not blind-dereference |
| `StructuredOutputConverter` construction | needs an explicit `Class<...>` argument |

- [ ] **Step 4: Fix `AbstractIntegrationTest`'s `Ai` mock**

This is the most likely test-side break: the mock has to satisfy whatever `Ai` looks like at 1.5.1. Run one agent test in isolation to see the real failure rather than reading it out of a full-suite log:

```bash
./gradlew :backend:test --tests 'com.simonrowe.agents.DigestComposerTest' --console=plain --rerun-tasks 2>&1 | tail -40
```

- [ ] **Step 5: Run every Embabel-touching test**

```bash
./gradlew :backend:test --console=plain --rerun-tasks \
  --tests 'com.simonrowe.agents.*' \
  --tests 'com.simonrowe.summary.*' \
  --tests 'com.simonrowe.platform.ReleaseSummarySweepTest' 2>&1 | tail -40
```

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml backend/src
git commit -m "feat: move Embabel to 1.5.1

1.5.x is the first Embabel line that supports Boot 4 — it targets Boot 4.1.0 and
Spring AI 2.0.0, which is what settled Boot 4.1.1 as this upgrade's target."
```

---

### Task 8: Whole-backend green — compile, checkstyle, tests, coverage

**Files:**
- Modify: whatever the compiler and Checkstyle name.

**Interfaces:**
- Consumes: Tasks 4–7.
- Produces: `:backend:test` and `:backend:jacocoTestCoverageVerification` passing.

- [ ] **Step 1: Compile clean**

```bash
./gradlew :backend:compileJava :backend:compileTestJava --console=plain 2>&1 | tail -30
```

- [ ] **Step 2: Checkstyle both modules**

`maxWarnings = 0`, so any new violation fails the build. Fix the code, never raise the threshold.

```bash
./gradlew :backend:checkstyleMain :backend:checkstyleTest \
          :software-factory:checkstyleMain :software-factory:checkstyleTest --console=plain 2>&1 | tail -30
```

- [ ] **Step 3: Run the full backend suite**

`UP-TO-DATE` is not trustworthy after a recipe run — Gradle caches inputs the recipe changed in ways it tracks poorly.

```bash
./gradlew :backend:test --console=plain --rerun-tasks 2>&1 | tail -40
```

Compare the test count against the Task 1 Step 1 baseline. **A count that dropped means tests silently stopped being discovered** — most likely the JUnit 5→6 migration left a class the platform no longer picks up. Investigate a drop; do not accept a green run with fewer tests.

- [ ] **Step 4: Verify the coverage floor**

```bash
./gradlew :backend:jacocoTestCoverageVerification --console=plain 2>&1 | tail -20
```

Expected: passes at the 0.78 minimum.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix: bring the backend suite green on Boot 4.1.1"
```

---

### Task 9: Verification gates the test suite cannot cover

Three things a green suite says nothing about: Mongock (disabled in tests), context startup, and the chat path.

**Files:** none — this task changes nothing unless a gate fails.

**Interfaces:**
- Consumes: a green build from Task 8.
- Produces: evidence, recorded in the PR description.

- [ ] **Step 1: Run the Mongock-enabled test by name**

`application-test.yml` sets `mongock.enabled: false`, so Task 8 proved nothing about migrations — and every production data change ships as a change unit.

```bash
./gradlew :backend:test --tests '*V011SeedAndBackfillDanVegaBlogIntegrationTest' \
  --console=plain --rerun-tasks --info 2>&1 | grep -iE 'mongock|APPLIED|lock|BUILD|tests' | head -40
```

Expected: `Mongock acquired the lock`, at least one `APPLIED - {...}` line, and BUILD SUCCESSFUL. A green run with **no** Mongock output means the property override did not take and the gate did not actually run — that is a failure, not a pass.

Context: Mongock 5.5.1 declares `spring-boot-3.version = [3.0.0-RC1, 4.0.0)` and publishes no `mongock-springboot-v4`, so it does not claim Boot 4 support. It was proven to work on Boot 4.1.1 in a standalone spike during design. This step confirms it in the real application.

- [ ] **Step 2: Start the app and confirm the context comes up**

A Boot major upgrade breaks at context startup in ways that compile and unit-test perfectly: missing autoconfiguration, a removed bean, a renamed property. Use the `local-env` skill to bring up Mongo, Elasticsearch and Kafka, then:

```bash
./scripts/start-backend.sh
```

Watch for `Started Application in ...`, then:

```bash
curl -s localhost:8082/actuator/health | python3 -m json.tool
curl -s localhost:8080/api/blogs | head -c 400
curl -s localhost:8080/api/platform/status | python3 -m json.tool | head -30
```

Expected: health `UP`, blogs returning JSON, and `/api/platform/status` reporting a backend commit that is not `unknown`.

- [ ] **Step 3: Confirm no autoconfiguration silently vanished**

The Spring AI namespace merge in Task 5 is the risk: a chat model that failed to configure produces no error until the first chat request.

```bash
curl -s localhost:8082/actuator/beans | python3 -c "
import json,sys
d=json.load(sys.stdin)
beans=d['contexts']['application']['beans']
for n in sorted(beans):
    if any(k in n.lower() for k in ('chatmodel','chatclient','embeddingmodel','imagemodel','vectorstore','toolcalling')):
        print(n, '->', beans[n]['type'])
"
```

Expected: an OpenAI chat model, an embedding model, an image model, the Elasticsearch vector store, and the tool-calling manager or advisor. A missing chat model here is the failure this whole step exists to catch.

- [ ] **Step 4: Drive the chatbot end to end**

Unit tests mock the model. Use the `chat-e2e-verify` skill against the running local stack. Specifically confirm: an on-topic question answers, a tool-backed question (one that triggers a blog or web-search tool) answers, and an off-topic question is refused by `GuardrailAdvisor`.

The guardrail is the subtle one — a `reasoning_effort` leak into per-call options silently disables the topic gate rather than erroring, and Task 5 rewrote exactly that config.

- [ ] **Step 5: Confirm Langfuse still receives traces**

Tool-call counting was rewritten in Task 6 and the observation wiring reads Spring AI observation contexts that moved. Use the `langfuse-verify` skill. Confirm a chat turn produces a trace with a non-empty input and output, and that the tool-count score is present and non-zero for a tool-backed turn.

- [ ] **Step 6: Confirm the SBOM is still produced and populated**

An empty BOM uploads to Dependency-Track fine and reads as "clean".

```bash
./gradlew cyclonedxBom --console=plain
python3 -c "
import json,glob
for f in glob.glob('build/**/bom.json',recursive=True):
    d=json.load(open(f)); print(f,'components:',len(d.get('components',[])))
"
```

Expected: hundreds of components. Also spot-check that Boot components read `4.1.1`, confirming the SBOM describes what actually ships.

- [ ] **Step 7: Spring-specific diagnostics**

Via the spring-tools MCP server: call `refreshWorkspace`, then `getProjectDiagnostics` for `backend` and for `software-factory`.

- [ ] **Step 8: Stop the local stack**

```bash
./scripts/stop.sh
```

---

### Task 10: Documentation

**Files:**
- Modify: `CLAUDE.md` (a new entry at the top of "Recent Changes")
- Modify: `docs/runbooks/dependency-track.md` (the managed-version override list)
- Create: `docs/runbooks/spring-boot-4-upgrade.md`

**Interfaces:**
- Consumes: everything learned in Tasks 1–9.
- Produces: the record a future upgrade reads first.

- [ ] **Step 1: Write the runbook**

Create `docs/runbooks/spring-boot-4-upgrade.md` covering, with the real measured values from this run:

- Target rationale: 4.1.1 not 4.0.x, and the Embabel/Spring AI evidence for it.
- Mongock: declares no Boot 4 support, verified to work, how to re-verify (the named test, and that a green suite proves nothing because `mongock.enabled: false`).
- Testcontainers 2.x artifact renames — fails as unresolvable dependency, not compile error.
- The `ext[...]` override repair: which five were deleted and why keeping them would have pinned dependencies *below* Boot, especially `jackson-bom.version` meaning Jackson 3 in Boot 4.
- The Spring AI namespace merge, and the fact that an `autoconfigure.exclude` entry naming an absent class is silently ignored.
- Whatever the Temporal `ConstructorBinding` situation turned out to be in Task 4 Step 6.
- Java 25 and why not 26.

- [ ] **Step 2: Add the `CLAUDE.md` entry**

Add to the **top** of the `## Recent Changes` list, in the established house style (lead line naming the change, then the load-bearing details a future reader would otherwise rediscover the hard way).

**Do not run `.specify/scripts/bash/update-agent-context.sh`** — it fails with `grep: repetition-operator operand invalid` and silently strips the lead line from eight existing entries.

- [ ] **Step 3: Update the Dependency-Track runbook**

`docs/runbooks/dependency-track.md` describes the managed-version overrides that clear SIM-9 findings. Five of the six are gone. Update it, and note that Boot 4.1.1 now ships at or above the fixed version for commons-lang3, httpclient5, httpcore5, log4j2 and jackson.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/
git commit -m "docs: record the Spring Boot 4.1 upgrade"
```

---

### Task 11: Open the pull request

- [ ] **Step 1: Final full gate from clean**

```bash
./gradlew clean
./gradlew :backend:checkstyleMain :backend:checkstyleTest \
          :software-factory:checkstyleMain :software-factory:checkstyleTest \
          :backend:test :software-factory:test \
          :backend:jacocoTestCoverageVerification cyclonedxBom \
          --console=plain 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL. Read the output — do not infer it from a zero exit code alone.

- [ ] **Step 2: Confirm no scaffolding leaked into the branch**

```bash
git diff origin/main... -- build.gradle.kts | grep -i openrewrite
git log origin/main..HEAD --oneline
```

Expected: no `openrewrite` output at all.

- [ ] **Step 3: Open the PR via the `pr-review-loop` skill**

Creating a pull request in this org means using `pr-review-loop` — it owns pre-flight, opening, and waiting on all three signals (CI, the reviewer bot, SonarQube Cloud).

Flag prominently in the PR description, under Reviewer Guidance:

- **`software-factory` is upgraded in this PR, and it hosts the `Code Review` check that gates merging.** If that check never appears, the module is broken — recovery is repo-admin bypass, which is an escape hatch and lands in rule insights.
- The verification evidence from Task 9, especially the Mongock output.
- That `:backend:test` count matches the pre-upgrade baseline.
