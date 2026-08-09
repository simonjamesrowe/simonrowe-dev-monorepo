# Favourites-Driven Weekly Digest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the weekly digest blog post so it covers only news articles favourited in the last 7 days, with a real multi-paragraph summary of each article's scraped content instead of a list of links.

**Architecture:** `WeeklyDigestAgent` becomes a five-stage pipeline — select favourites, re-scrape each article, write one section per article with a dedicated LLM call, compose the whole post with a synthesis call that is discarded if it mangles any link, then publish. Two new components (`ArticleSectionWriter`, `DigestComposer`) hold the per-article and whole-document work so the agent is pure orchestration. Embabel is upgraded to 1.0.0 first, as an isolated no-behaviour-change step.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Embabel Agent 1.0.0, Spring AI 1.1.8, MongoDB, JUnit 5 + Mockito + AssertJ, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-08-favourites-digest-design.md`

## Global Constraints

- **Java style:** Google Java Style, enforced by Checkstyle via the pre-commit hook. Match the surrounding files: 2-space indent, `final` on constructor parameters, lines wrapped at roughly 80 characters.
- **Digest model:** `gpt-5.6-luna` — the full id. **Never** `gpt-5.6`, which is an alias that routes to Sol.
- **Model pricing:** $0.20 per 1M input tokens, $1.20 per 1M output tokens.
- **`gpt-5.6-luna` rejects any `temperature` other than the default of 1.** Never set temperature on a digest call.
- **Favourite window:** 7 days, on `Favourite.createdAt` (when it was hearted), not on article publish date.
- **Favourite type:** `FavouriteType.NEWS` only. `EVENT` favourites are ignored entirely.
- **No cap** on the number of articles per digest.
- **Zero favourites in the window → publish nothing.** Log and return.
- **Source-text cap:** 12,000 characters per article before it reaches the model.
- **Cron is unchanged.** `aggregation.digest.cron: "0 0 8 * * MON"` in `application.yml` already runs weekly.
- **Commit per task.** Conventional commits (`feat:`, `fix:`, `chore:`), no Jira refs, no Claude attribution.

## File Structure

**Created:**
- `backend/src/main/java/com/simonrowe/agents/DigestSection.java` — record carrying one article's finished section between stages.
- `backend/src/main/java/com/simonrowe/agents/ArticleSectionWriter.java` — scrapes and summarises exactly one article.
- `backend/src/main/java/com/simonrowe/agents/DigestComposer.java` — assembles sections, runs the synthesis call, validates links.
- `backend/src/test/java/com/simonrowe/agents/ArticleSectionWriterTest.java`
- `backend/src/test/java/com/simonrowe/agents/DigestComposerTest.java`

**Modified:**
- `backend/build.gradle.kts:120-121,142` — Embabel version.
- `gradle/libs.versions.toml` — Embabel version moves into the catalog.
- `backend/src/main/java/com/simonrowe/agents/AgentConfig.java` — registers the `gpt-5.6-luna` model bean.
- `backend/src/main/java/com/simonrowe/agents/WeeklyDigestAgent.java` — rewritten as orchestration.
- `backend/src/main/java/com/simonrowe/agents/DigestMetadataGenerator.java` — drops the `recentBlogs` parameter, reads the model property.
- `backend/src/main/java/com/simonrowe/favourites/FavouriteRepository.java` — one new derived query.
- `backend/src/main/resources/application.yml` — `aggregation.digest.model` and `.window-days`.
- `backend/src/test/java/com/simonrowe/agents/WeeklyDigestAgentTest.java` — rewritten against favourites.
- `docs/model-usage.md` — rows 4-5 stop being "after this change".

---

### Task 1: Upgrade Embabel to 1.0.0

The 0.x → 1.0 boundary removed deprecated methods and reworked the tool loop, but every API this codebase uses was verified present in the 1.0.0 jars. The expected diff is the version bump alone; compilation is the check on that expectation.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `backend/build.gradle.kts:120-121,142`

**Interfaces:**
- Consumes: nothing.
- Produces: Embabel 1.0.0 on the classpath, which Task 2 needs for `Gpt5ChatOptionsConverter`.

- [ ] **Step 1: Add the Embabel version to the version catalog**

In `gradle/libs.versions.toml`, under `[versions]`, after the `springAi = "1.1.8"` line:

```toml
embabel = "1.0.0"
```

In the same file, under `[libraries]`, after the `spring-ai-*` entries:

```toml
embabel-agent-starter = { module = "com.embabel.agent:embabel-agent-starter", version.ref = "embabel" }
embabel-agent-starter-openai = { module = "com.embabel.agent:embabel-agent-starter-openai", version.ref = "embabel" }
embabel-agent-test = { module = "com.embabel.agent:embabel-agent-test", version.ref = "embabel" }
```

- [ ] **Step 2: Point the build at the catalog entries**

In `backend/build.gradle.kts`, replace these two lines:

```kotlin
    implementation("com.embabel.agent:embabel-agent-starter:0.3.5")
    implementation("com.embabel.agent:embabel-agent-starter-openai:0.3.5")
```

with:

```kotlin
    implementation(libs.embabel.agent.starter)
    implementation(libs.embabel.agent.starter.openai)
```

and replace this line:

```kotlin
    testImplementation("com.embabel.agent:embabel-agent-test:0.3.5")
```

with:

```kotlin
    testImplementation(libs.embabel.agent.test)
```

- [ ] **Step 3: Verify it compiles**

Run: `cd backend && ../gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

