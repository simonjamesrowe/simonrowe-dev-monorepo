package com.simonrowe.factory.codereview.github;

import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import java.util.List;
import org.springframework.stereotype.Component;

/** Renders the sticky status comment and the per-finding inline comments. */
@Component
public class ReviewMarkdownRenderer {

  /**
   * Stamped on every inline comment this reviewer posts.
   *
   * <p>It is what lets the next push tell its own stale findings apart from a human's reply and
   * delete only the former. Without it, findings accumulate on the pull request push after push.
   */
  public static final String FINDING_MARKER = "<!-- temporal-code-review-finding -->";

  private static final String ADVISORY =
      "\n_Advisory only; this reviewer does not approve or block merges._\n";

  /**
   * Renders the single comment that carries the whole review outcome.
   *
   * <p>This body is edited in place on every push rather than posted anew, so it names the commit
   * it describes: a reader arriving later cannot otherwise tell whether it is about the code in
   * front of them.
   */
  public String renderSummary(
      final ReviewReport report,
      final String marker,
      final String headSha,
      final List<ReviewFinding> inlineFallback) {
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

    body.append(reviewedAt(headSha));
    body.append(ADVISORY);
    return body.toString();
  }

  public String renderFindingComment(final ReviewFinding finding) {
    return FINDING_MARKER
        + "\n**"
        + finding.severity().toJson()
        + " — "
        + finding.title()
        + "**\n\n"
        + finding.explanation()
        + "\n\n_Recommendation:_ "
        + finding.recommendation()
        + "\n";
  }

  public String renderAck(final String marker, final String headSha) {
    return marker
        + "\n"
        + "## Automated code review\n\n"
        + "🔄 A review of these changes is **in progress**.\n\n"
        + "This comment is replaced by the review when it finishes, or by the reason it did not.\n"
        + reviewedAt(headSha)
        + ADVISORY;
  }

  public String renderFailure(
      final ReviewFailure failure,
      final String marker,
      final String headSha,
      final String temporalUiBaseUrl) {
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
        + reviewedAt(headSha)
        + ADVISORY;
  }

  /** Names the commit this comment is about, or says nothing when there is no commit to name. */
  private static String reviewedAt(final String headSha) {
    if (headSha == null || headSha.isBlank()) {
      return "";
    }
    return "\n_Commit `" + (headSha.length() > 7 ? headSha.substring(0, 7) : headSha) + "`._\n";
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
