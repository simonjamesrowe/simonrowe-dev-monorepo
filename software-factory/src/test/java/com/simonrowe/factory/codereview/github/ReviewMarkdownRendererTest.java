package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.codereview.domain.ReviewFinding;
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
}