If compilation fails, the failure is a genuine 1.0.0 breaking change and the fix belongs in this task — do not carry it into a later one. The APIs this codebase uses and that were verified present in 1.0.0 are: `Ai.withLlm(String)`, `PromptRunner.respond(List<Message>)`, `AssistantMessage.getContent()`, `PromptRunner.creating(Class)`, `Creating.fromPrompt(String)`, and the `@Agent` / `@Action` annotations.

- [ ] **Step 4: Run the full test suite**

Run: `cd backend && ../gradlew test`
Expected: BUILD SUCCESSFUL, same tests passing as before the bump. No test should need editing in this task — that is the whole point of doing the upgrade separately.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml backend/build.gradle.kts
git commit -m "chore: upgrade Embabel agent to 1.0.0"
```

---

### Task 2: Register gpt-5.6-luna and add digest config

Embabel resolves `ai.withLlm(name)` against `LlmService` beans built from its own bundled registry (`classpath:models/openai-models.yml`), which stops at GPT-5.4 even in 1.0.0. `gpt-5.6-luna` must be registered explicitly.

`Gpt5ChatOptionsConverter` is the converter Embabel uses for the GPT-5 family; it is what stops a `temperature` being sent to a model that rejects it. Using `StandardOpenAiOptionsConverter` here would produce 400s.

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/agents/AgentConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/simonrowe/agents/Gpt56LunaRegistrationTest.java` (create)

**Interfaces:**
- Consumes: Embabel 1.0.0 from Task 1.
- Produces: a resolvable `gpt-5.6-luna` model, and the property `aggregation.digest.model` (default `gpt-5.6-luna`) that Tasks 3, 4 and 6 inject via `@Value`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/simonrowe/agents/Gpt56LunaRegistrationTest.java`:

```java
package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.spi.LlmService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.simonrowe.AbstractIntegrationTest;

/**
 * Guards the explicit registration of gpt-5.6-luna. Embabel's bundled model
 * registry stops at GPT-5.4, so if a future upgrade changes the registration
 * API this fails the build rather than failing at 08:00 on a Monday.
 */
@SpringBootTest
class Gpt56LunaRegistrationTest extends AbstractIntegrationTest {

  @Autowired
  private Map<String, LlmService<?>> llmServices;

  @Test
  void gpt56LunaIsRegistered() {
    assertThat(llmServices.values())
        .anySatisfy(llm -> assertThat(llm.getName()).isEqualTo("gpt-5.6-luna"));
  }
}
```

`getName()` comes from `com.embabel.common.ai.model.ModelMetadata`, which `LlmService` extends — verified against the 1.0.0 jars.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.Gpt56LunaRegistrationTest"`
Expected: FAIL — no bean whose name is `gpt-5.6-luna`.

- [ ] **Step 3: Register the model**

Replace `backend/src/main/java/com/simonrowe/agents/AgentConfig.java` with:

```java
package com.simonrowe.agents;

import com.embabel.agent.openai.Gpt5ChatOptionsConverter;
import com.embabel.agent.openai.OpenAiCompatibleModelFactory;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.model.PricingModel;
import java.time.LocalDate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.simonrowe.agents")
public class AgentConfig {

  /**
   * Registers {@code gpt-5.6-luna} with Embabel. Its bundled model registry
   * (@code classpath:models/openai-models.yml}) was verified against OpenAI on
   * 2026-03-29 and stops at the GPT-5.4 family, in 1.0.0 as well as 0.3.5, so
   * this model is invisible to {@code ai.withLlm(...)} without an explicit bean.
   *
   * <p>{@link Gpt5ChatOptionsConverter} is required rather than the standard
   * converter: gpt-5.6-luna accepts only the default temperature of 1 and
   * returns 400 for any other value.
   */
  @Bean
  public LlmService<?> gpt56LunaLlm(final OpenAiCompatibleModelFactory factory) {
    return factory.openAiCompatibleLlm(
        "gpt-5.6-luna",
        PricingModel.usdPer1MTokens(0.20, 1.20),
        "OpenAI",
        LocalDate.of(2026, 7, 30),
        Gpt5ChatOptionsConverter.INSTANCE);
  }
}
```

The `LocalDate` is knowledge-cutoff metadata only and does not affect behaviour; 2026-07-30 is the model's pricing-effective date.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.Gpt56LunaRegistrationTest"`
Expected: PASS.

If `OpenAiCompatibleModelFactory` cannot be injected, inject `com.embabel.agent.config.models.openai.OpenAiModelsConfig` instead — it extends `OpenAiCompatibleModelFactory` and is the bean Embabel's autoconfiguration creates.

- [ ] **Step 5: Add the digest properties**

In `backend/src/main/resources/application.yml`, in the existing `aggregation.digest` block (around line 374), add two keys alongside `cron`:

```yaml
aggregation:
  schedule:
    cron: "0 0 */6 * * *"
  digest:
    cron: "0 0 8 * * MON"
    model: "gpt-5.6-luna"
    window-days: 7
```

- [ ] **Step 6: Verify a real call works**

This is the one step that cannot be covered by a unit test, and the failure it catches (a 400 on an unsupported parameter) only appears against the live API.

Run: `cd backend && ../gradlew bootRun` with `OPENAI_API_KEY` set, then trigger a digest from the admin API and confirm the logs show a completion rather than a 400. Alternatively confirm directly:

```bash
curl -s https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-5.6-luna","messages":[{"role":"user","content":"Reply with OK"}]}' \
  | head -20
```

