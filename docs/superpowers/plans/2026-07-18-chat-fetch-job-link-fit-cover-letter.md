# Chat Job-Link Fit Assessment + Cover Letter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the chat assistant read a recruiter-pasted job URL, give an honest fit assessment grounded in Simon's real experience, then (on request) draft a tailored cover letter and link his CV.

**Architecture:** One new SSRF-guarded page fetcher (`UrlFetcher`, jsoup) in a `com.simonrowe.webfetch` package, exposed to the model via a new description-gated `@Tool` component (`FetchUrlTools`) registered in `ChatConfig` alongside the existing tools. Guardrail + system-prompt text changes steer recruiter/job-link behaviour. Backend-only; no frontend, no new container, no external service, no API key.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring AI 1.1.4 (`@Tool`/`ToolContext`), jsoup (already on the classpath), JUnit 5 + Mockito, Google Java Style (Checkstyle, 140-col, 2-space indent, `final` params).

---

## Reference facts (verified in the codebase)

- Existing web-search tool to mirror exactly: `backend/src/main/java/com/simonrowe/chat/WebSearchTools.java` (returns `Object` — `List<WebSearchResult>` on success or a short `String` on unavailable/failure; `sessionId(ToolContext)` helper; `publishToolStart/End` via `ChatStreamPublisher`; `@WithSpan`).
- Its client to mirror: `backend/src/main/java/com/simonrowe/websearch/SearxngClient.java` (constructor reads `@Value`, builds a `RestClient`; `isConfigured()` + `search()`).
- jsoup fetch pattern in repo: `SitemapHtmlScraper` — `Jsoup.connect(url).timeout(TIMEOUT_MS).userAgent(USER_AGENT).get()`; strip via `Jsoup.parse(html).text()` (see `RssScraper.stripHtml`).
- `ChatConfig.chatClient(...)` currently: injects `final WebSearchTools webSearchTools`, and `.defaultTools(profileMcpTools, webSearchTools)` (lines ~36 and ~50).
- Guardrail prompt: `GuardrailAdvisor.CLASSIFICATION_PROMPT_TEMPLATE` (the SAFE paragraph already lists recruiter/employer intent).
- CV: `Profile.cvUrl` is returned by `getProfile()`; `ResumeController` serves the PDF at `/api/resume`.
- System prompt lives in `backend/src/main/resources/application.yml` under `chat.system-prompt` (block scalar); it already has a "RECRUITERS & EMPLOYERS are welcome" section and a tools bullet list including `webSearch`.
- Run tests: `cd backend && ../gradlew test`. Checkstyle: `../gradlew checkstyleMain checkstyleTest`.

## File structure

