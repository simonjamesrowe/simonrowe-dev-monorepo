package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.domain.ExistingThread;
import com.simonrowe.factory.codereview.domain.FindingFingerprint;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.ThreadAction;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reconcile decision table is the contract that replaced "delete everything and repost", so it
 * is tested as a pure function rather than through HTTP.
 */
class ReviewThreadGatewayTest {

  private static final ReviewFinding FINDING =
      new ReviewFinding(Severity.WARNING, "src/App.java", 12, "Bad", "Because.", "Fix it.");
  private static final ReviewFinding OTHER =
      new ReviewFinding(Severity.WARNING, "src/Other.java", 4, "Also bad", "Because.", "Fix it.");

  private static final String FINDING_FP = FindingFingerprint.of(FINDING);
  private static final String OTHER_FP = FindingFingerprint.of(OTHER);

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static ExistingThread thread(
      final String nodeId, final String fingerprint, final boolean resolved) {
    return new ExistingThread(nodeId, fingerprint, false, resolved, false);
  }

  // ---------------------------------------------------------------- decision table

  @Test
  void findingStillReportedWithAnOpenThreadIsLeftCompletelyAlone() {
    List<ThreadAction> actions =
        ReviewThreadGateway.reconcile(List.of(thread("T1", FINDING_FP, false)), List.of(FINDING));

    assertThat(actions).containsExactly(new ThreadAction.Leave("T1"));
  }

  /** A regression deserves a new conversation; silently reopening the old one hides that it came
   * back. */
  @Test
  void findingStillReportedWhoseThreadWasResolvedGetsFreshThread() {
    List<ThreadAction> actions =
        ReviewThreadGateway.reconcile(List.of(thread("T1", FINDING_FP, true)), List.of(FINDING));

    assertThat(actions)
        .containsExactly(new ThreadAction.Leave("T1"), new ThreadAction.PostNew(FINDING));
  }

  @Test
  void findingNoLongerReportedIsRepliedToAndResolved() {
    List<ThreadAction> actions =
        ReviewThreadGateway.reconcile(List.of(thread("T1", FINDING_FP, false)), List.of());

    assertThat(actions).containsExactly(new ThreadAction.ReplyAndResolve("T1"));
  }

  @Test
  void alreadyResolvedThreadForAnUnreportedFindingIsLeftAlone() {
    List<ThreadAction> actions =
        ReviewThreadGateway.reconcile(List.of(thread("T1", FINDING_FP, true)), List.of());

    assertThat(actions).containsExactly(new ThreadAction.Leave("T1"));
  }

  @Test
  void findingWithNoThreadAtAllGetsNewOne() {
    List<ThreadAction> actions = ReviewThreadGateway.reconcile(List.of(), List.of(FINDING));

    assertThat(actions).containsExactly(new ThreadAction.PostNew(FINDING));
  }

  /**
   * Required conversation resolution covers a human's thread too, but resolving it is a judgement
   * only a person can make — so the reviewer must never touch one it did not open.
   */
  @Test
  void threadThisReviewerDidNotOpenIsNeverTouched() {
    ExistingThread human = new ExistingThread("T1", null, false, false, true);

    List<ThreadAction> actions = ReviewThreadGateway.reconcile(List.of(human), List.of());

    assertThat(actions).containsExactly(new ThreadAction.Leave("T1"));
  }

  /**
   * A thread from before findings had identity carries the bare marker and can never match a new
   * fingerprint, so it is resolved on the first run after deploy. That is the correct outcome for a
   * pre-change artefact, and it destroys nothing.
   */
  @Test
  void legacyBareMarkerThreadIsRepliedToAndResolved() {
    ExistingThread legacy = new ExistingThread("T1", null, true, false, false);

    List<ThreadAction> actions = ReviewThreadGateway.reconcile(List.of(legacy), List.of(FINDING));

    assertThat(actions)
        .containsExactly(
            new ThreadAction.ReplyAndResolve("T1"), new ThreadAction.PostNew(FINDING));
  }

