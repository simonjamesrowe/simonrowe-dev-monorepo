# Dan Vega Blog Content Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Dan Vega's blog (`https://www.danvega.dev/blog`) as an `HTML_LISTING` content source, seeded and pre-populated with the last 30 days of posts via a Mongock change unit.

**Architecture:** Reuses the existing aggregation pipeline (`ScraperFactory` → `ContentAggregationAgent` → Kafka → Elasticsearch). Two small generic fixes to `SitemapHtmlScraper.isArticleLink()`, one new `backfillSource` method on `ContentAggregationAgent`, and a new Mongock change unit that seeds the source and runs a guarded backfill. No new strategy, entity, controller, or frontend code.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Data MongoDB, Mongock, Jsoup, Embabel (`@Agent`), JUnit 5 + Mockito + AssertJ, Gradle.

**Reference spec:** `docs/superpowers/specs/2026-07-23-dan-vega-content-source-design.md`

---

## File Structure

- **Modify** `backend/src/main/java/com/simonrowe/agents/scrapers/SitemapHtmlScraper.java` — add `tags` + numeric-pagination exclusions to `isArticleLink()` (lines ~202-209).
- **Modify** `backend/src/test/java/com/simonrowe/agents/scrapers/SitemapHtmlScraperTest.java` — tests for the new exclusions + Dan Vega acceptance.
- **Modify** `backend/src/main/java/com/simonrowe/agents/ContentAggregationAgent.java` — extract a shared `processScrapedItem` helper; add public `backfillSource(ContentSource, Instant)`.
- **Modify** `backend/src/test/java/com/simonrowe/agents/ContentAggregationAgentTest.java` — tests for `backfillSource` date filtering.
- **Create** `backend/src/main/java/com/simonrowe/migration/changeunits/V011SeedAndBackfillDanVegaBlog.java` — Mongock change unit.
- **Create** `backend/src/test/java/com/simonrowe/migration/changeunits/V011SeedAndBackfillDanVegaBlogTest.java` — change-unit tests.
- **Modify** `scripts/seed-content-sources.js` — add the Dan Vega source for local/dev seeding.

### Key facts locked from the codebase

- `ContentSource(id, name, baseUrl, feedUrl, sitemapUrl, sourceType, scrapeStrategy, active, lastFetchedAt, lastError)`.
- `ScrapedContent(title, url, content, publishedDate, author, imageUrl, isEvent)` (7-arg convenience ctor).
- `ContentClassification(type, summary, eventDate, venue, location, publishedDate)`; `isEvent()` returns true when `type == "event"`.
- `@ChangeUnit(id=..., order=..., author=...)` with `@Execution` / `@RollbackExecution`; Mongock scans `com.simonrowe.migration.changeunits`. `@Execution` methods can inject any Spring bean (`ContentSourceRepository`, `ContentAggregationAgent` — `@Agent` is meta-annotated `@Component`).
- `isArticleLink(String href, String listingUrl)` is package-private (testable from the same package).
- Run all backend tests: `cd backend && ../gradlew test`. Run one class: `cd backend && ../gradlew test --tests "com.simonrowe.<FQCN>"`.

---

