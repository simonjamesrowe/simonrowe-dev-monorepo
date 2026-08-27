package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewPhase;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewMarkdownRendererTest {

  private final ReviewMarkdownRenderer renderer = new ReviewMarkdownRenderer();

  private static ReviewFinding finding() {
    return new ReviewFinding(
        Severity.WARNING,
        "src/App.java",
        12,
        "Null result is dereferenced",
        "The new null branch reaches this dereference.",
        "Return before dereferencing.");
  }

  @Test
  void summaryCarriesMarkerSummaryVerdictAndFooter() {
    ReviewReport report =
        new ReviewReport("One concrete problem.", Verdict.COMMENT, List.of(finding()));

    String body = renderer.renderSummary(report, "<!-- marker -->", "head-sha", List.of());

    assertThat(body).startsWith("<!-- marker -->");
    assertThat(body).contains("One concrete problem.");
    assertThat(body).contains("**Verdict:** `comment`");
    assertThat(body).contains("block the merge");
    assertThat(body).doesNotContain("### Findings");
  }

  /**
   * The summary is edited in place on every push, so without the commit it describes a reader
   * cannot tell whether it is about the code currently in front of them.
   */
  @Test
  void summaryNamesTheCommitItReviewedBecauseItIsRewrittenOnEveryPush() {
    ReviewReport report = new ReviewReport("Fine.", Verdict.APPROVE, List.of());

    String body =
        renderer.renderSummary(report, "<!-- marker -->", "0123456789abcdef", List.of());

    assertThat(body).contains("0123456");
  }

  @Test
  void summaryListsUnanchorableFindingsInline() {
    ReviewReport report =
        new ReviewReport("One concrete problem.", Verdict.COMMENT, List.of(finding()));

    String body =
        renderer.renderSummary(report, "<!-- marker -->", "head-sha", report.findings());

    assertThat(body).contains("### Findings");
    assertThat(body).contains("`src/App.java:12`");
    assertThat(body).contains("Return before dereferencing.");
  }

  @Test
  void findingCommentIsSelfContained() {
    String comment = renderer.renderFindingComment(finding());

    assertThat(comment).contains("**warning — Null result is dereferenced**");
    assertThat(comment).contains("The new null branch reaches this dereference.");
    assertThat(comment).contains("_Recommendation:_ Return before dereferencing.");
    assertThat(comment).doesNotContain("src/App.java");
  }

  /**
   * The marker carries the finding's fingerprint, which is what turns "delete everything and
   * repost" into a reconcile: the thread a finding opened can be found again on the next push.
   */
  @Test
  void findingCommentCarriesItsOwnFingerprintedMarker() {
    String comment = renderer.renderFindingComment(finding());

    assertThat(comment).startsWith("<!-- temporal-code-review-finding:");
    assertThat(ReviewMarkdownRenderer.FINDING_MARKER_PATTERN.matcher(comment).find()).isTrue();
  }

  @Test
  void theSameFindingRendersTheSameMarkerTwice() {
    assertThat(renderer.renderFindingComment(finding()))
        .isEqualTo(renderer.renderFindingComment(finding()));
  }

  @Test
  void twoDifferentFindingsRenderTwoDifferentMarkers() {
    ReviewFinding other =
        new ReviewFinding(
            Severity.WARNING, "src/Other.java", 1, "Unbounded loop", "Because.", "Bound it.");

    assertThat(markerOf(renderer.renderFindingComment(finding())))
        .isNotEqualTo(markerOf(renderer.renderFindingComment(other)));
  }

  private static String markerOf(final String comment) {
    java.util.regex.Matcher matcher =
        ReviewMarkdownRenderer.FINDING_MARKER_PATTERN.matcher(comment);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  @Test
  void ackSaysTheReviewIsRunningSoSilenceIsNotAmbiguous() {
    String body = renderer.renderAck("<!-- marker -->", "0123456789abcdef");

    assertThat(body).startsWith("<!-- marker -->");
    assertThat(body).contains("Automated code review");
    assertThat(body).contains("in progress");
    assertThat(body).contains("0123456");
    assertThat(body).contains("block the merge");
  }

  @Test
  void failureNamesThePhaseAndLinksTheWorkflowHistory() {
    ReviewFailure failure =
        new ReviewFailure(ReviewPhase.REVIEWING, "Claude exited with 1", "code-review-abc");

    String body =
        renderer.renderFailure(
            failure, "<!-- marker -->", "head-sha", "https://temporal.example.com");

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

    String body = renderer.renderFailure(failure, "<!-- marker -->", "head-sha", "");

    assertThat(body).contains("GitHub returned 422");
    assertThat(body).contains("LOADING_PULL_REQUEST");
    assertThat(body).doesNotContain("Workflow history");
    assertThat(body).doesNotContain("namespaces/default");
  }

  @Test
  void failureReasonCannotBreakOutOfItsCodeFence() {
    ReviewFailure failure = new ReviewFailure(ReviewPhase.REVIEWING, "a ``` b", "wf-1");

    String body = renderer.renderFailure(failure, "<!-- marker -->", "head-sha", "");

    assertThat(body).doesNotContain("a ``` b");
    assertThat(body).contains("a ''' b");
  }
}
