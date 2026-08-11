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
  void reviewBodyCarriesMarkerSummaryVerdictAndFooter() {
    ReviewReport report =
        new ReviewReport("One concrete problem.", Verdict.COMMENT, List.of(finding()));

    String body = renderer.renderReviewBody(report, "<!-- marker -->", List.of());

    assertThat(body).startsWith("<!-- marker -->");
    assertThat(body).contains("One concrete problem.");
    assertThat(body).contains("**Verdict:** `comment`");
    assertThat(body).contains("_Advisory only");
    assertThat(body).doesNotContain("### Findings");
  }

  @Test
  void reviewBodyListsUnanchorableFindingsInline() {
    ReviewReport report =
        new ReviewReport("One concrete problem.", Verdict.COMMENT, List.of(finding()));

    String body = renderer.renderReviewBody(report, "<!-- marker -->", report.findings());

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

  @Test
  void ackSaysTheReviewIsRunningSoSilenceIsNotAmbiguous() {
    String body = renderer.renderAck("<!-- marker -->");

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
        renderer.renderFailure(failure, "<!-- marker -->", "https://temporal.example.com");

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

    String body = renderer.renderFailure(failure, "<!-- marker -->", "");

    assertThat(body).contains("GitHub returned 422");
    assertThat(body).contains("LOADING_PULL_REQUEST");
    assertThat(body).doesNotContain("Workflow history");
    assertThat(body).doesNotContain("namespaces/default");
  }

  @Test
  void failureReasonCannotBreakOutOfItsCodeFence() {
    ReviewFailure failure = new ReviewFailure(ReviewPhase.REVIEWING, "a ``` b", "wf-1");

    String body = renderer.renderFailure(failure, "<!-- marker -->", "");

    assertThat(body).doesNotContain("a ``` b");
    assertThat(body).contains("a ''' b");
  }
}