## Task 1: Exclude `tags` and numeric pagination in `isArticleLink()`

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/agents/scrapers/SitemapHtmlScraper.java:202-209`
- Test: `backend/src/test/java/com/simonrowe/agents/scrapers/SitemapHtmlScraperTest.java`

- [ ] **Step 1: Write the failing tests**

Append these methods to `SitemapHtmlScraperTest` (before the final closing brace):

```java
  // ---------------------------------------------------------------------------
  // isArticleLink — package-private section/utility filter
  // ---------------------------------------------------------------------------

  private static final String DAN_VEGA_LISTING = "https://www.danvega.dev/blog";

  @Test
  void isArticleLink_acceptsDanVegaBlogPost() {
    assertThat(scraper.isArticleLink(
        "https://www.danvega.dev/blog/embabel-1-0-ga", DAN_VEGA_LISTING))
        .isTrue();
  }

  @Test
  void isArticleLink_rejectsTagsIndex() {
    assertThat(scraper.isArticleLink(
        "https://www.danvega.dev/blog/tags", DAN_VEGA_LISTING))
        .isFalse();
  }

  @Test
  void isArticleLink_rejectsNumericPagination() {
    assertThat(scraper.isArticleLink(
        "https://www.danvega.dev/blog/2", DAN_VEGA_LISTING))
        .isFalse();
  }

  @Test
  void isArticleLink_stillAcceptsWordSlugOnOtherHosts() {
    assertThat(scraper.isArticleLink(
        "https://claude.com/blog/some-post", "https://claude.com/blog"))
        .isTrue();
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.scrapers.SitemapHtmlScraperTest"`
Expected: `isArticleLink_rejectsTagsIndex` and `isArticleLink_rejectsNumericPagination` FAIL (both currently return `true`); the two acceptance tests PASS.

- [ ] **Step 3: Add the exclusions**

In `SitemapHtmlScraper.java`, find this block (around lines 202-209):

```java
      String lastSegment = segments[segments.length - 1];
      if (lastSegment.isEmpty() && segments.length > 1) {
        lastSegment = segments[segments.length - 2];
      }
      if (lastSegment.equals("category") || lastSegment.equals("tag")
          || lastSegment.equals("author") || lastSegment.equals("page")) {
        return false;
      }
```

Replace it with:

```java
      String lastSegment = segments[segments.length - 1];
      if (lastSegment.isEmpty() && segments.length > 1) {
        lastSegment = segments[segments.length - 2];
      }
      if (lastSegment.equals("category") || lastSegment.equals("tag")
          || lastSegment.equals("tags") || lastSegment.equals("author")
          || lastSegment.equals("page")) {
        return false;
      }
      // Reject path-style pagination such as /blog/2 or /news/3
      if (lastSegment.matches("\\d+")) {
        return false;
      }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.scrapers.SitemapHtmlScraperTest"`
Expected: PASS (all methods, including the pre-existing date-extraction tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/agents/scrapers/SitemapHtmlScraper.java \
        backend/src/test/java/com/simonrowe/agents/scrapers/SitemapHtmlScraperTest.java
git commit -m "fix: exclude tags index and numeric pagination from HTML_LISTING article links"
```

---

## Task 2: Add `backfillSource` to `ContentAggregationAgent`

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/agents/ContentAggregationAgent.java:183-206`
- Test: `backend/src/test/java/com/simonrowe/agents/ContentAggregationAgentTest.java`

- [ ] **Step 1: Write the failing tests**

Add these imports near the top of `ContentAggregationAgentTest.java` (with the other imports):

```java
import java.time.temporal.ChronoUnit;
```

Add this shared source constant next to `ACTIVE_SOURCE`:

```java
  private static final ContentSource DAN_VEGA_SOURCE =
      new ContentSource(
          "src-dv", "Dan Vega", "https://www.danvega.dev/blog",
          null, null, SourceType.BLOG, ScrapeStrategy.HTML_LISTING,
          true, null, null);
```

Append these test methods (before the final closing brace):

```java
  @Test
  void backfillSource_savesRecentPostAndSkipsPostBeforeCutoff() {
    Instant now = Instant.now();
    Instant since = now.minus(30, ChronoUnit.DAYS);

    ScrapedContent recent = new ScrapedContent(
        "Recent Post", "https://www.danvega.dev/blog/recent",
        "This is a long enough content string to pass the "
            + "fifty character threshold for classification.",
        now.minus(5, ChronoUnit.DAYS), "Dan Vega", null, false);
    ScrapedContent old = new ScrapedContent(
        "Old Post", "https://www.danvega.dev/blog/old",
        "This is a long enough content string to pass the "
            + "fifty character threshold for classification.",
        now.minus(100, ChronoUnit.DAYS), "Dan Vega", null, false);

    ContentClassification articleClassification =
        new ContentClassification(
            "article", "A concise summary.", null, null, null, null);

    when(scraperFactory.scrape(DAN_VEGA_SOURCE))
        .thenReturn(List.of(recent, old));
    when(articleRepository.existsByOriginalUrl(anyString()))
        .thenReturn(false);
    when(eventRepository.existsByOriginalUrl(anyString()))
        .thenReturn(false);
    when(creating.fromPrompt(anyString()))
        .thenReturn(articleClassification);
    lenient().when(imageDownloader.downloadAndStore(any()))
        .thenReturn(null);
    lenient().when(blogImageGenerationService.generateAndStore(
        anyString(), anyString())).thenReturn(null);
    when(articleRepository.save(any()))
        .thenAnswer(invocation -> {
          AggregatedArticle a = invocation.getArgument(0);
          return new AggregatedArticle(
              "art-recent", a.title(), a.sourceName(), a.sourceUrl(),
              a.originalUrl(), a.summary(), a.fullContent(), a.author(),
              a.publishedDate(), a.fetchedAt(), a.visible(), a.imageUrl());
        });

    agent.backfillSource(DAN_VEGA_SOURCE, since);

    ArgumentCaptor<AggregatedArticle> captor =
        ArgumentCaptor.forClass(AggregatedArticle.class);
    verify(articleRepository).save(captor.capture());
    assertThat(captor.getValue().title()).isEqualTo("Recent Post");
    verify(changePublisher).publishCreated(
        ContentType.AGGREGATED_ARTICLE, "art-recent");
  }

  @Test
  void backfillSource_processesDatelessPost() {
    Instant since = Instant.now().minus(30, ChronoUnit.DAYS);

    ScrapedContent dateless = new ScrapedContent(
        "Dateless Post", "https://www.danvega.dev/blog/dateless",
        "This is a long enough content string to pass the "
            + "fifty character threshold for classification.",
        null, "Dan Vega", null, false);

    when(scraperFactory.scrape(DAN_VEGA_SOURCE))
        .thenReturn(List.of(dateless));
    when(articleRepository.existsByOriginalUrl(anyString()))
        .thenReturn(false);
    when(eventRepository.existsByOriginalUrl(anyString()))
        .thenReturn(false);
    when(creating.fromPrompt(anyString()))
        .thenReturn(new ContentClassification(
            "article", "Summary.", null, null, null, null));
    lenient().when(imageDownloader.downloadAndStore(any()))
        .thenReturn(null);
    lenient().when(blogImageGenerationService.generateAndStore(
        anyString(), anyString())).thenReturn(null);
    when(htmlScraper.extractPublishedDateFromUrl(anyString()))
        .thenReturn(null);
    when(articleRepository.save(any()))
        .thenAnswer(invocation -> {
          AggregatedArticle a = invocation.getArgument(0);
          return new AggregatedArticle(
              "art-dateless", a.title(), a.sourceName(), a.sourceUrl(),
              a.originalUrl(), a.summary(), a.fullContent(), a.author(),
              a.publishedDate(), a.fetchedAt(), a.visible(), a.imageUrl());
        });

    agent.backfillSource(DAN_VEGA_SOURCE, since);

    verify(articleRepository).save(any());
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.ContentAggregationAgentTest"`
Expected: compile FAIL — `backfillSource` does not exist yet.

- [ ] **Step 3: Refactor `processSource` and add `backfillSource`**

In `ContentAggregationAgent.java`, replace the entire existing `processSource` method (lines 183-206):

```java
  private void processSource(final ContentSource source) {
    List<ScrapedContent> scraped = scraperFactory.scrape(source);
    log.info("Fetched {} items from {}", scraped.size(), source.name());

    for (ScrapedContent content : scraped) {
      boolean alreadyExists =
          articleRepository.existsByOriginalUrl(content.url())
              || eventRepository.existsByOriginalUrl(content.url());
      if (alreadyExists) {
        continue;
      }

      ContentClassification classification =
          classifyAndSummarize(content);

      if (classification.isEvent()
          || source.sourceType() == ContentSource.SourceType.EVENTS
          || content.isEvent()) {
        processEvent(source, content, classification);
      } else {
        processArticle(source, content, classification);
      }
    }
  }
```

with:

```java
  private void processSource(final ContentSource source) {
    List<ScrapedContent> scraped = scraperFactory.scrape(source);
    log.info("Fetched {} items from {}", scraped.size(), source.name());

    for (ScrapedContent content : scraped) {
      processScrapedItem(source, content);
    }
  }

  /**
   * Scrapes a source and processes only items published on or after {@code since}.
   * Used to pre-populate a newly added source (e.g. from a Mongock change unit)
   * without waiting for the scheduled aggregation run. Items whose scraped
   * {@code publishedDate} is absent are processed normally (they fall back to the
   * fetch date and are treated as recent).
   *
   * @param source the content source to scrape
   * @param since  the earliest publish date to keep
   */
  public void backfillSource(final ContentSource source, final Instant since) {
    List<ScrapedContent> scraped = scraperFactory.scrape(source);
    log.info("Backfilling {} items from {} published on/after {}",
        scraped.size(), source.name(), since);

    for (ScrapedContent content : scraped) {
      if (content.publishedDate() != null
          && content.publishedDate().isBefore(since)) {
        log.info("Skipping '{}' — published {} is before cutoff {}",
            content.title(), content.publishedDate(), since);
        continue;
      }
      processScrapedItem(source, content);
    }
  }

  private void processScrapedItem(
      final ContentSource source, final ScrapedContent content) {
    boolean alreadyExists =
        articleRepository.existsByOriginalUrl(content.url())
            || eventRepository.existsByOriginalUrl(content.url());
    if (alreadyExists) {
      return;
    }

    ContentClassification classification =
        classifyAndSummarize(content);

    if (classification.isEvent()
        || source.sourceType() == ContentSource.SourceType.EVENTS
        || content.isEvent()) {
      processEvent(source, content, classification);
    } else {
      processArticle(source, content, classification);
    }
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.agents.ContentAggregationAgentTest"`
Expected: PASS (new `backfillSource` tests plus all pre-existing `runAggregation` tests — behavior is unchanged because `processSource` now delegates to `processScrapedItem`).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/agents/ContentAggregationAgent.java \
        backend/src/test/java/com/simonrowe/agents/ContentAggregationAgentTest.java
git commit -m "feat: add backfillSource to aggregate a source's recent posts on demand"
```

---

## Task 3: Mongock change unit to seed + backfill Dan Vega

**Files:**
- Create: `backend/src/main/java/com/simonrowe/migration/changeunits/V011SeedAndBackfillDanVegaBlog.java`
- Test: `backend/src/test/java/com/simonrowe/migration/changeunits/V011SeedAndBackfillDanVegaBlogTest.java`

- [ ] **Step 1: Write the failing tests**

Create `V011SeedAndBackfillDanVegaBlogTest.java`:

```java
package com.simonrowe.migration.changeunits;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V011SeedAndBackfillDanVegaBlogTest {

  @Mock private ContentSourceRepository sourceRepository;
  @Mock private ContentAggregationAgent aggregationAgent;

  private final V011SeedAndBackfillDanVegaBlog changeUnit =
      new V011SeedAndBackfillDanVegaBlog();

  @Test
  void seedsSourceAndBackfillsWhenAbsent() {
    when(sourceRepository.findByName("Dan Vega"))
        .thenReturn(Optional.empty());
    ContentSource saved = new ContentSource(
        "dv1", "Dan Vega", "https://www.danvega.dev/blog", null, null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.HTML_LISTING, true, null, null);
    when(sourceRepository.save(any())).thenReturn(saved);

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<ContentSource> captor =
        ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().name())
        .isEqualTo("Dan Vega");
    org.assertj.core.api.Assertions.assertThat(
        captor.getValue().scrapeStrategy())
        .isEqualTo(ContentSource.ScrapeStrategy.HTML_LISTING);
    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }

  @Test
  void doesNotReseedWhenSourceAlreadyExists() {
    ContentSource existing = new ContentSource(
        "dv1", "Dan Vega", "https://www.danvega.dev/blog", null, null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.HTML_LISTING, true, null, null);
    when(sourceRepository.findByName("Dan Vega"))
        .thenReturn(Optional.of(existing));

    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(sourceRepository, never()).save(any());
    verify(aggregationAgent, never()).backfillSource(any(), any());
  }

  @Test
  void doesNotThrowWhenBackfillFails() {
    when(sourceRepository.findByName("Dan Vega"))
        .thenReturn(Optional.empty());
    ContentSource saved = new ContentSource(
        "dv1", "Dan Vega", "https://www.danvega.dev/blog", null, null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.HTML_LISTING, true, null, null);
    when(sourceRepository.save(any())).thenReturn(saved);
    doThrow(new RuntimeException("LLM unavailable"))
        .when(aggregationAgent).backfillSource(any(), any());

    // Must not propagate — a failed backfill must never break app boot.
    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.migration.changeunits.V011SeedAndBackfillDanVegaBlogTest"`
Expected: compile FAIL — `V011SeedAndBackfillDanVegaBlog` does not exist yet.

- [ ] **Step 3: Create the change unit**

Create `V011SeedAndBackfillDanVegaBlog.java`:

```java
package com.simonrowe.migration.changeunits;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds the Dan Vega blog content source and pre-populates the last 30 days of
 * posts so the news feed is not empty until the next scheduled aggregation run.
 *
 * <p>The source is scraped with the {@code HTML_LISTING} strategy (like the Claude
 * Blog source): the {@code /blog} index page's newest posts are fetched full-body.
 * The backfill is wrapped so that any failure (LLM, network, Kafka) is logged but
 * never rethrown — a failed pre-population must not fail the migration or block
 * application boot. The 6-hourly scheduled job backfills any gaps afterwards.
 * Seeding is idempotent ({@code findByName} guard) and article dedup on
 * {@code originalUrl} makes the backfill safe to re-run.
 */
@ChangeUnit(id = "seed-and-backfill-dan-vega-blog", order = "011", author = "simonrowe")
public class V011SeedAndBackfillDanVegaBlog {

  private static final Logger log =
      LoggerFactory.getLogger(V011SeedAndBackfillDanVegaBlog.class);

  private static final long BACKFILL_WINDOW_DAYS = 30;

  @Execution
  public void execution(
      final ContentSourceRepository contentSourceRepository,
      final ContentAggregationAgent aggregationAgent) {
    if (contentSourceRepository.findByName("Dan Vega").isPresent()) {
      log.info("Dan Vega source already present; skipping seed and backfill");
      return;
    }

    ContentSource saved = contentSourceRepository.save(new ContentSource(
        null,
        "Dan Vega",
        "https://www.danvega.dev/blog",
        null,
        null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.HTML_LISTING,
        true,
        null,
        null));
    log.info("Seeded Dan Vega content source");

    Instant since = Instant.now().minus(BACKFILL_WINDOW_DAYS, ChronoUnit.DAYS);
    try {
      aggregationAgent.backfillSource(saved, since);
    } catch (Exception e) {
      // A failed pre-population must never break app boot; the scheduled
      // aggregation job will pick up the source on its next run.
      log.error("Dan Vega backfill failed; leaving source for scheduled run", e);
    }
  }

  @RollbackExecution
  public void rollback(final ContentSourceRepository contentSourceRepository) {
    contentSourceRepository.findByName("Dan Vega")
        .ifPresent(contentSourceRepository::delete);
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.migration.changeunits.V011SeedAndBackfillDanVegaBlogTest"`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/migration/changeunits/V011SeedAndBackfillDanVegaBlog.java \
        backend/src/test/java/com/simonrowe/migration/changeunits/V011SeedAndBackfillDanVegaBlogTest.java
git commit -m "feat: seed Dan Vega blog source and backfill last 30 days via Mongock V011"
```

---

## Task 4: Add Dan Vega to the dev seed script

**Files:**
- Modify: `scripts/seed-content-sources.js`

- [ ] **Step 1: Add the source entry**

In `scripts/seed-content-sources.js`, add this object to the `sources` array (after the `Tessl Events` entry, before the closing `]`):

```javascript
  ,
  {
    name: 'Dan Vega',
    baseUrl: 'https://www.danvega.dev/blog',
    feedUrl: null,
    sitemapUrl: null,
    sourceType: 'BLOG',
    scrapeStrategy: 'HTML_LISTING',
    active: true,
    lastFetchedAt: null,
    lastError: null
  }
```

- [ ] **Step 2: Verify the script parses**

Run: `node --check scripts/seed-content-sources.js`
Expected: no output (exit 0). If `node` is unavailable, visually confirm the array has a comma before the new object and valid JSON-like syntax.

- [ ] **Step 3: Commit**

```bash
git add scripts/seed-content-sources.js
git commit -m "chore: add Dan Vega blog to content-source dev seed script"
```

---

## Task 5: Full build and verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full backend test suite + Checkstyle**

Run: `cd backend && ../gradlew clean build`
Expected: `BUILD SUCCESSFUL`, all tests pass, no Checkstyle violations. If Checkstyle flags line length (140 max) or import order in any new file, fix inline and re-run.

- [ ] **Step 2: Manual end-to-end verification (local, optional but recommended)**

With MongoDB/Kafka/Elasticsearch running and `OPENAI_API_KEY` set, start the backend (`./scripts/start-backend.sh`). On first boot Mongock runs `V011`. Then:

Run: `curl -s "http://localhost:8080/api/news?page=0&size=50" | grep -o '"sourceName":"Dan Vega"' | head`
Expected: one or more matches (Dan Vega articles ingested). If the backfill was skipped due to a transient error, trigger it manually:

Run: `curl -s -X POST "http://localhost:8080/api/admin/aggregation/trigger"` (requires admin auth), then re-check `/api/news`.

- [ ] **Step 3: Final commit (only if Step 1 required fixes)**

```bash
git add -A
git commit -m "chore: fix checkstyle/build issues for Dan Vega content source"
```

---

## Self-Review

**Spec coverage:**
- HTML_LISTING source seeded (name "Dan Vega", baseUrl, BLOG) → Task 3 + Task 4. ✓
- `isArticleLink` `tags`/pagination exclusions → Task 1. ✓
- Guarded synchronous backfill of last 30 days via Mongock → Task 2 (`backfillSource`) + Task 3 (change unit try/catch, 30-day window). ✓
- Tests: scraper filter, backfill date filter, change-unit idempotency + non-throwing backfill → Tasks 1-3. ✓
- No new strategy/entity/controller/frontend; downstream automatic → nothing to implement (verified in Task 5). ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. ✓

**Type consistency:** `backfillSource(ContentSource, Instant)` signature matches between Task 2 (definition), its tests, and Task 3 (`aggregationAgent.backfillSource(saved, since)`). `ContentSource`, `ScrapedContent`, `ContentClassification` constructors match the locked record signatures. Change unit `@Execution(ContentSourceRepository, ContentAggregationAgent)` matches the test's `execution(sourceRepository, aggregationAgent)` call. ✓
