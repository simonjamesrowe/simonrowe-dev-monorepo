package com.simonrowe.factory.codereview.github;

import com.simonrowe.factory.codereview.domain.ReviewFailure;
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

  /** Keeps an arbitrary failure string from breaking out of the code fence around it. */
  private static String fenceSafe(final String reason) {
    if (reason == null || reason.isBlank()) {
      return "No failure detail was reported.";
    }
    return reason.replace("`", "'");
  }
}