Expected: a normal completion. A 400 mentioning `temperature` means the options converter is wrong.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/simonrowe/agents/AgentConfig.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/simonrowe/agents/Gpt56LunaRegistrationTest.java
git commit -m "feat: register gpt-5.6-luna for digest generation"
```

---

### Task 3: DigestSection and ArticleSectionWriter

One article in, one finished section out. This is where the re-scrape and the per-article LLM call live.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/agents/DigestSection.java`
- Create: `backend/src/main/java/com/simonrowe/agents/ArticleSectionWriter.java`
- Test: `backend/src/test/java/com/simonrowe/agents/ArticleSectionWriterTest.java`

**Interfaces:**
- Consumes: `aggregation.digest.model` from Task 2.
- Produces:
  - `DigestSection(String articleId, String title, String url, String body, boolean fallback)`
  - `ArticleSectionWriter.write(AggregatedArticle article)` → `DigestSection`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/simonrowe/agents/ArticleSectionWriterTest.java`:

```java
package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Message;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import com.simonrowe.aggregation.AggregatedArticle;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArticleSectionWriterTest {

  private static final String MODEL = "gpt-5.6-luna";

  @Mock private SitemapHtmlScraper scraper;
  @Mock private Ai ai;

  private PromptRunner promptRunner;
  private AssistantMessage assistantMessage;
  private ArticleSectionWriter writer;

  private static final AggregatedArticle ARTICLE = new AggregatedArticle(
      "art-1", "Spring Boot 4 Released", "InfoQ",
      "https://infoq.com", "https://infoq.com/spring-boot-4",
      "Stored summary.", "Stored full content that is long enough to use.",
      "Jane Doe", Instant.now(), Instant.now(), true, null);

  @BeforeEach
  void setUp() {
    promptRunner = mock(PromptRunner.class);
    assistantMessage = mock(AssistantMessage.class);
    lenient().when(ai.withLlm(MODEL)).thenReturn(promptRunner);
    lenient().when(promptRunner.respond(anyList())).thenReturn(assistantMessage);
    lenient().when(assistantMessage.getContent()).thenReturn("Generated prose.");
    writer = new ArticleSectionWriter(scraper, ai, MODEL);
  }

  @Test
  void usesFreshlyScrapedContentWhenScrapeSucceeds() {
    when(scraper.scrapeArticlePagePublic("https://infoq.com/spring-boot-4"))
        .thenReturn(new ScrapedContent(
            "Spring Boot 4 Released", "https://infoq.com/spring-boot-4",
            "Freshly scraped body text.", Instant.now(), "Jane Doe", null, false));

    DigestSection section = writer.write(ARTICLE);

    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(promptRunner).respond(captor.capture());
    assertThat(captor.getValue().get(0).getContent())
        .contains("Freshly scraped body text.");
    assertThat(section.body()).isEqualTo("Generated prose.");
    assertThat(section.fallback()).isFalse();
    assertThat(section.articleId()).isEqualTo("art-1");
    assertThat(section.title()).isEqualTo("Spring Boot 4 Released");
    assertThat(section.url()).isEqualTo("https://infoq.com/spring-boot-4");
  }

  @Test
  void fallsBackToStoredFullContentWhenScrapeReturnsNull() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);

    writer.write(ARTICLE);

    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(promptRunner).respond(captor.capture());
    assertThat(captor.getValue().get(0).getContent())
        .contains("Stored full content that is long enough to use.");
  }

  @Test
  void fallsBackToStoredSummaryWhenScrapeAndFullContentAreEmpty() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);
    AggregatedArticle noContent = new AggregatedArticle(
        "art-2", "Thin Article", "RSS Source",
        "https://rss.com", "https://rss.com/thin",
        "Only a stored summary.", "", null,
        Instant.now(), Instant.now(), true, null);

    writer.write(noContent);

    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(promptRunner).respond(captor.capture());
    assertThat(captor.getValue().get(0).getContent())
        .contains("Only a stored summary.");
  }

  @Test
  void fallsBackToStoredSummaryWhenLlmThrows() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);
    when(promptRunner.respond(anyList()))
        .thenThrow(new RuntimeException("upstream 500"));

    DigestSection section = writer.write(ARTICLE);

    assertThat(section.body()).isEqualTo("Stored summary.");
    assertThat(section.fallback()).isTrue();
  }

  @Test
  void truncatesSourceTextToTwelveThousandCharacters() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);
    AggregatedArticle huge = new AggregatedArticle(
        "art-3", "Huge", "Source", "https://src.com", "https://src.com/huge",
        "Summary.", "x".repeat(20_000), null,
        Instant.now(), Instant.now(), true, null);

    writer.write(huge);

    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(promptRunner).respond(captor.capture());
    assertThat(captor.getValue().get(0).getContent()).doesNotContain("x".repeat(12_001));
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.ArticleSectionWriterTest"`
Expected: FAIL — `ArticleSectionWriter` and `DigestSection` do not exist.

- [ ] **Step 3: Create the DigestSection record**

Create `backend/src/main/java/com/simonrowe/agents/DigestSection.java`:

```java
package com.simonrowe.agents;

/**
 * One article's finished contribution to a digest post.
 *
 * <p>{@code title} and {@code url} come from MongoDB and are never
 * model-generated, so the link in the rendered post cannot be hallucinated.
 * {@code fallback} is true when the summarising call failed and {@code body}
 * holds the article's stored summary instead of generated prose; a digest in
 * which every section is a fallback is not worth publishing.
 */
public record DigestSection(
    String articleId,
    String title,
    String url,
    String body,
    boolean fallback
) {
}
```

