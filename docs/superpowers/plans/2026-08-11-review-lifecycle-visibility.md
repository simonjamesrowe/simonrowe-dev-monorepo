# Review Lifecycle Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every accepted code review visible on the pull request — acknowledged before anything can fail, then either replaced by the review or turned into a diagnosable failure notice.

**Architecture:** The workflow posts an acknowledgement comment as its *first* activity, using only `ReviewRequest`, so it lands before `loadPullRequest` can fail. It carries that comment id through the run: success publishes the review and deletes the ack; failure `PATCH`es the ack into a failure notice carrying phase, reason and a Temporal deep link. Lifecycle comments mint their token with no `permissions` override, so they survive the review path over-requesting permissions.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Temporal Java SDK, Jackson, JUnit 5 + AssertJ, `TestWorkflowEnvironment`, `com.sun.net.httpserver.HttpServer` for HTTP-level tests.

**Spec:** `docs/superpowers/specs/2026-08-11-review-lifecycle-visibility-design.md`

## Global Constraints

- Module is `software-factory`, package root `com.simonrowe.factory.codereview`. Nothing here touches `backend/` or `frontend/`.
- Google Java Style, enforced by Checkstyle. Method parameters are declared `final`. Two-space indent. Javadoc on public/package-private types and non-obvious methods.
- Run `cd software-factory && ../gradlew :software-factory:check` before each commit — it runs tests *and* Checkstyle. A commit that fails Checkstyle fails CI.
- **Never log, return, or assert on a token value.** Tests assert on request *shape* (headers present, body fields), never on secret content.
- All non-deterministic I/O stays behind Temporal activities. Workflow code must not call HTTP directly.
- Conventional commits, no Jira reference, no Claude attribution.
- `publish: false` must remain totally silent — no ack, no patch, no delete, no failure notice.

## File Structure

| File | Responsibility |
| --- | --- |
| `domain/ReviewFailure.java` (new) | Failure payload: phase, reason, workflow id |
| `github/ReviewMarkdownRenderer.java` | Adds `renderAck`; `renderFailure` gains phase + Temporal link |
| `github/GitHubCredentials.java` | Adds `commentToken`; 4xx mint failures become non-retryable and carry GitHub's body |
| `github/GitHubGateway.java` | Adds `publishAck` / `resolveAck`; `publishFailure` moves to `ReviewRequest` |
| `config/CodeReviewProperties.java` | Adds `temporalUiBaseUrl` |
| `workflow/ReviewActivities.java` + `ReviewActivitiesImpl.java` | New ack activities; `publishFailure` signature change |
| `workflow/CodeReviewWorkflowImpl.java` | Carries `ackCommentId`; drops the `pullRequest != null` guard |

**Do Task 1 first.** It adds the fourth component to `CodeReviewProperties`, which changes a
constructor every other task's tests call — doing it once, up front, stops Tasks 2 and 3 disagreeing
about the arity. Tasks 2 and 3 are then independent of each other. Task 4 depends on all three.

---