  @Test
  void mixedReportResolvesWhatWentAndLeavesWhatRemains() {
    List<ThreadAction> actions =
        ReviewThreadGateway.reconcile(
            List.of(thread("T1", FINDING_FP, false), thread("T2", OTHER_FP, false)),
            List.of(FINDING));

    assertThat(actions)
        .containsExactly(new ThreadAction.Leave("T1"), new ThreadAction.ReplyAndResolve("T2"));
  }

  /**
   * The guarantee this whole feature rests on. {@link ThreadAction} has no delete case, so this
   * asserts the type stays that way as much as it asserts the behaviour.
   */
  @Test
  void noInputEverProducesDeleteAction() {
    List<List<ExistingThread>> threadSets =
        List.of(
            List.of(),
            List.of(thread("T1", FINDING_FP, false)),
            List.of(thread("T1", FINDING_FP, true)),
            List.of(new ExistingThread("T1", null, true, false, false)),
            List.of(new ExistingThread("T1", null, false, false, true)));
    List<List<ReviewFinding>> reports =
        List.of(List.of(), List.of(FINDING), List.of(FINDING, OTHER));

    for (List<ExistingThread> threads : threadSets) {
      for (List<ReviewFinding> findings : reports) {
        assertThat(ReviewThreadGateway.reconcile(threads, findings))
            .allSatisfy(
                action ->
                    assertThat(action)
                        .isInstanceOfAny(
                            ThreadAction.Leave.class,
                            ThreadAction.PostNew.class,
                            ThreadAction.ReplyAndResolve.class));
      }
    }

    assertThat(ThreadAction.class.getPermittedSubclasses()).hasSize(3);
  }

  /** The same finding must not be posted twice when it already has an open thread. */
  @Test
  void findingWithAnOpenThreadIsNotPostedAgain() {
    List<ThreadAction> actions =
        ReviewThreadGateway.reconcile(
            List.of(thread("T1", FINDING_FP, false)), List.of(FINDING, OTHER));

    assertThat(actions)
        .containsExactly(new ThreadAction.Leave("T1"), new ThreadAction.PostNew(OTHER));
  }

  /** Identity survives a rebase moving the line and the model re-grading the severity. */
  @Test
  void reWordedLineAndSeverityDoNotReopenAnExistingThread() {
    ReviewFinding regraded =
        new ReviewFinding(Severity.CRITICAL, "src/App.java", 900, "bad", "Other.", "Other.");

    List<ThreadAction> actions =
        ReviewThreadGateway.reconcile(List.of(thread("T1", FINDING_FP, false)), List.of(regraded));

    assertThat(actions).containsExactly(new ThreadAction.Leave("T1"));
  }

  // ---------------------------------------------------------------- GraphQL mapping

  private JsonNode graphQl(final String json) throws Exception {
    return objectMapper.readTree(json);
  }

  @Test
  void mapsNodeIdResolvedStateAndFingerprintFromTheRootComment() throws Exception {
    String marker = ReviewMarkdownRenderer.findingMarker(FINDING_FP);
    JsonNode node =
        graphQl(
            """
            {"reviewThreads": {"nodes": [
              {"id": "MDEx", "isResolved": true, "comments": {"nodes": [
                {"body": "%s\\nBad", "author": {"login": "bot", "__typename": "Bot"}}
              ]}}
            ]}}
            """
                .formatted(marker));

    List<ExistingThread> threads = ReviewThreadGateway.toExistingThreads(node);

    assertThat(threads).singleElement().satisfies(thread -> {
      assertThat(thread.nodeId()).isEqualTo("MDEx");
      assertThat(thread.resolved()).isTrue();
      assertThat(thread.fingerprint()).isEqualTo(FINDING_FP);
      assertThat(thread.legacyMarker()).isFalse();
      assertThat(thread.reviewerOwned()).isTrue();
    });
  }

  @Test
  void threadWithNoReviewerMarkerHasNoFingerprintAndIsNotReviewerOwned() throws Exception {
    JsonNode node =
        graphQl(
            """
            {"reviewThreads": {"nodes": [
              {"id": "H1", "isResolved": false, "comments": {"nodes": [
                {"body": "Why is this here?", "author": {"login": "simon", "__typename": "User"}}
              ]}}
            ]}}
            """);

    assertThat(ReviewThreadGateway.toExistingThreads(node))
        .singleElement()
        .satisfies(thread -> {
          assertThat(thread.fingerprint()).isNull();
          assertThat(thread.legacyMarker()).isFalse();
          assertThat(thread.reviewerOwned()).isFalse();
        });
  }

