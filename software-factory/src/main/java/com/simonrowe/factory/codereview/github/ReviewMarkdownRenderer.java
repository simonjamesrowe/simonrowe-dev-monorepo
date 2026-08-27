package com.simonrowe.factory.codereview.github;

import com.simonrowe.factory.codereview.domain.FindingFingerprint;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Renders the sticky status comment and the per-finding inline comments. */
@Component
public class ReviewMarkdownRenderer {

  /**
   * The bare marker every inline comment carried before findings had identity.
   *
   * <p>Kept only so threads opened before this change are still recognisable as this reviewer's
   * own. They match no fingerprint, so on the first review after deploy they are replied to and
   * resolved — the correct outcome for a pre-change artefact, and one that destroys nothing.
   */
  public static final String LEGACY_FINDING_MARKER = "<!-- temporal-code-review-finding -->";

  /**
   * Matches the marker stamped on every inline comment this reviewer posts, capturing the
   * finding's fingerprint.
   *
   * <p>Defined here, beside the code that writes it, so the gateway that parses identity back out
   * of a thread is reading exactly the string the renderer wrote — one definition, not two that
   * can drift.
   */
  public static final Pattern FINDING_MARKER_PATTERN =
      Pattern.compile("<!-- temporal-code-review-finding:([0-9a-f]{64}) -->");

  /**
   * States what the reviewer now does to a merge.
   *
   * <p>This used to read "Advisory only; this reviewer does not approve or block merges." That
   * became false: the {@code Code Review} check run is a required status, and every other finding
   * holds the merge through required conversation resolution.
   */
  private static final String ADVISORY =
      "\n_Critical findings and reviewer failures turn the `Code Review` check red and block the "
          + "merge. Other findings block until their conversation is resolved — by fixing them, "
          + "or by replying with why they are declined and resolving the thread._\n";

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

  /**
   * Builds the identity-carrying marker for one fingerprint.
   *
   * <p>The fingerprint is what turns "delete everything and repost" into a reconcile: it survives
   * a rebase moving the line and the model re-grading the severity, so the thread a finding opened
   * can be found again on the next push instead of being destroyed and recreated.
   */
  public static String findingMarker(final String fingerprint) {
    return "<!-- temporal-code-review-finding:" + fingerprint + " -->";
  }

  public String renderFindingComment(final ReviewFinding finding) {
    return findingMarker(FindingFingerprint.of(finding))
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