### Task 1: Failure payload and rendering

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/codereview/domain/ReviewFailure.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/codereview/github/ReviewMarkdownRenderer.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/codereview/config/CodeReviewProperties.java`
- Modify: `software-factory/src/main/resources/application.yml`
- Test: `software-factory/src/test/java/com/simonrowe/factory/codereview/github/ReviewMarkdownRendererTest.java`

**Interfaces:**
- Consumes: `ReviewPhase` (existing enum: `ACCEPTED`, `LOADING_PULL_REQUEST`, `REVIEWING`, `PUBLISHING`, `COMPLETED`, `FAILED`).
- Produces:
  - `record ReviewFailure(ReviewPhase phase, String reason, String workflowId)`
  - `String ReviewMarkdownRenderer.renderAck(String marker)`
  - `String ReviewMarkdownRenderer.renderFailure(ReviewFailure failure, String marker, String temporalUiBaseUrl)` — **replaces** the existing `renderFailure(String reason, String marker)`
  - `CodeReviewProperties` gains a fourth component, `String temporalUiBaseUrl`, so every later task sees a stable constructor

- [ ] **Step 1: Write the failing tests**

Append to `ReviewMarkdownRendererTest.java` (add imports `com.simonrowe.factory.codereview.domain.ReviewFailure` and `com.simonrowe.factory.codereview.domain.ReviewPhase`):

```java
  @Test
  void ackSaysAReviewIsRunningSoSilenceIsNotAmbiguous() {
    String body = new ReviewMarkdownRenderer().renderAck("<!-- marker -->");

    assertThat(body).startsWith("<!-- marker -->");
    assertThat(body).contains("Automated code review");
    assertThat(body).contains("in progress");
    assertThat(body).contains("Advisory only");
  }

  @Test
  void failureNamesThePhaseAndLinksTheWorkflowHistory() {
    ReviewFailure failure =
        new ReviewFailure(ReviewPhase.REVIEWING, "Claude exited with 1", "code-review-abc");

    String body =
        new ReviewMarkdownRenderer()
            .renderFailure(failure, "<!-- marker -->", "https://temporal.example.com");

    assertThat(body).contains("did not complete");
    assertThat(body).contains("REVIEWING");
    assertThat(body).contains("Claude exited with 1");
    assertThat(body)
        .contains("https://temporal.example.com/namespaces/default/workflows/code-review-abc");
    assertThat(body).contains("code-review-abc");
  }

  @Test
  void failureOmitsTheLinkWhenNoTemporalUrlIsConfigured() {
    ReviewFailure failure =
        new ReviewFailure(ReviewPhase.LOADING_PULL_REQUEST, "GitHub returned 422", "wf-1");

    String body = new ReviewMarkdownRenderer().renderFailure(failure, "<!-- marker -->", "");

    assertThat(body).contains("GitHub returned 422");
    assertThat(body).contains("LOADING_PULL_REQUEST");
    assertThat(body).doesNotContain("Workflow history");
    assertThat(body).doesNotContain("namespaces/default");
  }

  @Test
  void failureReasonCannotBreakOutOfItsCodeFence() {
    ReviewFailure failure = new ReviewFailure(ReviewPhase.REVIEWING, "a ``` b", "wf-1");

    String body = new ReviewMarkdownRenderer().renderFailure(failure, "<!-- marker -->", "");

    assertThat(body).doesNotContain("a ``` b");
    assertThat(body).contains("a ''' b");
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*ReviewMarkdownRendererTest'
```

Expected: FAIL — compile error, `cannot find symbol: class ReviewFailure` and `method renderAck`.

- [ ] **Step 3: Create the `ReviewFailure` record**

`domain/ReviewFailure.java`:

```java
package com.simonrowe.factory.codereview.domain;

/**
 * Why a review did not produce a report.
 *
 * <p>Carries the phase as well as the reason: "failed in REVIEWING" and "failed in PUBLISHING" call
 * for completely different investigations, and the reason alone rarely distinguishes them.
 */
public record ReviewFailure(ReviewPhase phase, String reason, String workflowId) {
}
```

- [ ] **Step 4: Replace `renderFailure` and add `renderAck`**

In `ReviewMarkdownRenderer.java`, add imports for `ReviewFailure`, then replace the whole existing `renderFailure(String, String)` method with:

```java
  public String renderAck(final String marker) {
    return marker
        + "\n"
        + "## Automated code review\n\n"
        + "🔄 A review of these changes is **in progress**.\n\n"
        + "This comment is replaced by the review when it finishes, or by the reason it did not.\n"
        + "\n_Advisory only; this reviewer does not approve or block merges._\n";
  }

  public String renderFailure(
      final ReviewFailure failure, final String marker, final String temporalUiBaseUrl) {
    return marker
        + "\n"
        + "## Automated code review — failed\n\n"
        + "This review did not complete, so these changes have **not** been reviewed.\n\n"
        + "**Phase:** `"
        + failure.phase()
        + "`\n\n"
        + "```\n"
        + fenceSafe(failure.reason())
        + "\n```\n"
        + workflowLink(failure.workflowId(), temporalUiBaseUrl)
        + "\n_Advisory only; this reviewer does not approve or block merges._\n";
  }

  /**
   * Renders the Temporal deep link, or nothing at all.
   *
   * <p>The link is the fastest route from a pull request to the full history, but it is a
   * convenience: an unconfigured base URL must never cost the reader the reason itself.
   */
  private static String workflowLink(final String workflowId, final String temporalUiBaseUrl) {
    if (workflowId == null || workflowId.isBlank()) {
      return "";
    }
    if (temporalUiBaseUrl == null || temporalUiBaseUrl.isBlank()) {
      return "\n`" + workflowId + "`\n";
    }
    String base =
        temporalUiBaseUrl.endsWith("/")
            ? temporalUiBaseUrl.substring(0, temporalUiBaseUrl.length() - 1)
            : temporalUiBaseUrl;
    return "\n[Workflow history]("
        + base
        + "/namespaces/default/workflows/"
        + workflowId
        + ") · `"
        + workflowId
        + "`\n";
  }
```

Leave `fenceSafe` exactly as it is.

- [ ] **Step 4b: Add the config property now, so later tasks see a stable constructor**

`CodeReviewProperties.java` — add a fourth component to the record:

```java
public record CodeReviewProperties(
    Github github, Agent agent, Api api, String temporalUiBaseUrl) {
```

`application.yml` — add under `factory.codereview`, as a sibling of `github`, `agent` and `api`:

```yaml
    # Deep-linked from failure comments on pull requests. Blank drops the link and keeps the
    # bare workflow id, so an unconfigured URL never costs the reader the failure reason.
    temporal-ui-base-url: ${TEMPORAL_UI_URL:https://temporal.simonrowe.dev}
```

Then fix every existing test that constructs the record:

```bash
cd software-factory && grep -rn "new CodeReviewProperties(" src/test/java
```

Add `"https://temporal.example.com"` as the final argument to each.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*ReviewMarkdownRendererTest'
```

Expected: PASS. `GitHubGateway.java` will not compile yet — that is fine, it is Task 3. To keep this task independently verifiable, temporarily leave `GitHubGateway.failureReviewPayload` calling the old signature and expect a compile error; **do not** commit until Step 6 passes.

If `:software-factory:test` fails to compile `GitHubGateway`, apply this one-line bridge in `GitHubGateway.failureReviewPayload` so the module compiles (Task 3 removes it):

```java
    payload.put(
        "body",
        renderer.renderFailure(
            new com.simonrowe.factory.codereview.domain.ReviewFailure(
                com.simonrowe.factory.codereview.domain.ReviewPhase.FAILED, reason, null),
            marker,
            ""));
```

- [ ] **Step 6: Run the full module check**

```bash
cd software-factory && ../gradlew :software-factory:check
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/codereview/domain/ReviewFailure.java \
        software-factory/src/main/java/com/simonrowe/factory/codereview/github/ReviewMarkdownRenderer.java \
        software-factory/src/main/java/com/simonrowe/factory/codereview/github/GitHubGateway.java \
        software-factory/src/main/java/com/simonrowe/factory/codereview/config/CodeReviewProperties.java \
        software-factory/src/main/resources/application.yml \
        software-factory/src/test/java
git commit -m "feat: render review acks and failures with phase and workflow link

A bare reason gave no indication of which phase died, and no route to the
Temporal history that holds the rest. ReviewFailure carries both."
```

---

### Task 2: A credential path that survives an over-broad permission request

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/codereview/github/GitHubCredentials.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/codereview/github/GitHubCredentialsTest.java`

**Interfaces:**
- Produces: `String GitHubCredentials.commentToken(Long installationId)` — mints with **no** `permissions` object, cached separately from `accessToken`.

**Why:** `mintInstallationToken` currently requests `contents:write`, `issues:write`, `pull_requests:write` on every mint. GitHub `422`s the *whole* request when any exceeds the installation's grant, which is what took the reviewer down on 2026-08-11. Omitting the block yields the installation's full grant, which cannot 422 on over-request — so lifecycle comments keep working when the review path cannot.

- [ ] **Step 1: Write the failing tests**

Append to `GitHubCredentialsTest.java`. Reuse the existing `writePrivateKey` and `tokenServer` helpers in that file — read them first and match their signatures exactly.

```java
  @Test
  void commentTokenRequestsNoPermissionsSoItCannotBeRejectedForOverReaching(
      @TempDir final Path directory) throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Path privateKey = writePrivateKey(directory, keyPair);
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = tokenServer(requests, authorization, requestBody);

    try {
      GitHubCredentials credentials = credentials(server, privateKey);

      credentials.commentToken(456L);

      assertThat(objectMapper.readTree(requestBody.get()).has("permissions")).isFalse();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void accessTokenStillRequestsTheWritePermissionsTheReviewPathNeeds(
      @TempDir final Path directory) throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Path privateKey = writePrivateKey(directory, keyPair);
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = tokenServer(requests, authorization, requestBody);

    try {
      GitHubCredentials credentials = credentials(server, privateKey);

      credentials.accessToken(456L);

      JsonNode permissions = objectMapper.readTree(requestBody.get()).path("permissions");
      assertThat(permissions.path("contents").asText()).isEqualTo("write");
      assertThat(permissions.path("issues").asText()).isEqualTo("write");
      assertThat(permissions.path("pull_requests").asText()).isEqualTo("write");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aRejectedMintIsNotRetriedAndCarriesGithubsExplanation(@TempDir final Path directory)
      throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Path privateKey = writePrivateKey(directory, keyPair);
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body =
              "{\"message\":\"The permissions requested are not granted to this installation.\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(422, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();

    try {
      GitHubCredentials credentials = credentials(server, privateKey);

      assertThatThrownBy(() -> credentials.accessToken(456L))
          .isInstanceOf(ApplicationFailure.class)
          .hasMessageContaining("422")
          .hasMessageContaining("not granted to this installation");
      assertThat(((ApplicationFailure) catchThrowable(() -> credentials.accessToken(456L)))
              .isNonRetryable())
          .isTrue();
    } finally {
      server.stop(0);
    }
  }
```

Add these imports: `static org.assertj.core.api.Assertions.assertThatThrownBy`, `static org.assertj.core.api.Assertions.catchThrowable`, `io.temporal.failure.ApplicationFailure`.

If the existing file has no `credentials(HttpServer, Path)` helper, add this private method to the test class:

```java
  private GitHubCredentials credentials(final HttpServer server, final Path privateKey) {
    CodeReviewProperties properties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "http://localhost:" + server.getAddress().getPort(),
                "",
                "secret",
                "client-id",
                privateKey.toString(),
                Duration.ofSeconds(5)),
            null,
            null,
            "");
    return new GitHubCredentials(
        properties, objectMapper, HttpClient.newHttpClient(), Clock.fixed(NOW, ZoneOffset.UTC));
  }
```

The trailing `""` is the `temporalUiBaseUrl` component added in Task 1. `GitHubCredentials` never reads it, so an empty value is correct here.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*GitHubCredentialsTest'
```

Expected: FAIL — `cannot find symbol: method commentToken`.

- [ ] **Step 3: Add the second cache and split the mint**

In `GitHubCredentials.java`, add beside the existing `installationTokens` field:

```java
  private final Map<Long, CachedToken> commentTokens = new ConcurrentHashMap<>();
```

Replace the body of `accessToken` and add `commentToken`:

```java
  public synchronized String accessToken(final Long installationId) {
    return token(installationId, installationTokens, true);
  }

  /**
   * A token for pull-request lifecycle comments only, minted with no {@code permissions} override.
   *
   * <p>Omitting the block yields the installation's full grant, which cannot be rejected for
   * over-reaching. {@link #accessToken} asks for more than the App may have been granted, and
   * GitHub rejects that whole request with {@code 422} — which is how a permission drift took every
   * review down on 2026-08-11, silently, because reporting the failure needed a token too. Keeping
   * the comment path on its own narrower mint means the next such drift is visible on the pull
   * request instead of only in Temporal.
   */
  public synchronized String commentToken(final Long installationId) {
    return token(installationId, commentTokens, false);
  }

  private String token(
      final Long installationId,
      final Map<Long, CachedToken> cache,
      final boolean requestWritePermissions) {
    if (installationId == null) {
      return staticToken();
    }
    if (!appConfigured()) {
      if (!staticToken().isBlank()) {
        return staticToken();
      }
      throw ApplicationFailure.newNonRetryableFailure(
          "GitHub App webhook requires GITHUB_APP_CLIENT_ID and "
              + "GITHUB_APP_PRIVATE_KEY_PATH",
          "MISSING_GITHUB_APP_CREDENTIALS");
    }

    Instant now = clock.instant();
    CachedToken cached = cache.get(installationId);
    if (cached != null && cached.expiresAt().isAfter(now.plus(EXPIRY_MARGIN))) {
      return cached.value();
    }
    CachedToken minted = mintInstallationToken(installationId, now, requestWritePermissions);
    cache.put(installationId, minted);
    return minted.value();
  }
```

- [ ] **Step 4: Make the permission block conditional and 4xx non-retryable**

Change the signature of `mintInstallationToken` to take the flag, make the payload conditional, and replace the status check.

Signature:

```java
  private CachedToken mintInstallationToken(
      final long installationId, final Instant now, final boolean requestWritePermissions) {
```

Replace the two payload lines (keep the long explanatory comment above them):

```java
      ObjectNode payload = objectMapper.createObjectNode();
      if (requestWritePermissions) {
        ObjectNode permissions =
            objectMapper
                .createObjectNode()
                .put("contents", "write")
                .put("issues", "write")
                .put("pull_requests", "write");
        payload.set("permissions", permissions);
      }
```

Replace the status check:

```java
      if (response.statusCode() >= 400 && response.statusCode() < 500) {
        // A 4xx here is a configuration fault, not a blip: the commonest is a 422 because the
        // requested permissions exceed the installation's grant. Retrying it three times only
        // delays the report, and GitHub's body names the offending permission, so keep it.
        throw ApplicationFailure.newNonRetryableFailure(
            "GitHub App token endpoint returned "
                + response.statusCode()
                + ": "
                + truncate(response.body()),
            "GITHUB_TOKEN_REJECTED");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitHub App token endpoint returned " + response.statusCode());
      }
```

Add this private helper to the class:

```java
  private static String truncate(final String body) {
    if (body == null || body.isBlank()) {
      return "(no response body)";
    }
    String collapsed = body.replaceAll("\\s+", " ").trim();
    return collapsed.length() > 200 ? collapsed.substring(0, 200) : collapsed;
  }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*GitHubCredentialsTest'
```

Expected: PASS, including the pre-existing `mintsAndCachesInstallationToken`.

- [ ] **Step 6: Run the full module check**

```bash
cd software-factory && ../gradlew :software-factory:check
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/codereview/github/GitHubCredentials.java \
        software-factory/src/test/java/com/simonrowe/factory/codereview/github/GitHubCredentialsTest.java
git commit -m "feat: mint lifecycle-comment tokens without a permissions override

GitHub rejects a whole token request when any requested permission exceeds the
installation's grant, so one permission drift took down reviewing and the
reporting of that failure together. Commenting now mints its own token from the
installation's full grant, which cannot be rejected for over-reaching.

A 4xx mint is also now non-retryable and keeps GitHub's response body, which
names the offending permission."
```

---

### Task 3: Ack lifecycle on the GitHub gateway

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/codereview/github/GitHubGateway.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/codereview/github/GitHubGatewayTest.java`

**Interfaces:**
- Consumes: `ReviewFailure`, `renderAck` / `renderFailure` and `CodeReviewProperties.temporalUiBaseUrl()` (Task 1); `commentToken` (Task 2).
- Produces:
  - `String GitHubGateway.publishAck(ReviewRequest request)` — returns the created comment id
  - `void GitHubGateway.resolveAck(ReviewRequest request, String ackCommentId)`
  - `void GitHubGateway.publishFailure(ReviewRequest request, String ackCommentId, ReviewFailure failure)` — **replaces** `publishFailure(PullRequestContext, String)`
  - Package-private for testing: `static String ackPath(ReviewRequest)`, `static String commentPath(ReviewRequest, String)`, `static String failureMethod(String ackCommentId)`

- [ ] **Step 1: Write the failing tests**

Append to `GitHubGatewayTest.java`. Add imports for `com.simonrowe.factory.codereview.domain.ReviewFailure` and `com.simonrowe.factory.codereview.domain.ReviewPhase`; `ReviewMarkdownRenderer` is in the same package and needs none.

```java
  @Test
  void ackIsPostedToTheIssueCommentsCollectionForThePullRequest() {
    String path =
        GitHubGateway.ackPath(new ReviewRequest("example", "project", 42, "sha", 1L, true));

    assertThat(path).isEqualTo("/repos/example/project/issues/42/comments");
  }

  @Test
  void ackIsEditedAndDeletedThroughTheRepositoryCommentResource() {
    String path =
        GitHubGateway.commentPath(
            new ReviewRequest("example", "project", 42, "sha", 1L, true), "987");

    assertThat(path).isEqualTo("/repos/example/project/issues/comments/987");
  }

  @Test
  void aFailureEditsTheAckWhenThereIsOneAndPostsFreshWhenThereIsNot() {
    assertThat(GitHubGateway.failureMethod("987")).isEqualTo("PATCH");
    assertThat(GitHubGateway.failureMethod(null)).isEqualTo("POST");
  }

  @Test
  void theFailureBodyCarriesThePhaseTheReasonAndTheWorkflowLink() {
    String body =
        new ReviewMarkdownRenderer()
            .renderFailure(
                new ReviewFailure(ReviewPhase.LOADING_PULL_REQUEST, "returned 422", "wf-9"),
                "<!-- temporal-code-review:sha:v1 -->",
                "https://temporal.example.com");

    assertThat(body).startsWith("<!-- temporal-code-review:sha:v1 -->");
    assertThat(body).contains("LOADING_PULL_REQUEST");
    assertThat(body).contains("returned 422");
    assertThat(body).contains("https://temporal.example.com/namespaces/default/workflows/wf-9");
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*GitHubGatewayTest'
```

Expected: FAIL — `cannot find symbol: method ackPath`.

- [ ] **Step 3: Add the ack lifecycle methods**

In `GitHubGateway.java`, add the paths and the three methods. Place them after the existing `publishReview`.

```java
  static String ackPath(final ReviewRequest request) {
    return "/repos/"
        + request.owner()
        + "/"
        + request.repository()
        + "/issues/"
        + request.pullNumber()
        + "/comments";
  }

  static String commentPath(final ReviewRequest request, final String commentId) {
    return "/repos/"
        + request.owner()
        + "/"
        + request.repository()
        + "/issues/comments/"
        + commentId;
  }

  /**
   * Announces that a review has started, returning the comment id so it can be resolved later.
   *
   * <p>Posted before anything else, from {@link ReviewRequest} alone, so it lands even when the run
   * dies loading the pull request — the failure that produced no comment at all on 2026-08-11.
   */
  public String publishAck(final ReviewRequest request) {
    String marker = marker(request.expectedHeadSha());
    JsonNode created =
        sendJson(
            "POST",
            ackPath(request),
            objectMapper.createObjectNode().put("body", renderer.renderAck(marker)),
            credentials.commentToken(request.installationId()));
    String id = created.path("id").asText();
    if (id.isBlank()) {
      throw new IllegalStateException("GitHub response omitted the ack comment id");
    }
    return id;
  }

  /** Removes the ack once the review itself is on the pull request. */
  public void resolveAck(final ReviewRequest request, final String ackCommentId) {
    String path = commentPath(request, ackCommentId);
    HttpResponse<String> response =
        send("DELETE", path, null, credentials.commentToken(request.installationId()));
    requireSuccess(response, "DELETE", path);
  }

  /**
   * Reports that no review happened, replacing the ack in place where there is one.
   *
   * <p>Takes {@link ReviewRequest} rather than {@link PullRequestContext} deliberately: the most
   * common failure happens while loading that context, so requiring it is what made the commonest
   * failure unreportable.
   */
  public void publishFailure(
      final ReviewRequest request, final String ackCommentId, final ReviewFailure failure) {
    String marker = marker(request.expectedHeadSha());
    String body = renderer.renderFailure(failure, marker, properties.temporalUiBaseUrl());
    String accessToken = credentials.commentToken(request.installationId());
    ObjectNode payload = objectMapper.createObjectNode().put("body", body);

    String method = failureMethod(ackCommentId);
    String path = ackCommentId == null ? ackPath(request) : commentPath(request, ackCommentId);
    requireSuccess(send(method, path, payload, accessToken), method, path);
  }

  /** Edits the ack where there is one; a review whose ack never landed still gets reported. */
  static String failureMethod(final String ackCommentId) {
    return ackCommentId == null ? "POST" : "PATCH";
  }
```

- [ ] **Step 4: Point the marker at a head SHA and delete the dead code**

Replace the existing private `marker(PullRequestContext)` with a SHA-based one, and update its two existing call sites in `publishReview` (`marker(pullRequest.headSha())`) and anywhere else it is used:

```java
  private String marker(final String headSha) {
    return "<!-- temporal-code-review:"
        + headSha
        + ":"
        + properties.agent().promptVersion()
        + " -->";
  }
```

Then **delete** `failureReviewPayload` entirely — including the temporary bridge added in Task 1 Step 5. Nothing calls it now.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*GitHubGatewayTest'
```

Expected: PASS.

- [ ] **Step 6: Run the full module check**

```bash
cd software-factory && ../gradlew :software-factory:check
```

Expected: BUILD SUCCESSFUL. `ReviewActivitiesImpl` will fail to compile against the new `publishFailure` signature — if so, stop and do Task 4; the two commits land together.

- [ ] **Step 7: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/codereview/github/GitHubGateway.java \
        software-factory/src/main/java/com/simonrowe/factory/codereview/config/CodeReviewProperties.java \
        software-factory/src/main/resources/application.yml \
        software-factory/src/test/java
git commit -m "feat: post, edit and delete a review ack comment

publishFailure now takes ReviewRequest rather than PullRequestContext: the
commonest failure happens while loading that context, so requiring it is what
made the commonest failure unreportable."
```

---

### Task 4: Wire the lifecycle through the workflow

**Files:**
- Modify: `software-factory/src/main/java/com/simonrowe/factory/codereview/workflow/ReviewActivities.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/codereview/workflow/ReviewActivitiesImpl.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/codereview/workflow/CodeReviewWorkflowImpl.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/codereview/workflow/CodeReviewWorkflowTest.java`

**Interfaces:**
- Consumes: `GitHubGateway.publishAck` / `resolveAck` / `publishFailure(ReviewRequest, String, ReviewFailure)` (Task 3); `ReviewFailure` (Task 1).
- Produces: `ReviewActivities` gains `String publishAck(ReviewRequest)` and `void resolveAck(ReviewRequest, String)`; `publishFailure` becomes `void publishFailure(ReviewRequest, String, ReviewFailure)`.

This task is atomic: the interface change does not compile without the workflow change.

- [ ] **Step 1: Write the failing tests**

Replace the whole body of `CodeReviewWorkflowTest.java` with the following. It rewrites both existing fakes for the new interface and adds the new cases.

```java
package com.simonrowe.factory.codereview.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewPhase;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.domain.ReviewResult;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CodeReviewWorkflowTest {

  private static final ReviewReport REPORT =
      new ReviewReport(
          "One concrete problem.",
          Verdict.COMMENT,
          List.of(
              new ReviewFinding(
                  Severity.WARNING,
                  "src/App.java",
                  12,
                  "Null result is dereferenced",
                  "The new null branch reaches this dereference.",
                  "Return before dereferencing.")));

  private static ReviewRequest request(final boolean publish) {
    return new ReviewRequest("owner", "repo", 7, "head-sha", 123L, publish);
  }

  private static CodeReviewWorkflow start(
      final TestWorkflowEnvironment environment, final Object activities, final String id) {
    Worker worker = environment.newWorker(CodeReviewTaskQueues.REVIEWS);
    worker.registerWorkflowImplementationTypes(CodeReviewWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    environment.start();
    return environment
        .getWorkflowClient()
        .newWorkflowStub(
            CodeReviewWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CodeReviewTaskQueues.REVIEWS)
                .setWorkflowId(id)
                .build());
  }

  @Test
  void acknowledgesBeforeLoadingThePullRequestThenDeletesTheAckOnSuccess() {
    RecordingActivities activities = new RecordingActivities();

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-ack-success");

      ReviewResult result = workflow.review(request(true));

      assertThat(activities.calls)
          .containsExactly(
              "publishAck", "loadPullRequest", "runReview", "publishReview", "resolveAck");
      assertThat(activities.resolvedAckId).isEqualTo("ack-1");
      assertThat(result.report()).isEqualTo(REPORT);
      assertThat(workflow.progress().phase()).isEqualTo(ReviewPhase.COMPLETED);
    }
  }

  @Test
  void editsTheAckIntoAFailureNoticeNamingThePhaseThatDied() {
    RecordingActivities activities = new RecordingActivities();
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure(
            "Claude exited with 1: subtype=error_max_turns", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-ack-failure");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).contains("publishAck", "publishFailure");
      assertThat(activities.calls).doesNotContain("resolveAck", "publishReview");
      assertThat(activities.failureAckId).isEqualTo("ack-1");
      assertThat(activities.failure.phase()).isEqualTo(ReviewPhase.REVIEWING);
      assertThat(activities.failure.reason()).contains("error_max_turns");
      assertThat(activities.failure.workflowId()).isEqualTo("review-ack-failure");
    }
  }

  @Test
  void reportsAFailureThatHappenedWhileLoadingThePullRequest() {
    RecordingActivities activities = new RecordingActivities();
    activities.failLoadWith =
        ApplicationFailure.newNonRetryableFailure(
            "GitHub App token endpoint returned 422", "GITHUB_TOKEN_REJECTED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-load-failure");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).contains("publishFailure");
      assertThat(activities.failure.phase()).isEqualTo(ReviewPhase.LOADING_PULL_REQUEST);
      assertThat(activities.failure.reason()).contains("422");
    }
  }

  @Test
  void postsAFreshFailureCommentWhenTheAckNeverLanded() {
    RecordingActivities activities = new RecordingActivities();
    activities.failAck = true;
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure("Claude exited with 1", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-no-ack");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).contains("publishFailure");
      assertThat(activities.failureAckId).isNull();
    }
  }

  @Test
  void anAckThatCannotBePostedDoesNotFailTheReview() {
    RecordingActivities activities = new RecordingActivities();
    activities.failAck = true;

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-ack-broken");

      ReviewResult result = workflow.review(request(true));

      assertThat(result.report()).isEqualTo(REPORT);
      assertThat(workflow.progress().phase()).isEqualTo(ReviewPhase.COMPLETED);
    }
  }

  @Test
  void anAckThatCannotBeDeletedDoesNotFailTheReview() {
    RecordingActivities activities = new RecordingActivities();
    activities.failResolve = true;

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-resolve-broken");

      ReviewResult result = workflow.review(request(true));

      assertThat(result.report()).isEqualTo(REPORT);
      assertThat(workflow.progress().phase()).isEqualTo(ReviewPhase.COMPLETED);
    }
  }

  @Test
  void aDryRunTouchesThePullRequestNotAtAll() {
    RecordingActivities activities = new RecordingActivities();

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-dry-run");

      ReviewResult result = workflow.review(request(false));

      assertThat(activities.calls).containsExactly("loadPullRequest", "runReview");
      assertThat(result.published()).isFalse();
    }
  }

  @Test
  void aDryRunStaysSilentEvenWhenItFails() {
    RecordingActivities activities = new RecordingActivities();
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure("Claude exited with 1", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-dry-run-failure");

      assertThatThrownBy(() -> workflow.review(request(false)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).doesNotContain("publishAck", "publishFailure");
    }
  }

  /** One fake for every case, so each test states only the behaviour it is about. */
  private static final class RecordingActivities implements ReviewActivities {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private boolean failAck;
    private boolean failResolve;
    private RuntimeException failLoadWith;
    private RuntimeException failReviewWith;
    private String resolvedAckId;
    private String failureAckId;
    private ReviewFailure failure;

    @Override
    public String publishAck(final ReviewRequest request) {
      calls.add("publishAck");
      if (failAck) {
        throw ApplicationFailure.newNonRetryableFailure("ack failed", "ACK_FAILED");
      }
      return "ack-1";
    }

    @Override
    public PullRequestContext loadPullRequest(final ReviewRequest request) {
      calls.add("loadPullRequest");
      if (failLoadWith != null) {
        throw failLoadWith;
      }
      return new PullRequestContext(
          request.owner(),
          request.repository(),
          request.pullNumber(),
          "Title",
          "Body",
          "https://github.com/owner/repo.git",
          "base-sha",
          "head-sha",
          request.installationId());
    }

    @Override
    public ReviewReport runReview(final PullRequestContext pullRequest) {
      calls.add("runReview");
      if (failReviewWith != null) {
        throw failReviewWith;
      }
      return REPORT;
    }

    @Override
    public void publishReview(
        final PullRequestContext pullRequest, final ReviewReport reviewReport) {
      calls.add("publishReview");
    }

    @Override
    public void resolveAck(final ReviewRequest request, final String ackCommentId) {
      calls.add("resolveAck");
      resolvedAckId = ackCommentId;
      if (failResolve) {
        throw ApplicationFailure.newNonRetryableFailure("delete failed", "DELETE_FAILED");
      }
    }

    @Override
    public void publishFailure(
        final ReviewRequest request, final String ackCommentId, final ReviewFailure reported) {
      calls.add("publishFailure");
      failureAckId = ackCommentId;
      failure = reported;
    }
  }
}
```

Remove the now-unused `ArrayList` import if Checkstyle flags it.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*CodeReviewWorkflowTest'
```