- **Create** `backend/src/main/java/com/simonrowe/webfetch/WebPageContent.java` — record `{title, url, text}`.
- **Create** `backend/src/main/java/com/simonrowe/webfetch/UrlFetcher.java` — SSRF-guarded jsoup fetcher + text extraction/truncation.
- **Create** `backend/src/main/java/com/simonrowe/chat/FetchUrlTools.java` — `@Tool fetchUrl(url, toolContext)`.
- **Modify** `backend/src/main/java/com/simonrowe/chat/ChatConfig.java` — inject + register `fetchUrlTools`.
- **Modify** `backend/src/main/java/com/simonrowe/chat/GuardrailAdvisor.java` — add pasted-job-URL wording to SAFE.
- **Modify** `backend/src/main/resources/application.yml` — `web-fetch` config block; extend system prompt (fetchUrl tool bullet + recruiter job-link/cover-letter/CV guidance).
- **Create** `backend/src/test/java/com/simonrowe/webfetch/UrlFetcherTest.java`.
- **Create** `backend/src/test/java/com/simonrowe/webfetch/FetchUrlToolsTest.java` (tool lives in `chat` but its test sits with the fetch feature, matching `WebSearchToolsTest`'s placement in `com/simonrowe/websearch`).
- **Modify** `backend/src/test/java/com/simonrowe/chat/GuardrailAdvisorTest.java` — assert new SAFE wording.

---

## Task 1: `WebPageContent` record

**Files:**
- Create: `backend/src/main/java/com/simonrowe/webfetch/WebPageContent.java`

- [ ] **Step 1: Create the record**

```java
package com.simonrowe.webfetch;

/**
 * Readable content extracted from a fetched web page (e.g. a job posting), passed to the chat
 * model so it can assess fit or enrich a grounded answer.
 *
 * @param title page title
 * @param url the (final) URL that was fetched
 * @param text extracted, truncated plain text of the page body
 */
public record WebPageContent(String title, String url, String text) {
}
```

- [ ] **Step 2: Compile**

Run: `cd backend && ../gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/simonrowe/webfetch/WebPageContent.java
git commit -m "feat: add WebPageContent record for fetched page content"
```

---

## Task 2: `UrlFetcher` — SSRF guard (validation first, TDD)

Build the fetcher's public URL validation before the network path, because it is the security-critical part and is fully unit-testable without a network.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/webfetch/UrlFetcher.java`
- Test: `backend/src/test/java/com/simonrowe/webfetch/UrlFetcherTest.java`

- [ ] **Step 1: Write the failing test for URL validation**

```java
package com.simonrowe.webfetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlFetcherTest {

  private final UrlFetcher fetcher = new UrlFetcher(8000, 8);

  @Test
  void rejectsNonHttpSchemes() {
    assertThat(UrlFetcher.isFetchableUrl("file:///etc/passwd")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("ftp://example.com/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("gopher://example.com")).isFalse();
  }

  @Test
  void rejectsBlankOrMalformedUrls() {
    assertThat(UrlFetcher.isFetchableUrl(null)).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("   ")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("not a url")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://")).isFalse();
  }

  @Test
  void rejectsLoopbackAndInternalHosts() {
    assertThat(UrlFetcher.isFetchableUrl("http://localhost:8080/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://127.0.0.1/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://[::1]/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://searxng:8080/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://portainer:9000/x")).isFalse();
  }

  @Test
  void rejectsPrivateAndMetadataAddresses() {
    assertThat(UrlFetcher.isFetchableUrl("http://10.0.0.5/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://192.168.1.10/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://172.16.4.4/x")).isFalse();
    assertThat(UrlFetcher.isFetchableUrl("http://169.254.169.254/latest/meta-data")).isFalse();
  }

  @Test
  void acceptsPublicHttpsUrls() {
    assertThat(UrlFetcher.isFetchableUrl("https://boards.greenhouse.io/acme/jobs/123")).isTrue();
    assertThat(UrlFetcher.isFetchableUrl("https://www.reed.co.uk/jobs/head-of-engineering/999")).isTrue();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.webfetch.UrlFetcherTest"`
Expected: FAIL — `UrlFetcher` does not exist / no `isFetchableUrl`.

- [ ] **Step 3: Implement `UrlFetcher` with the validation (network method stubbed for now)**

```java
package com.simonrowe.webfetch;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches a public web page and extracts readable text, for the chat assistant's fetchUrl tool.
 * Guards against SSRF by allowing only http/https and rejecting hosts that resolve to loopback,
 * private, link-local, or otherwise non-public addresses. Never throws to callers.
 */
public class UrlFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(UrlFetcher.class);
  private static final String USER_AGENT =
      "Mozilla/5.0 (compatible; SimonRoweBot/1.0; +https://simonrowe.dev)";
  private static final int MILLIS_PER_SECOND = 1000;
  private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

  private final int maxChars;
  private final int timeoutSeconds;

  public UrlFetcher(final int maxChars, final int timeoutSeconds) {
    this.maxChars = maxChars;
    this.timeoutSeconds = timeoutSeconds;
  }

  /**
   * Whether the URL is safe to fetch: http/https scheme and a host that resolves only to public
   * (non-loopback, non-private, non-link-local, non-multicast) addresses.
   *
   * @param url candidate URL
   * @return true when the URL may be fetched
   */
  public static boolean isFetchableUrl(final String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    final URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      return false;
    }
    final String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      return false;
    }
    final String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return false;
    }
    if (host.equalsIgnoreCase("localhost")) {
      return false;
    }
    try {
      for (final InetAddress address : InetAddress.getAllByName(host)) {
        if (address.isLoopbackAddress()
            || address.isAnyLocalAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
          return false;
        }
      }
    } catch (UnknownHostException e) {
      // Unresolvable host (e.g. an internal container name with no public DNS) — do not fetch.
      return false;
    }
    return true;
  }

  /**
   * Fetch and extract readable text from a public web page.
   *
   * @param url the page URL (callers should pre-check with {@link #isFetchableUrl(String)})
   * @return extracted content, or {@code null} if the URL is unsafe or the fetch fails
   */
  public WebPageContent fetch(final String url) {
    if (!isFetchableUrl(url)) {
      LOG.warn("Refusing to fetch non-public or invalid URL");
      return null;
    }
    try {
      final Document doc =
          Jsoup.connect(url.trim())
              .userAgent(USER_AGENT)
              .timeout(timeoutSeconds * MILLIS_PER_SECOND)
              .maxBodySize(MAX_BODY_BYTES)
              .followRedirects(true)
              .get();
      // Re-validate the effective URL after any redirects before using the content.
      final String finalUrl = doc.location() != null ? doc.location() : url.trim();
      if (!isFetchableUrl(finalUrl)) {
        LOG.warn("Refusing content: redirect landed on a non-public URL");
        return null;
      }
      final String title = doc.title();
      String text = doc.body() != null ? doc.body().text() : doc.text();
      if (text.length() > maxChars) {
        text = text.substring(0, maxChars);
      }
      return new WebPageContent(title, finalUrl, text);
    } catch (Exception e) {
      LOG.warn("Failed to fetch URL: {}", url, e);
      return null;
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.webfetch.UrlFetcherTest"`
Expected: PASS (5 tests). Note: `acceptsPublicHttpsUrls` performs real DNS resolution of `greenhouse.io`/`reed.co.uk`; if the build box has no DNS, those two assertions may fail — if so, the reviewer should confirm network access, not weaken the guard.

- [ ] **Step 5: Add the HTML-extraction test (static HTML, no network)**

Append to `UrlFetcherTest`:

```java
  @Test
  void extractsTitleAndTruncatesText() {
    UrlFetcher small = new UrlFetcher(10, 8);
    org.jsoup.nodes.Document doc =
        org.jsoup.Jsoup.parse("<html><head><title>Head of Engineering</title></head>"
            + "<body><h1>Role</h1><p>abcdefghijklmnop</p></body></html>");
    // Exercise the same extraction rules the fetch() method uses.
    String title = doc.title();
    String text = doc.body().text();
    if (text.length() > 10) {
      text = text.substring(0, 10);
    }
    assertThat(title).isEqualTo("Head of Engineering");
    assertThat(text).hasSize(10);
  }
```

- [ ] **Step 6: Run and commit**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.webfetch.UrlFetcherTest" && ../gradlew checkstyleMain checkstyleTest`
Expected: PASS, no checkstyle violations.

```bash
git add backend/src/main/java/com/simonrowe/webfetch/UrlFetcher.java \
        backend/src/test/java/com/simonrowe/webfetch/UrlFetcherTest.java
git commit -m "feat: add SSRF-guarded UrlFetcher for reading job postings"
```

---

## Task 2b: Register `UrlFetcher` as a Spring bean

`UrlFetcher` has a non-injectable primitive constructor, so provide it via a `@Bean` reading the config (mirrors how config values are wired elsewhere).

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/chat/ChatConfig.java`

- [ ] **Step 1: Add the bean method to `ChatConfig`**

Add imports at the top of `ChatConfig.java`:

```java
import com.simonrowe.webfetch.UrlFetcher;
```

Add this bean method inside the `ChatConfig` class (near `chatMemory()`):

```java
  @Bean
  public UrlFetcher urlFetcher(
      @Value("${web-fetch.max-chars:8000}") final int maxChars,
      @Value("${web-fetch.timeout-seconds:8}") final int timeoutSeconds) {
    return new UrlFetcher(maxChars, timeoutSeconds);
  }
```

(`org.springframework.beans.factory.annotation.Value` is already imported in `ChatConfig`.)

- [ ] **Step 2: Compile**

Run: `cd backend && ../gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/simonrowe/chat/ChatConfig.java
git commit -m "feat: expose UrlFetcher as a configured Spring bean"
```

---

## Task 3: `FetchUrlTools` `@Tool` component (TDD)

Mirrors `WebSearchTools` exactly: returns `Object`, publishes tool labels, never throws.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/chat/FetchUrlTools.java`
- Test: `backend/src/test/java/com/simonrowe/webfetch/FetchUrlToolsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.webfetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.chat.ChatStreamPublisher;
import com.simonrowe.chat.FetchUrlTools;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class FetchUrlToolsTest {

  private final ChatStreamPublisher publisher = mock(ChatStreamPublisher.class);

  private ToolContext ctx() {
    return new ToolContext(Map.of("sessionId", "s1"));
  }

  @Test
  void returnsFetchedContentAndPublishesLabels() {
    UrlFetcher fetcher = mock(UrlFetcher.class);
    when(fetcher.fetch("https://boards.greenhouse.io/acme/jobs/1"))
        .thenReturn(new WebPageContent("Head of Eng", "https://boards.greenhouse.io/acme/jobs/1",
            "Lead a team of engineers."));
    FetchUrlTools tools = new FetchUrlTools(fetcher, publisher);

    Object result = tools.fetchUrl("https://boards.greenhouse.io/acme/jobs/1", ctx());

    assertThat(result).isInstanceOf(WebPageContent.class);
    assertThat(((WebPageContent) result).title()).isEqualTo("Head of Eng");
    verify(publisher).toolStart("s1", "Reading the job posting");
    verify(publisher).toolEnd("s1", "Reading the job posting");
  }

  @Test
  void blankUrlReturnsMessageWithoutFetching() {
    UrlFetcher fetcher = mock(UrlFetcher.class);
    FetchUrlTools tools = new FetchUrlTools(fetcher, publisher);

    Object result = tools.fetchUrl("   ", ctx());

    assertThat(result).isEqualTo("I couldn't read that page.");
    verify(fetcher, never()).fetch(anyString());
    verify(publisher, never()).toolStart(any(), any());
  }

  @Test
  void unreadableUrlDegradesGracefully() {
    UrlFetcher fetcher = mock(UrlFetcher.class);
    when(fetcher.fetch(anyString())).thenReturn(null);
    FetchUrlTools tools = new FetchUrlTools(fetcher, publisher);

    Object result = tools.fetchUrl("https://www.linkedin.com/jobs/view/999", ctx());

    assertThat(result).isEqualTo("I couldn't read that page.");
    verify(publisher).toolStart("s1", "Reading the job posting");
    verify(publisher).toolEnd("s1", "Reading the job posting");
  }

  @Test
  void missingSessionIdSkipsLabels() {
    UrlFetcher fetcher = mock(UrlFetcher.class);
    when(fetcher.fetch(anyString()))
        .thenReturn(new WebPageContent("t", "https://example.com", "body"));
    FetchUrlTools tools = new FetchUrlTools(fetcher, publisher);

    Object result = tools.fetchUrl("https://example.com", new ToolContext(Map.of()));

    assertThat(result).isInstanceOf(WebPageContent.class);
    verify(publisher, never()).toolStart(any(), any());
    verify(publisher, never()).toolEnd(any(), any());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.webfetch.FetchUrlToolsTest"`
Expected: FAIL — `FetchUrlTools` does not exist.

- [ ] **Step 3: Implement `FetchUrlTools`**

```java
package com.simonrowe.chat;

import com.simonrowe.webfetch.UrlFetcher;
import com.simonrowe.webfetch.WebPageContent;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Reads the contents of a specific web page the visitor references — most often a job posting a
 * recruiter pastes — so the assistant can assess Simon's fit or enrich a grounded answer.
 * Degrades gracefully: blank/invalid/unreadable URLs return a short message rather than throwing.
 */
@Component
public class FetchUrlTools {

  private static final Logger LOG = LoggerFactory.getLogger(FetchUrlTools.class);
  private static final String LABEL = "Reading the job posting";
  private static final String UNREADABLE = "I couldn't read that page.";

  private final UrlFetcher urlFetcher;
  private final ChatStreamPublisher streamPublisher;

  public FetchUrlTools(final UrlFetcher urlFetcher, final ChatStreamPublisher streamPublisher) {
    this.urlFetcher = urlFetcher;
    this.streamPublisher = streamPublisher;
  }

  @WithSpan
  @Tool(
      description =
          "Read the contents of a specific web page the visitor references — most often a job "
              + "posting they paste — to assess Simon's fit for a role or to enrich an answer "
              + "grounded in his profile/experience/skills. Not a general web reader; do not use "
              + "it for unrelated pages. Returns the page title, url, and extracted text, or a "
              + "short message if the page cannot be read (some job boards block automated reads).")
  public Object fetchUrl(
      @ToolParam(description = "The absolute http(s) URL of the page to read")
          final String url,
      final ToolContext toolContext) {
    if (url == null || url.isBlank()) {
      return UNREADABLE;
    }
    final String sessionId = sessionId(toolContext);
    publishToolStart(sessionId);
    try {
      final WebPageContent content = urlFetcher.fetch(url);
      if (content == null) {
        return UNREADABLE;
      }
      return content;
    } catch (Exception e) {
      LOG.warn("fetchUrl failed for {}", url, e);
      return UNREADABLE;
    } finally {
      publishToolEnd(sessionId);
    }
  }

  private static String sessionId(final ToolContext toolContext) {
    if (toolContext == null) {
      return null;
    }
    Object value = toolContext.getContext().get("sessionId");
    return value instanceof String id && !id.isBlank() ? id : null;
  }

  private void publishToolStart(final String sessionId) {
    if (sessionId != null) {
      streamPublisher.toolStart(sessionId, LABEL);
    }
  }

  private void publishToolEnd(final String sessionId) {
    if (sessionId != null) {
      streamPublisher.toolEnd(sessionId, LABEL);
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.webfetch.FetchUrlToolsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Checkstyle + commit**

Run: `cd backend && ../gradlew checkstyleMain checkstyleTest`
Expected: no violations.

```bash
git add backend/src/main/java/com/simonrowe/chat/FetchUrlTools.java \
        backend/src/test/java/com/simonrowe/webfetch/FetchUrlToolsTest.java
git commit -m "feat: add fetchUrl chat tool for reading pasted job postings"
```

---

## Task 4: Register `fetchUrlTools` in the chat client

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/chat/ChatConfig.java` (the `chatClient` bean, ~lines 34-51)

- [ ] **Step 1: Add the parameter and register the tool**

Change the `chatClient` method signature to add the new parameter after `webSearchTools`:

```java
  @Bean
  public ChatClient chatClient(final ChatClient.Builder builder,
      final ChatMemory chatMemory, final ProfileMcpTools profileMcpTools,
      final WebSearchTools webSearchTools, final FetchUrlTools fetchUrlTools,
      final VectorStore vectorStore, final ChatModel chatModel) {
```

Change the `.defaultTools(...)` line from:

```java
        .defaultTools(profileMcpTools, webSearchTools)
```

to:

```java
        .defaultTools(profileMcpTools, webSearchTools, fetchUrlTools)
```

(`FetchUrlTools` is in the same `com.simonrowe.chat` package — no import needed.)

- [ ] **Step 2: Compile**

Run: `cd backend && ../gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/simonrowe/chat/ChatConfig.java
git commit -m "feat: register fetchUrl tool on the chat client"
```

---

## Task 5: Allow pasted job-URLs in the guardrail (TDD)

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/chat/GuardrailAdvisor.java` (the SAFE paragraph in `CLASSIFICATION_PROMPT_TEMPLATE`)
- Test: `backend/src/test/java/com/simonrowe/chat/GuardrailAdvisorTest.java` (the `testClassificationPromptIsDomainAware` test)

- [ ] **Step 1: Add the assertion to the existing prompt test**

In `GuardrailAdvisorTest.testClassificationPromptIsDomainAware`, after the existing
`recruiter` assertion, add:

```java
    assertTrue(
        sentPrompt.contains("job posting"),
        "prompt should allow a pasted job-posting URL");
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.chat.GuardrailAdvisorTest"`
Expected: FAIL on the new assertion (prompt has no "job posting" text yet).

- [ ] **Step 3: Update the SAFE wording**

In `GuardrailAdvisor.java`, find the recruiter clause in `CLASSIFICATION_PROMPT_TEMPLATE`:

```java
          + "hiring Simon — his suitability or fit for a role, availability, notice/salary "
          + "expectations, or current job openings comparable to his profile; greetings; meta "
```

Change it to include a pasted job posting URL:

```java
          + "hiring Simon — his suitability or fit for a role, availability, notice/salary "
          + "expectations, current job openings comparable to his profile, or a pasted job "
          + "posting URL/spec to assess his fit; greetings; meta "
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.chat.GuardrailAdvisorTest"`
Expected: PASS (all tests, including the new assertion).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/chat/GuardrailAdvisor.java \
        backend/src/test/java/com/simonrowe/chat/GuardrailAdvisorTest.java
git commit -m "feat: classify a pasted job-posting URL as on-topic in the guardrail"
```

---

## Task 6: Config block + system-prompt guidance

**Files:**
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Add the `web-fetch` config block**

Find the existing `web-search:` block:

```yaml
web-search:
  searxng:
    # Self-hosted SearXNG (no API key). Blank disables web search (graceful degradation);
    # set to the internal service URL (e.g. http://searxng:8080) to enable it.
    base-url: ${SEARXNG_URL:}
    max-results: 5
```

Immediately after it, add:

```yaml
web-fetch:
  # Reading a recruiter-pasted job posting URL (no external service; uses jsoup).
  max-chars: 8000
  timeout-seconds: 8
```

- [ ] **Step 2: Add `fetchUrl` to the tools bullet list in `chat.system-prompt`**

Find the `webSearch` bullet in the tools list:

```yaml
    - **webSearch** — search the live web for current information about a company I've worked at,
      a technology/skill I list, or a source in my content. Use it ONLY to enrich topics grounded
      in my profile/experience/skills — never for general or unrelated questions. Cite every
      result you use as an inline markdown link [title](url).
```

Immediately after that bullet (before the `submitContactForm` bullet), add:

```yaml
    - **fetchUrl** — read the contents of a specific web page the visitor references, most often
      a job posting a recruiter pastes. Use it to read the spec so you can assess my fit; if the
      page can't be read (some boards block it), say so and offer to search for the role instead.
```

- [ ] **Step 3: Extend the "RECRUITERS & EMPLOYERS" block**

Find the end of the existing recruiter block:

```yaml
    3. Offer next steps (share CV/availability, tailor a matching summary, or take a message via
       **submitContactForm**). Keep it honest — note genuine gaps rather than overselling.
```

Immediately after it, add a new paragraph:

```yaml


    When a recruiter pastes a link to a job they're advertising (or references a specific
    posting):
    1. Call **fetchUrl** on that URL to read the spec. If it can't be read (LinkedIn/Indeed often
       block automated reads), say so plainly and either use **webSearch** to find the role or
       ask them to paste the job description text.
    2. Give an honest fit assessment of me against the actual spec — call **getJobs** and
       **getSkills** to ground it in my real experience, and be candid about genuine gaps.
    3. Then OFFER (do not auto-generate) a tailored cover letter and my CV: e.g. "Want me to draft
       a short cover letter for this role, or share Simon's CV?"
    4. Only if they say yes, draft a concise first-person cover letter (3-4 short paragraphs)
       tailored to the fetched role, and link my CV using the cvUrl from **getProfile** (or
       [Download CV](/api/resume) if no cvUrl is set). Never invent a CV link.