- [ ] **Step 4: Create the ArticleSectionWriter**

Create `backend/src/main/java/com/simonrowe/agents/ArticleSectionWriter.java`:

```java
package com.simonrowe.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import com.simonrowe.aggregation.AggregatedArticle;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns one favourited article into one digest section: re-scrapes the source
 * for the freshest and fullest text, then asks the model for a few paragraphs
 * about it.
 *
 * <p>Re-scraping rather than trusting the stored {@code fullContent} matters
 * because that field's depth varies — full page text for HTML and sitemap
 * sources, often a bare feed snippet for RSS ones — and it may be weeks stale.
 */
@Component
public class ArticleSectionWriter {

  private static final Logger LOG =
      LoggerFactory.getLogger(ArticleSectionWriter.class);

  private static final int MAX_SOURCE_CHARS = 12_000;

  private static final String SECTION_PROMPT = """
      You are Simon Rowe, writing one section of your weekly digest about an \
      article you saved this week.

      Write 2-3 paragraphs summarising what this piece actually says — the \
      substance, not a description of the article. Then finish with one short \
      sentence, on its own line, beginning "Why this caught my eye:" giving \
      the angle that makes it worth someone's time.

      Write in first person, in Markdown. Do NOT write any heading — the \
      heading and the link are added separately. Do NOT repeat the title.

      Title: %s
      Source: %s

      Article text:
      %s
      """;

  private final SitemapHtmlScraper scraper;
  private final Ai ai;
  private final String model;

  public ArticleSectionWriter(
      final SitemapHtmlScraper scraper,
      final Ai ai,
      @Value("${aggregation.digest.model}") final String model) {
    this.scraper = scraper;
    this.ai = ai;
    this.model = model;
  }

  /**
   * Builds the digest section for a single article.
   *
   * @param article the favourited article
   * @return the section; never null, with {@code fallback} set when the model
   *     call failed and the stored summary was used instead
   */
  public DigestSection write(final AggregatedArticle article) {
    String sourceText = sourceTextFor(article);
    try {
      String prompt = String.format(
          SECTION_PROMPT, article.title(), article.sourceName(), sourceText);
      String body = ai.withLlm(model)
          .respond(List.of(new UserMessage(prompt)))
          .getContent();
      return new DigestSection(
          article.id(), article.title(), article.originalUrl(), body, false);
    } catch (Exception e) {
      LOG.warn("Failed to summarise '{}', using stored summary: {}",
          article.title(), e.getMessage());
      return new DigestSection(
          article.id(), article.title(), article.originalUrl(),
          article.summary(), true);
    }
  }

  private String sourceTextFor(final AggregatedArticle article) {
    ScrapedContent scraped =
        scraper.scrapeArticlePagePublic(article.originalUrl());
    if (scraped != null && isUsable(scraped.content())) {
      return truncate(scraped.content());
    }
    LOG.info("Scrape returned nothing usable for '{}', "
        + "falling back to stored content", article.title());
    if (isUsable(article.fullContent())) {
      return truncate(article.fullContent());
    }
    return truncate(article.summary());
  }

  private static boolean isUsable(final String text) {
    return text != null && !text.isBlank();
  }

  private static String truncate(final String text) {
    if (text == null) {
      return "";
    }
    return text.length() > MAX_SOURCE_CHARS
        ? text.substring(0, MAX_SOURCE_CHARS)
        : text;
  }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.ArticleSectionWriterTest"`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/simonrowe/agents/DigestSection.java \
        backend/src/main/java/com/simonrowe/agents/ArticleSectionWriter.java \
        backend/src/test/java/com/simonrowe/agents/ArticleSectionWriterTest.java
git commit -m "feat: add per-article digest section writer"
```

---

### Task 4: DigestComposer

Builds a guaranteed-correct document first, then tries to improve it, then checks the improvement didn't break anything. The validation is the point: a synthesis pass over a whole document is exactly where a model silently drops or rewrites links.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/agents/DigestComposer.java`
- Test: `backend/src/test/java/com/simonrowe/agents/DigestComposerTest.java`