Expected: FAIL — `RecordingActivities is not abstract and does not override publishFailure(PullRequestContext, String)`.

- [ ] **Step 3: Update the activity interface**

`ReviewActivities.java` — replace the `publishFailure` declaration and add two methods:

```java
  @ActivityMethod
  String publishAck(ReviewRequest request);

  @ActivityMethod
  void resolveAck(ReviewRequest request, String ackCommentId);

  @ActivityMethod
  void publishFailure(ReviewRequest request, String ackCommentId, ReviewFailure failure);
```

Add the import for `ReviewFailure`.

- [ ] **Step 4: Update the activity implementation**

`ReviewActivitiesImpl.java` — replace `publishFailure` and add the two new delegates:

```java
  @Override
  public String publishAck(final ReviewRequest request) {
    return gitHubGateway.publishAck(request);
  }

  @Override
  public void resolveAck(final ReviewRequest request, final String ackCommentId) {
    gitHubGateway.resolveAck(request, ackCommentId);
  }

  @Override
  public void publishFailure(
      final ReviewRequest request, final String ackCommentId, final ReviewFailure failure) {
    gitHubGateway.publishFailure(request, ackCommentId, failure);
  }
```

Add the import for `ReviewFailure`.

- [ ] **Step 5: Rewrite the workflow orchestration**

