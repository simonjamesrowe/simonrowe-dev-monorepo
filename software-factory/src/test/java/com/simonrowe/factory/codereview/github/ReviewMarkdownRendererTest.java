package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewMarkdownRendererTest {

  @Test
  void rendersStableMarkerAndGroundedFinding() {
    ReviewReport report =
        new ReviewReport(
            "Summary",
            Verdict.COMMENT,
            List.of(
                new ReviewFinding(
                    Severity.WARNING,
                    "src/App.java",
                    12,
                    "Reachable defect",
                    "Explanation.",
                    "Recommendation.")));

    String markdown = new ReviewMarkdownRenderer().render(report, "<!-- marker -->");

    assertThat(markdown)
        .startsWith("<!-- marker -->")
        .contains("`src/App.java:12`")
        .contains("Advisory only");
  }
}