**Interfaces:**
- Consumes: `DigestSection` from Task 3, `aggregation.digest.model` from Task 2.
- Produces: `DigestComposer.compose(List<DigestSection> sections)` → `String` of Markdown.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/simonrowe/agents/DigestComposerTest.java`:

```java
package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DigestComposerTest {

  private static final String MODEL = "gpt-5.6-luna";

  private static final DigestSection SECTION_ONE = new DigestSection(
      "art-1", "Spring Boot 4 Released", "https://infoq.com/spring-boot-4",
      "Body about Spring Boot.", false);
  private static final DigestSection SECTION_TWO = new DigestSection(
      "art-2", "Postgres 19 Ships", "https://pg.org/pg19",
      "Body about Postgres.", false);

  @Mock private Ai ai;

  private PromptRunner promptRunner;
  private AssistantMessage assistantMessage;
  private DigestComposer composer;

  @BeforeEach
  void setUp() {
    promptRunner = mock(PromptRunner.class);
    assistantMessage = mock(AssistantMessage.class);
    lenient().when(ai.withLlm(MODEL)).thenReturn(promptRunner);
    lenient().when(promptRunner.respond(anyList())).thenReturn(assistantMessage);
    composer = new DigestComposer(ai, MODEL);
  }

  @Test
  void usesSynthesisWhenEveryUrlSurvives() {
    when(assistantMessage.getContent()).thenReturn("""
        A flowing intro.

        ## [Spring Boot 4 Released](https://infoq.com/spring-boot-4)
        Rewritten prose about Spring Boot.

        ## [Postgres 19 Ships](https://pg.org/pg19)
        Rewritten prose about Postgres.
        """);

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("Rewritten prose about Spring Boot.");
    assertThat(result).contains("https://infoq.com/spring-boot-4");
    assertThat(result).contains("https://pg.org/pg19");
  }

  @Test
  void fallsBackToAssembledDocumentWhenSynthesisDropsAUrl() {
    when(assistantMessage.getContent()).thenReturn("""
        A flowing intro.

        ## Spring Boot 4 Released
        Rewritten prose, but the link is gone.

        ## [Postgres 19 Ships](https://pg.org/pg19)
        Rewritten prose about Postgres.
        """);

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("[Spring Boot 4 Released](https://infoq.com/spring-boot-4)");
    assertThat(result).contains("Body about Spring Boot.");
    assertThat(result).doesNotContain("Rewritten prose");
  }

  @Test
  void fallsBackToAssembledDocumentWhenSynthesisThrows() {
    when(promptRunner.respond(anyList()))
        .thenThrow(new RuntimeException("upstream 500"));

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("[Spring Boot 4 Released](https://infoq.com/spring-boot-4)");
    assertThat(result).contains("[Postgres 19 Ships](https://pg.org/pg19)");
    assertThat(result).contains("Body about Postgres.");
  }

  @Test
  void fallsBackToAssembledDocumentWhenSynthesisIsBlank() {
    when(assistantMessage.getContent()).thenReturn("   ");

    String result = composer.compose(List.of(SECTION_ONE, SECTION_TWO));

    assertThat(result).contains("[Postgres 19 Ships](https://pg.org/pg19)");
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.DigestComposerTest"`
Expected: FAIL — `DigestComposer` does not exist.

- [ ] **Step 3: Create the DigestComposer**

Create `backend/src/main/java/com/simonrowe/agents/DigestComposer.java`:

```java
package com.simonrowe.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a list of per-article sections into the finished digest body.
 *
 * <p>The deterministic document is built first and always exists. A synthesis
 * call then tries to rewrite it into one flowing piece, and its output is used
 * only if every source URL survived verbatim — rewriting a whole document is
 * where a model silently mangles links, and this makes that failure mode
 * non-publishing rather than reader-visible.
 */
@Component
public class DigestComposer {

  private static final Logger LOG =
      LoggerFactory.getLogger(DigestComposer.class);

  private static final String SYNTHESIS_PROMPT = """
      Below is a draft digest post by Simon Rowe, assembled from one section \
      per article he saved this week.

      Rewrite it as a single flowing piece in his first-person voice. Add a \
      short 2-3 sentence intro at the top. Keep exactly one section per \
      article, in the same order.

      Rules you must not break:
      - Reproduce every Markdown link EXACTLY as written, including the URL.
      - Keep every "## [Title](url)" heading exactly as given.
      - Do not add a top-level title heading.
      - Do not invent articles, links or facts.

      Draft:
      %s
      """;

  private final Ai ai;
  private final String model;

  public DigestComposer(
      final Ai ai,
      @Value("${aggregation.digest.model}") final String model) {
    this.ai = ai;
    this.model = model;
  }

  /**
   * Composes the digest body.
   *
   * @param sections one section per favourited article, in publication order
   * @return Markdown for the post body, with no top-level title heading
   */
  public String compose(final List<DigestSection> sections) {
    String assembled = assemble(sections);
    String synthesised = synthesise(assembled);
    if (synthesised == null || !preservesEveryUrl(synthesised, sections)) {
      LOG.warn("Synthesis pass rejected for {} sections; "
          + "publishing the assembled document", sections.size());
      return assembled;
    }
    return synthesised;
  }

  private static String assemble(final List<DigestSection> sections) {
    StringBuilder sb = new StringBuilder();
    for (DigestSection section : sections) {
      sb.append("## [").append(section.title())
          .append("](").append(section.url()).append(")\n\n")
          .append(section.body()).append("\n\n");
    }
    return sb.toString().trim();
  }

  private String synthesise(final String assembled) {
    try {
      String content = ai.withLlm(model)
          .respond(List.of(
              new UserMessage(String.format(SYNTHESIS_PROMPT, assembled))))
          .getContent();
      return content == null || content.isBlank() ? null : content;
    } catch (Exception e) {
      LOG.warn("Digest synthesis call failed: {}", e.getMessage());
      return null;
    }
  }

  private static boolean preservesEveryUrl(
      final String synthesised, final List<DigestSection> sections) {
    return sections.stream()
        .allMatch(section -> synthesised.contains(section.url()));
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.DigestComposerTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/agents/DigestComposer.java \
        backend/src/test/java/com/simonrowe/agents/DigestComposerTest.java
git commit -m "feat: add digest composer with link-preserving synthesis"
```

---

### Task 5: Simplify DigestMetadataGenerator

The digest no longer covers Simon's own blog posts, so `recentBlogs` is always empty. Leaving a parameter that is always `List.of()` invites someone to assume it means something.

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/agents/DigestMetadataGenerator.java`

**Interfaces:**
- Consumes: `aggregation.digest.model` from Task 2.
- Produces: `DigestMetadataGenerator.generate(List<AggregatedArticle> articles, String activitySummary)` → `DigestMetadata`. Task 6 calls this.

- [ ] **Step 1: Change the signature and drop the blog fallback**

In `backend/src/main/java/com/simonrowe/agents/DigestMetadataGenerator.java`:

Remove the `import com.simonrowe.blog.Blog;` line.

Replace the constructor with one that injects the model:

```java
  private final Ai ai;
  private final String model;

  public DigestMetadataGenerator(
      final Ai ai,
      @Value("${aggregation.digest.model}") final String model) {
    this.ai = ai;
    this.model = model;
  }
```

Add `import org.springframework.beans.factory.annotation.Value;`.

Replace the `generate` method with:

```java
  public DigestMetadata generate(
      final List<AggregatedArticle> articles,
      final String activitySummary) {
    try {
      String content = ai.withLlm(model)
          .respond(List.of(new UserMessage(METADATA_PROMPT + activitySummary)))
          .getContent();
      DigestMetadata parsed = parse(content);
      if (isUsable(parsed)) {
        return parsed;
      }
    } catch (Exception ex) {
      LOG.warn("Failed to generate digest metadata: {}", ex.getMessage());
    }
    return fallback(articles);
  }
```

Replace the `fallback` method with:

```java
  private static DigestMetadata fallback(
      final List<AggregatedArticle> articles) {
    String lead = articles.stream()
        .findFirst()
        .map(AggregatedArticle::title)
        .orElse("AI and backend engineering");
    String title = truncate("What caught my eye: " + lead, MAX_TITLE_LENGTH);
    String description = truncate(
        "A few practical notes on " + lead + " and related engineering signals.",
        MAX_DESCRIPTION_LENGTH);
    return new DigestMetadata(title, description);
  }
```

- [ ] **Step 2: Verify it compiles**

Run: `cd backend && ../gradlew compileJava`
Expected: FAIL — `WeeklyDigestAgent` still calls the three-argument `generate`. That call site is fixed in Task 6; this failure is expected and is why Tasks 5 and 6 are committed together.

- [ ] **Step 3: Do not commit yet**

Continue straight to Task 6. The build is intentionally red between these two tasks.

---

### Task 6: Rewrite WeeklyDigestAgent against favourites

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/favourites/FavouriteRepository.java`
- Modify: `backend/src/main/java/com/simonrowe/agents/WeeklyDigestAgent.java`
- Modify: `backend/src/test/java/com/simonrowe/agents/WeeklyDigestAgentTest.java` (rewrite)
- Modify: `docs/model-usage.md`

**Interfaces:**
- Consumes: `DigestSection` and `ArticleSectionWriter.write(...)` from Task 3, `DigestComposer.compose(...)` from Task 4, `DigestMetadataGenerator.generate(List<AggregatedArticle>, String)` from Task 5.
- Produces: the finished `generateDigest()` behaviour. Nothing downstream depends on it.

- [ ] **Step 1: Add the favourites query**

In `backend/src/main/java/com/simonrowe/favourites/FavouriteRepository.java`, add:

```java
  List<Favourite> findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
      FavouriteType type, Instant createdAt);
```

Add `import java.time.Instant;`.

This is covered by the `idx_type_created` index created in `V014MakeFavouritesGlobal`, so no new Mongock change unit is needed.

- [ ] **Step 2: Write the failing test**

Replace the whole of `backend/src/test/java/com/simonrowe/agents/WeeklyDigestAgentTest.java` with:

```java
package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Tag;
import com.simonrowe.blog.TagRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import com.simonrowe.media.BlogImageGenerationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeeklyDigestAgentTest {

  private static final Tag DIGEST_TAG = new Tag("tag-1", "Weekly Digest");
  private static final int WINDOW_DAYS = 7;

  @Mock private BlogRepository blogRepository;
  @Mock private TagRepository tagRepository;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private FavouriteRepository favouriteRepository;
  @Mock private ArticleSectionWriter sectionWriter;
  @Mock private DigestComposer composer;
  @Mock private DigestMetadataGenerator metadataGenerator;
  @Mock private ContentChangePublisher changePublisher;
  @Mock private BlogImageGenerationService blogImageGenerationService;

  private WeeklyDigestAgent agent;

  private static AggregatedArticle article(final String id, final String title) {
    return new AggregatedArticle(
        id, title, "InfoQ", "https://infoq.com",
        "https://infoq.com/" + id, "Stored summary.", "Full content.",
        "Jane Doe", Instant.now(), Instant.now(), true, null);
  }

  private static Favourite favourite(final String contentId, final int daysAgo) {
    return new Favourite(
        "fav-" + contentId, FavouriteType.NEWS, contentId,
        Instant.now().minus(daysAgo, ChronoUnit.DAYS));
  }

  @BeforeEach
  void setUp() {
    lenient().when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    agent = new WeeklyDigestAgent(
        blogRepository, tagRepository, articleRepository, favouriteRepository,
        sectionWriter, composer, metadataGenerator, changePublisher,
        blogImageGenerationService, WINDOW_DAYS);
  }

  @Test
  void publishesNothingWhenNoFavouritesInWindow() {
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of());

    agent.generateDigest();

    verify(blogRepository, never()).save(any());
    verify(changePublisher, never()).publishCreated(any(), any());
  }

  @Test
  void skipsFavouriteWhoseArticleNoLongerExists() {
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of(favourite("gone", 1)));
    when(articleRepository.findById("gone")).thenReturn(Optional.empty());

    agent.generateDigest();

    verify(sectionWriter, never()).write(any());
    verify(blogRepository, never()).save(any());
  }

  @Test
  void skipsFavouriteWhoseArticleIsHidden() {
    AggregatedArticle hidden = new AggregatedArticle(
        "hid", "Hidden", "InfoQ", "https://infoq.com",
        "https://infoq.com/hid", "Summary.", "Content.", null,
        Instant.now(), Instant.now(), false, null);
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of(favourite("hid", 1)));
    when(articleRepository.findById("hid")).thenReturn(Optional.of(hidden));

    agent.generateDigest();

    verify(sectionWriter, never()).write(any());
    verify(blogRepository, never()).save(any());
  }

  @Test
  void queriesUsingTheConfiguredWindow() {
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of());

    agent.generateDigest();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(favouriteRepository)
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), cutoff.capture());
    Instant expected = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
    assertThat(cutoff.getValue())
        .isBetween(expected.minusSeconds(60), expected.plusSeconds(60));
  }

  @Test
  void publishesNothingWhenEverySectionIsAFallback() {
    AggregatedArticle art = article("art-1", "Spring Boot 4");
    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of(favourite("art-1", 1)));
    when(articleRepository.findById("art-1")).thenReturn(Optional.of(art));
    when(sectionWriter.write(art)).thenReturn(new DigestSection(
        "art-1", "Spring Boot 4", "https://infoq.com/art-1",
        "Stored summary.", true));

    agent.generateDigest();

    verify(composer, never()).compose(anyList());
    verify(blogRepository, never()).save(any());
  }

  @Test
  void publishesDigestFromFavouritedArticles() {
    AggregatedArticle art = article("art-1", "Spring Boot 4");
    DigestSection section = new DigestSection(
        "art-1", "Spring Boot 4", "https://infoq.com/art-1",
        "Real prose.", false);

    when(favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            eq(FavouriteType.NEWS), any()))
        .thenReturn(List.of(favourite("art-1", 2)));
    when(articleRepository.findById("art-1")).thenReturn(Optional.of(art));
    when(sectionWriter.write(art)).thenReturn(section);
    when(composer.compose(List.of(section)))
        .thenReturn("## [Spring Boot 4](https://infoq.com/art-1)\n\nReal prose.");
    when(metadataGenerator.generate(anyList(), anyString()))
        .thenReturn(new DigestMetadata("What caught my eye", "A short description"));
    when(blogImageGenerationService
        .generateAndStore(anyString(), anyString(), anyString()))
        .thenReturn("/uploads/digest.png");
    when(blogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    agent.generateDigest();

    ArgumentCaptor<Blog> saved = ArgumentCaptor.forClass(Blog.class);
    verify(blogRepository).save(saved.capture());
    Blog digest = saved.getValue();
    assertThat(digest.contentType()).isEqualTo(BlogContentType.DIGEST);
    assertThat(digest.title()).isEqualTo("What caught my eye");
    assertThat(digest.content()).contains("https://infoq.com/art-1");
    assertThat(digest.tags()).containsExactly(DIGEST_TAG);
    assertThat(digest.published()).isTrue();
    verify(changePublisher).publishCreated(eq(ContentType.BLOG), any());
  }
}
```

A note on `queriesUsingTheConfiguredWindow`: the spec asks for a boundary test where a favourite hearted 8 days ago is excluded and one hearted 6 days ago is included. With a mocked repository that test would assert nothing — the derived query does the filtering inside MongoDB, so the mock would return whatever it was told to. Asserting the cutoff `Instant` the agent passes is the real equivalent, and it is what catches an off-by-one in the window arithmetic.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.WeeklyDigestAgentTest"`
Expected: FAIL — `WeeklyDigestAgent` has no such constructor.

- [ ] **Step 4: Rewrite the agent**

Replace `backend/src/main/java/com/simonrowe/agents/WeeklyDigestAgent.java` with:

```java
package com.simonrowe.agents;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Skill;
import com.simonrowe.blog.Tag;
import com.simonrowe.blog.TagRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.favourites.Favourite;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.favourites.FavouriteType;
import com.simonrowe.media.BlogImageGenerationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * Builds the weekly digest from the news articles favourited in the last
 * window, one section per article.
 *
 * <p>The window is fixed rather than measured from the last digest, so a
 * skipped run loses that week's items rather than rolling them forward. That
 * is deliberate: it keeps the job stateless.
 */
@Agent(
    name = "WeeklyDigest",
    description = "Generates a weekly digest blog post summarising the news "
        + "articles favourited over the past week"
)
public class WeeklyDigestAgent {

  private static final Logger log =
      LoggerFactory.getLogger(WeeklyDigestAgent.class);

  private final BlogRepository blogRepository;
  private final TagRepository tagRepository;
  private final AggregatedArticleRepository articleRepository;
  private final FavouriteRepository favouriteRepository;
  private final ArticleSectionWriter sectionWriter;
  private final DigestComposer composer;
  private final DigestMetadataGenerator metadataGenerator;
  private final ContentChangePublisher changePublisher;
  private final BlogImageGenerationService blogImageGenerationService;
  private final int windowDays;

  public WeeklyDigestAgent(
      final BlogRepository blogRepository,
      final TagRepository tagRepository,
      final AggregatedArticleRepository articleRepository,
      final FavouriteRepository favouriteRepository,
      final ArticleSectionWriter sectionWriter,
      final DigestComposer composer,
      final DigestMetadataGenerator metadataGenerator,
      final ContentChangePublisher changePublisher,
      final BlogImageGenerationService blogImageGenerationService,
      @Value("${aggregation.digest.window-days}") final int windowDays) {
    this.blogRepository = blogRepository;
    this.tagRepository = tagRepository;
    this.articleRepository = articleRepository;
    this.favouriteRepository = favouriteRepository;
    this.sectionWriter = sectionWriter;
    this.composer = composer;
    this.metadataGenerator = metadataGenerator;
    this.changePublisher = changePublisher;
    this.blogImageGenerationService = blogImageGenerationService;
    this.windowDays = windowDays;
  }

  /** Generates and publishes the digest, or logs why it did not. */
  @Action(description = "Generate a digest blog post")
  public void generateDigest() {
    List<AggregatedArticle> articles = favouritedArticles();
    if (articles.isEmpty()) {
      log.info("No news favourited in the last {} days, "
          + "skipping digest generation", windowDays);
      return;
    }

    List<DigestSection> sections = articles.stream()
        .map(sectionWriter::write)
        .toList();

    if (sections.stream().allMatch(DigestSection::fallback)) {
      log.error("Every section fell back to its stored summary — "
          + "assuming an LLM outage and publishing nothing");
      return;
    }

    String content = composer.compose(sections);
    String activitySummary = buildActivitySummary(sections);
    DigestMetadata metadata =
        metadataGenerator.generate(articles, activitySummary);
    String featuredImageUrl = blogImageGenerationService.generateAndStore(
        metadata.title(), metadata.shortDescription(),
        buildImageContext(articles));

    Tag digestTag = getOrCreateDigestTag();
    Instant createdAt = Instant.now();
    Blog digest = new Blog(
        null, metadata.title(), metadata.shortDescription(),
        content, true, featuredImageUrl, createdAt, createdAt,
        List.of(digestTag), List.<Skill>of(), BlogContentType.DIGEST);

    Blog saved = blogRepository.save(digest);
    changePublisher.publishCreated(ContentType.BLOG, saved.id());
    log.info("Published digest '{}' covering {} favourited articles",
        metadata.title(), articles.size());
  }

  private List<AggregatedArticle> favouritedArticles() {
    Instant cutoff = Instant.now().minus(windowDays, ChronoUnit.DAYS);
    List<Favourite> favourites = favouriteRepository
        .findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
            FavouriteType.NEWS, cutoff);
    return favourites.stream()
        .map(this::resolveArticle)
        .flatMap(Optional::stream)
        .toList();
  }

  private Optional<AggregatedArticle> resolveArticle(
      final Favourite favourite) {
    Optional<AggregatedArticle> article =
        articleRepository.findById(favourite.contentId());
    if (article.isEmpty()) {
      log.warn("Favourite {} points at missing article {}, skipping",
          favourite.id(), favourite.contentId());
      return Optional.empty();
    }
    if (!article.get().visible()) {
      log.info("Skipping favourited article '{}' — it is hidden",
          article.get().title());
      return Optional.empty();
    }
    return article;
  }

  private static String buildActivitySummary(
      final List<DigestSection> sections) {
    StringBuilder sb = new StringBuilder("## Favourited This Week\n");
    for (DigestSection section : sections) {
      sb.append("- [").append(section.title())
          .append("](").append(section.url()).append(")\n");
    }
    return sb.toString();
  }

  private static String buildImageContext(
      final List<AggregatedArticle> articles) {
    StringBuilder sb = new StringBuilder("Favourited articles: ");
    articles.stream().limit(8).forEach(article -> sb.append(article.title())
        .append(" from ").append(article.sourceName())
        .append(" - ").append(article.summary()).append("; "));
    return sb.toString();
  }

  private Tag getOrCreateDigestTag() {
    return tagRepository.findByName("Weekly Digest")
        .orElseGet(() -> tagRepository.save(new Tag(null, "Weekly Digest")));
  }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.WeeklyDigestAgentTest"`
Expected: PASS, 6 tests.

- [ ] **Step 6: Run the whole suite**

Run: `cd backend && ../gradlew test`
Expected: BUILD SUCCESSFUL. `AdminAggregationController` calls `digestAgent::generateDigest`, whose signature is unchanged, so nothing else should need touching.

- [ ] **Step 7: Update the model audit**

In `docs/model-usage.md`, delete this paragraph, which is no longer true:

```markdown
Rows 4 and 5 describe the state after the favourites-digest change
(`docs/superpowers/specs/2026-08-08-favourites-digest-design.md`); both call
sites are on `gpt-4o-mini` until it ships.
```

Update the "Framework versions" table row for Embabel so the "Here" column reads `1.0.0`.

- [ ] **Step 8: Run checkstyle**

Run: `cd backend && ../gradlew checkstyleMain checkstyleTest`
Expected: BUILD SUCCESSFUL. Fix any violations before committing — the pre-commit hook will reject them otherwise.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/simonrowe/agents/WeeklyDigestAgent.java \
        backend/src/main/java/com/simonrowe/agents/DigestMetadataGenerator.java \
        backend/src/main/java/com/simonrowe/favourites/FavouriteRepository.java \
        backend/src/test/java/com/simonrowe/agents/WeeklyDigestAgentTest.java \
        docs/model-usage.md
git commit -m "feat: build the weekly digest from favourited news only"
```

---

## Verification

After Task 6, before considering this done:

- [ ] `cd backend && ../gradlew test` passes in full.
- [ ] `cd backend && ../gradlew checkstyleMain checkstyleTest` passes.
- [ ] Favourite two or three news articles locally, trigger the digest from the admin Data Ops UI, and read the generated post. Check: one section per favourite, every link resolves to the right article, the "why this caught my eye" line reads as prose rather than boilerplate, and no top-level title heading is duplicated above the post title.
- [ ] Check the logs for `Synthesis pass rejected` — if it fires on a normal run, the synthesis prompt needs tightening rather than the validation loosening.