`CodeReviewWorkflowImpl.java` — replace the `review` method and the `reportFailure` helper. Add imports for `ReviewFailure`.

```java
  @Override
  public ReviewResult review(final ReviewRequest request) {
    String ackCommentId = request.publish() ? acknowledge(request) : null;
    try {
      current =
          new ReviewProgress(
              ReviewPhase.LOADING_PULL_REQUEST, "Loading GitHub metadata", null, null);
      PullRequestContext pullRequest = networkActivities.loadPullRequest(request);

      current =
          new ReviewProgress(
              ReviewPhase.REVIEWING, "Running read-only agent", pullRequest.headSha(), null);
      ReviewReport report = agentActivities.runReview(pullRequest);

      if (request.publish()) {
        current =
            new ReviewProgress(
                ReviewPhase.PUBLISHING,
                "Publishing advisory comment",
                pullRequest.headSha(),
                report);
        networkActivities.publishReview(pullRequest, report);
        resolve(request, ackCommentId);
      }

      current =
          new ReviewProgress(
              ReviewPhase.COMPLETED, "Review completed", pullRequest.headSha(), report);
      return new ReviewResult(
          Workflow.getInfo().getWorkflowId(), pullRequest.headSha(), request.publish(), report);
    } catch (RuntimeException exception) {
      // Capture the phase before overwriting it — FAILED says nothing about where it died.
      ReviewPhase failedIn = current.phase();
      String reason = safeFailureMessage(exception);
      current = new ReviewProgress(ReviewPhase.FAILED, reason, current.headSha(), current.report());
      if (request.publish()) {
        reportFailure(
            request,
            ackCommentId,
            new ReviewFailure(failedIn, reason, Workflow.getInfo().getWorkflowId()));
      }
      throw exception;
    }
  }

  /**
   * Best-effort acknowledgement. A pull request that cannot be commented on is still worth
   * reviewing, so a failure here yields a null id and the run continues; the failure path then
   * posts a fresh comment rather than editing one.
   */
  private String acknowledge(final ReviewRequest request) {
    try {
      return networkActivities.publishAck(request);
    } catch (RuntimeException exception) {
      Workflow.getLogger(CodeReviewWorkflowImpl.class)
          .warn("Could not acknowledge the review on the pull request", exception);
      return null;
    }
  }

  /**
   * Best-effort ack removal, after the review is published. A failed delete leaves a stale "in
   * progress" beside a real review — visible and harmless. Deleting first and then failing to
   * publish would lose both.
   */
  private void resolve(final ReviewRequest request, final String ackCommentId) {
    if (ackCommentId == null) {
      return;
    }
    try {
      networkActivities.resolveAck(request, ackCommentId);
    } catch (RuntimeException exception) {
      Workflow.getLogger(CodeReviewWorkflowImpl.class)
          .warn("Could not remove the review acknowledgement", exception);
    }
  }

  /**
   * Best-effort notice on the pull request. A failure to report the failure must not replace the
   * original one, which is what actually needs diagnosing.
   */
  private void reportFailure(
      final ReviewRequest request, final String ackCommentId, final ReviewFailure failure) {
    try {
      networkActivities.publishFailure(request, ackCommentId, failure);
    } catch (RuntimeException exception) {
      Workflow.getLogger(CodeReviewWorkflowImpl.class)
          .warn("Could not publish review failure notice", exception);
    }
  }
```