```

- [ ] **Step 4: Verify the app still boots (config parses)**

Run: `cd backend && ../gradlew test --tests "com.simonrowe.chat.ChatConfigPromptTest"`
Expected: PASS (system prompt / config still loads). If no such test exercises YAML loading, run `../gradlew test --tests "com.simonrowe.chat.*"` instead.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/application.yml
git commit -m "feat: guide job-link fit assessment + cover letter in the chat prompt"
```

---

## Task 7: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Full backend test suite**

Run: `cd backend && ../gradlew test`
Expected: `BUILD SUCCESSFUL`, including `UrlFetcherTest`, `FetchUrlToolsTest`, `GuardrailAdvisorTest`, and the existing suite.

- [ ] **Step 2: Checkstyle**

Run: `cd backend && ../gradlew checkstyleMain checkstyleTest`
Expected: no violations.

- [ ] **Step 3: Manual smoke (optional, requires running stack)**

With the backend running (SearXNG optional), in the chat drawer paste a public company/ATS job
URL (e.g. a Greenhouse/Lever posting) and ask "is Simon a fit for this role?". Expect: a
"Reading the job posting" indicator, a grounded fit assessment, then an offer of a cover letter /
CV. Paste a LinkedIn job URL and confirm it degrades gracefully (says it can't read it, offers to
search).

- [ ] **Step 4: Final commit (if any working-tree changes remain)**

```bash
git add -A && git commit -m "chore: finalize job-link fit assessment feature"
```

---

## Notes for the implementer

- **Do not weaken the SSRF guard** to make a test pass. If `acceptsPublicHttpsUrls` fails, it's a DNS/network issue on the build box, not a code bug.
- `WebSearchTools` is the reference implementation for the tool shape (return `Object`, `sessionId` helper, label publishing, never throw) — keep `FetchUrlTools` consistent with it.
- Checkstyle is strict: 2-space indent, 140-col max, `final` on params/locals where the file does it, Javadoc on public types/methods, no star imports, no unused imports.
- This is backend-only. Do not add frontend, endpoints, or a new container.
