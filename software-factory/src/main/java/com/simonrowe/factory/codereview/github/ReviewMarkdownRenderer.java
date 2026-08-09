package com.simonrowe.factory.codereview.github;

import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import org.springframework.stereotype.Component;

/** Renders one compact, updateable pull-request comment. */
@Component
public class ReviewMarkdownRenderer {

  public String render(final ReviewReport report, final String marker) {
    StringBuilder body = new StringBuilder();
    body.append(marker).append("\n");
    body.append("## Automated code review\n\n");
    body.append(report.summary()).append("\n\n");
    body.append("**Verdict:** `").append(report.verdict().toJson()).append("`\n");

    if (report.findings().isEmpty()) {
      body.append("\nNo actionable findings.\n");
    } else {
      body.append("\n### Findings\n");
      for (ReviewFinding finding : report.findings()) {
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

  public String renderFailure(final String reason, final String marker) {
    return marker
        + "\n"
        + "## Automated code review\n\n"
        + "This review did not complete, so these changes have **not** been reviewed.\n\n"
        + "```\n"
        + fenceSafe(reason)
        + "\n```\n"
        + "\n_Advisory only; this reviewer does not approve or block merges._\n";
  }

  /** Keeps an arbitrary failure string from breaking out of the code fence around it. */
  private static String fenceSafe(final String reason) {
    if (reason == null || reason.isBlank()) {
      return "No failure detail was reported.";
    }
    return reason.replace("`", "'");
  }
}
