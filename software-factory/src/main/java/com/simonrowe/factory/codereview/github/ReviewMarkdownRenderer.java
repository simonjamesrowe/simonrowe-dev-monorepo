package com.simonrowe.factory.codereview.github;

import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import java.util.List;
import org.springframework.stereotype.Component;

/** Renders GitHub Review bodies and per-finding inline comments. */
@Component
public class ReviewMarkdownRenderer {

  public String renderReviewBody(
      final ReviewReport report, final String marker, final List<ReviewFinding> inlineFallback) {
    StringBuilder body = new StringBuilder();
    body.append(marker).append("\n");
    body.append("## Automated code review\n\n");
    body.append(report.summary()).append("\n\n");
    body.append("**Verdict:** `").append(report.verdict().toJson()).append("`\n");

    if (!inlineFallback.isEmpty()) {
      body.append("\n### Findings\n");
      for (ReviewFinding finding : inlineFallback) {
        body
            .append("\n- **")
            .append(finding.severity().toJson())
            .append(" — ")
            .append(finding.title())
            .append("** (`")
            .append(finding.file())
            .append(':')
            .append(finding.line())
            .append("`)\n  ")
            .append(finding.explanation())
            .append("\n  _Recommendation:_ ")
            .append(finding.recommendation())
            .append('\n');
      }
    }

    body.append("\n_Advisory only; this reviewer does not approve or block merges._\n");
    return body.toString();
  }

  public String renderFindingComment(final ReviewFinding finding) {
    return "**"
        + finding.severity().toJson()
        + " — "
        + finding.title()
        + "**\n\n"
        + finding.explanation()
        + "\n\n_Recommendation:_ "
        + finding.recommendation()
        + "\n";
  }
}