  @Test
  void legacyBareMarkerIsRecognisedAsThisReviewersOwnThread() throws Exception {
    JsonNode node =
        graphQl(
            """
            {"reviewThreads": {"nodes": [
              {"id": "L1", "isResolved": false, "comments": {"nodes": [
                {"body": "%s\\nOld", "author": {"login": "bot", "__typename": "Bot"}}
              ]}}
            ]}}
            """
                .formatted(ReviewMarkdownRenderer.LEGACY_FINDING_MARKER));

    assertThat(ReviewThreadGateway.toExistingThreads(node))
        .singleElement()
        .satisfies(thread -> {
          assertThat(thread.fingerprint()).isNull();
          assertThat(thread.legacyMarker()).isTrue();
          assertThat(thread.reviewerOwned()).isTrue();
        });
  }

  @Test
  void replyFromAnyoneOtherThanBotIsDetected() throws Exception {
    JsonNode node =
        graphQl(
            """
            {"reviewThreads": {"nodes": [
              {"id": "T1", "isResolved": false, "comments": {"nodes": [
                {"body": "%s", "author": {"login": "bot", "__typename": "Bot"}},
                {"body": "Declined, see spec.", "author": {"login": "simon", "__typename": "User"}}
              ]}}
            ]}}
            """
                .formatted(ReviewMarkdownRenderer.findingMarker(FINDING_FP)));

    assertThat(ReviewThreadGateway.toExistingThreads(node))
        .singleElement()
        .satisfies(thread -> assertThat(thread.hasNonBotReply()).isTrue());
  }

  @Test
  void botOnlyThreadHasNoNonBotReply() throws Exception {
    JsonNode node =
        graphQl(
            """
            {"reviewThreads": {"nodes": [
              {"id": "T1", "isResolved": false, "comments": {"nodes": [
                {"body": "%s", "author": {"login": "bot", "__typename": "Bot"}},
                {"body": "No longer reported.", "author": {"login": "bot", "__typename": "Bot"}}
              ]}}
            ]}}
            """
                .formatted(ReviewMarkdownRenderer.findingMarker(FINDING_FP)));

    assertThat(ReviewThreadGateway.toExistingThreads(node))
        .singleElement()
        .satisfies(thread -> assertThat(thread.hasNonBotReply()).isFalse());
  }

  /**
   * Only the root comment carries identity. A fingerprint quoted in a reply — someone pasting the
   * marker back — must not be mistaken for the thread's own.
   */
  @Test
  void onlyTheRootCommentIsSearchedForFingerprint() throws Exception {
    JsonNode node =
        graphQl(
            """
            {"reviewThreads": {"nodes": [
              {"id": "T1", "isResolved": false, "comments": {"nodes": [
                {"body": "Why is this here?", "author": {"login": "simon", "__typename": "User"}},
                {"body": "%s", "author": {"login": "bot", "__typename": "Bot"}}
              ]}}
            ]}}
            """
                .formatted(ReviewMarkdownRenderer.findingMarker(FINDING_FP)));

    assertThat(ReviewThreadGateway.toExistingThreads(node))
        .singleElement()
        .satisfies(thread -> assertThat(thread.fingerprint()).isNull());
  }

  @Test
  void emptyThreadListMapsToNoThreads() throws Exception {
    assertThat(
            ReviewThreadGateway.toExistingThreads(
                graphQl("{\"reviewThreads\": {\"nodes\": []}}")))
        .isEmpty();
  }

  @Test
  void fingerprintIsParsedFromExactlyTheStringTheRendererWrote() {
    String body = new ReviewMarkdownRenderer().renderFindingComment(FINDING);

    assertThat(ReviewThreadGateway.fingerprintOf(body)).isEqualTo(FINDING_FP);
  }
}