Note what is gone: the `pullRequest` local declared before the `try`, and the `pullRequest != null` guard. That guard is the defect this whole change exists to remove.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*CodeReviewWorkflowTest'
```

Expected: PASS, all 8.

- [ ] **Step 7: Run the full module check**

```bash
cd software-factory && ../gradlew :software-factory:check
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/codereview/workflow/ \
        software-factory/src/test/java/com/simonrowe/factory/codereview/workflow/
git commit -m "feat: acknowledge every review, then resolve or report it

The failure notice was guarded on pullRequest != null, and the commonest
failure is thrown while loading that pull request — so the commonest failure
posted nothing at all. Three of seven pull requests got no comment of any kind.

The ack is now the first activity and needs only ReviewRequest, so it lands
before anything can fail. Success deletes it after publishing the review;
failure edits it into a notice carrying the phase, the reason and a link to the
Temporal history. Ack and delete are both best-effort: neither can fail a
review that would otherwise have succeeded."
```

---

### Task 5: Document the lifecycle

**Files:**
- Modify: `docs/runbooks/software-factory.md`

- [ ] **Step 1: Document what appears on a pull request**

Add a `## What appears on a pull request` section after `## Entry points`:

```markdown
## What appears on a pull request

One comment per reviewed head SHA, which transitions:

| State | Comment |
| --- | --- |
| Accepted | 🔄 "A review of these changes is in progress" |
| Reviewed | the review itself; the ack is deleted |
| Failed | the ack is edited into a failure notice with phase, reason and a Temporal link |

**Silence now means exactly one thing: the workflow never started** — the webhook
did not arrive, or nothing is polling the `code-review` task queue. Every other
outcome is visible on the pull request. Before this, silence meant any of five
things and was the normal presentation of failure.

Two caveats:

- `publish: false` runs post nothing at all, including failure notices. A green
  dry run does not prove the publish path works.
- A credential fault severe enough to block minting any token also blocks
  commenting, so it stays visible only in Temporal. Lifecycle comments mint their
  token with no `permissions` override to make this as unlikely as possible — an
  over-broad request from the review path cannot break commenting — but an
  uninstalled App or a bad key will.
```

- [ ] **Step 2: Commit**

```bash
git add docs/runbooks/software-factory.md
git commit -m "docs: describe the review comment lifecycle"
```

---

## Verification after all tasks

```bash
cd software-factory && ../gradlew :software-factory:check
```

Then, once deployed, push a commit to any open pull request and expect an ack within seconds, followed by either a review with the ack gone, or the ack replaced by a failure naming its phase.

**No GitHub App permission change is required.** GitHub governs comments on a pull request by the `pull_requests` permission — already granted — and the no-override token inherits it.

## Deliberately not in this plan

- **A poller pre-check** in the webhook receiver. Temporal shows workflows are starting; this has never been the failure.
- **A non-GitHub failure signal.** The one failure class this cannot surface is a credential fault that blocks commenting. Tracked in the spec's closing section.
- **A self-imposed workflow deadline.** The agent activity's `StartToCloseTimeout(20m)` already converts a hang into a normal workflow exception.
