package com.simonrowe.factory.deploy.github;

import com.simonrowe.factory.deploy.domain.DeployStatus;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncDecision;
import com.simonrowe.factory.deploy.persistence.DeployRunRecord;
import com.simonrowe.factory.deploy.workflow.DeployActivities;
import org.springframework.stereotype.Component;

/**
 * Renders the markdown a deploy report is made of.
 *
 * <p>Separate from {@link DeployReportGateway} so the wording is testable without an HTTP call,
 * which matters here: the most important thing this class does is state whether the site is
 * currently up, and that must not be wrong.
 */
@Component
public class DeployReportRenderer {

  /**
   * The issue title, now the Linear issue's title.
   *
   * <p>Unchanged by the move off GitHub Issues: it already rendered exactly what a tracker wants.
   *
   * @param record the run
   * @param triage the diagnosis, or null when none was produced
   * @return a one-line title readable with no other context
   */
  public String issueTitle(final DeployRunRecord record, final DeployActivities.Triage triage) {
    String headline = triage == null ? "Deploy failed" : triage.headline();
    return "Deploy failed: " + headline + " (" + shortSha(record.sha()) + ")";
  }

  /**
   * The issue body.
   *
   * @param record the run
   * @param triage the diagnosis, or null when none was produced
   * @return markdown
   */
  public String issueBody(final DeployRunRecord record, final DeployActivities.Triage triage) {
    StringBuilder body = new StringBuilder();

    // First, before anything else, because it decides whether this needs acting on right now.
    body.append(siteState(record)).append("\n\n");

    body.append("## What happened\n\n");
    body.append("- **Commit**: `").append(record.sha()).append("`\n");
    body.append("- **Trigger**: ").append(record.trigger()).append("\n");
    body.append("- **Outcome**: ").append(record.status()).append("\n");
    if (record.rollbackTaken()) {
      body.append("- **Rollback**: ")
          .append(record.rollbackStatus() == null ? "attempted" : record.rollbackStatus())
          .append("\n");
    }
    body.append("\n");

    if (triage != null) {
      body.append("## Diagnosis\n\n");
      body.append("_Confidence: ")
          .append(triage.confidence())
          .append(" · category: ")
          .append(triage.suspectedCause())
          .append("_\n\n");
      body.append(triage.diagnosis()).append("\n\n");
      if (!triage.failingServices().isEmpty()) {
        body.append("**Failing services**: ")
            .append(String.join(", ", triage.failingServices()))
            .append("\n\n");
      }
      if (!triage.suspectCommits().isEmpty()) {
        body.append("**Suspect commits**\n\n");
        triage.suspectCommits().forEach(commit -> body.append("- ").append(commit).append("\n"));
        body.append("\n");
      }
      if (!triage.suggestedNextStep().isBlank()) {
        body.append("## Suggested next step\n\n")
            .append(triage.suggestedNextStep())
            .append("\n\n");
      }
    }

    body.append(phaseTable(record));
    body.append(configSyncSection(record));

    body.append("\n---\n\n");
    body.append(
        "Opened automatically by the `deployer`. Deploy history is in the `deploy_runs` "
            + "collection of the `software_factory` database, and the run itself is in the "
            + "Temporal UI while it is inside the retention window.\n");
    return body.toString();
  }

  /**
   * The commit comment: the same facts, shorter, because it is read in passing.
   *
   * <p>This is the in-context breadcrumb on the merge that broke production, and the one part of
   * the report that stayed on GitHub. It names the Linear ticket rather than repeating its body.
   *
   * <p>It also carries {@link #partialDeployComment}, and that is not a tidy-up. {@code
   * DeployWorkflowImpl.finish} posts a commit comment on success for exactly one status —
   * {@code DEPLOYED_IMAGES_ONLY} — so the comment's whole reason to exist is saying which
   * configuration did not get applied. But it called only this method, and with no triage and no
   * Linear ticket on that path this rendered the bare {@code siteState} line and nothing else:
   * "The site is up." {@code partialDeployComment} was reachable only from its own tests.
   *
   * <p>Production ran that way from 2026-08-28. {@code deployer} is deliberately outside the
   * recreate allowlist, so the commit that changed its service definition (#130) held the
   * fast-forward back — and, because the held-back comparison is against the checkout rather
   * than the previous target, kept holding it back on every merge after it. Three consecutive
   * deploys applied images against a frozen checkout, each reporting "The site is up." Under the
   * frontend's old nginx bind mount that shipped a route to production that silently 404'd.
   *
   * @param record the run
   * @param triage the diagnosis, or null when none was produced
   * @param linearIssueUrl the Linear issue this run filed, or null when nothing was filed
   * @return markdown
   */
  public String commitComment(
      final DeployRunRecord record,
      final DeployActivities.Triage triage,
      final String linearIssueUrl) {
    StringBuilder body = new StringBuilder();
    body.append(siteState(record)).append("\n\n");
    String partial = partialDeployComment(record);
    if (partial != null) {
      body.append(partial);
    }
    if (triage != null) {
      body.append("**").append(triage.headline()).append("**\n\n");
      if (!triage.suggestedNextStep().isBlank()) {
        body.append(triage.suggestedNextStep()).append("\n\n");
      }
    }
    if (linearIssueUrl != null && !linearIssueUrl.isBlank()) {
      body.append("Full diagnosis, tracked in Linear: ").append(linearIssueUrl).append("\n");
    }
    return body.toString();
  }

  /**
   * A comment for a deploy that succeeded but did not apply everything.
   *
   * <p>Posted on success, deliberately. "Deployed, but not all of it" must not be silent — that is
   * exactly the half-applied state this feature exists to make impossible to miss.
   *
   * <p>Reached through {@link #commitComment}, which is the only method the deploy activity
   * calls. Keep it that way: while this was a second public method nobody invoked, the notice
   * it carries never once reached a commit — it covered every images-only deploy auto-deploy
   * ever performed, from the feature going live to 2026-08-29.
   *
   * @param record the run
   * @return markdown, or null when there is nothing worth saying
   */
  public String partialDeployComment(final DeployRunRecord record) {
    if (record.configSync() == null
        || record.status() != DeployStatus.DEPLOYED_IMAGES_ONLY) {
      return null;
    }
    StringBuilder body = new StringBuilder();
    body.append("### Deployed — images only\n\n");
    body.append(
        // `frontend/nginx.conf` is deliberately NOT in this list any more: it ships inside the
        // frontend image, so a frozen checkout no longer freezes the SPA's routing with it.
        // It used to be bind-mounted, and naming a file here that is in fact live would send a
        // reader looking in the wrong place.
        "The new images for this commit are running, but the host-side configuration was "
            + "**not** applied, so anything this commit changed in `docker-compose.prod.yml`, "
            + "`config/nginx/` or `scripts/` is not live. Until this is resolved every later "
            + "merge deploys images only too, because the held-back comparison is against this "
            + "checkout rather than against the previous deploy.\n\n");
    body.append(configSyncSection(record));
    return body.toString();
  }

  private String siteState(final DeployRunRecord record) {
    if (record.maintenancePageLeftUp()) {
      return "> [!CAUTION]\n"
          + "> **The site is showing the maintenance page and needs a human.** The rollback did "
          + "not verify clean, so the page was deliberately left up rather than exposing a "
          + "broken site.";
    }
    return switch (record.status()) {
      case ROLLED_BACK ->
          "> [!WARNING]\n"
              + "> **The site is up, on the previous version.** This commit was deployed, failed "
              + "verification, and was rolled back automatically.";
      case ROLLBACK_DISABLED ->
          "> [!CAUTION]\n"
              + "> **Rollback was disabled, so this commit is still deployed and failing.**";
      case DEPLOYED, DEPLOYED_IMAGES_ONLY -> "> [!NOTE]\n> **The site is up.**";
      default ->
          "> [!WARNING]\n"
              + "> **The deploy failed.** Check the site and the container states before "
              + "assuming either way.";
    };
  }

  private String phaseTable(final DeployRunRecord record) {
    if (record.phases().isEmpty()) {
      return "";
    }
    StringBuilder table = new StringBuilder("## Phases\n\n| Phase | Result | Detail |\n");
    table.append("| --- | --- | --- |\n");
    for (PhaseOutcome outcome : record.phases()) {
      table
          .append("| `")
          .append(outcome.phase().name().toLowerCase(java.util.Locale.ROOT))
          .append("` | ")
          .append(outcome.succeeded() ? "ok" : outcome.declined() ? "declined" : "**failed**")
          .append(" | ")
          .append(firstLine(outcome.detail()))
          .append(" |\n");
    }
    return table.append("\n").toString();
  }

  private String configSyncSection(final DeployRunRecord record) {
    if (record.configSync() == null) {
      return "";
    }
    SyncDecision decision = record.configSync().decision();
    if (decision == SyncDecision.APPLIED || decision == SyncDecision.ALREADY_CURRENT) {
      return "";
    }

    StringBuilder section = new StringBuilder("## Configuration was not synced\n\n");
    section.append("**Decision**: `").append(decision).append("`");
    if (record.configSync().detail() != null && !record.configSync().detail().isBlank()) {
      section.append(" — ").append(record.configSync().detail());
    }
    section.append("\n\n");

    if (!record.configSync().heldBackServices().isEmpty()) {
      section.append(
          "These services are affected by a configuration change in this commit and are "
              + "**not** on the allowlist the automation may recreate, so nothing was applied "
              + "at all rather than applying it half way:\n\n");
      record
          .configSync()
          .heldBackServices()
          .forEach(service -> section.append("- `").append(service).append("`\n"));
      section.append("\n");
    }
    if (record.configSync().missingVariable() != null) {
      section.append(
          "The new compose file references an environment variable the host's `.env` does not "
              + "define: `");
      section.append(record.configSync().missingVariable());
      section.append(
          "`. `.env` is host-managed and never synced, so this needs adding by hand before the "
              + "configuration can be applied.\n\n");
    }
    if (record.configSync().manualCommand() != null) {
      section.append("Apply it by hand on the host with:\n\n```bash\n");
      section.append(record.configSync().manualCommand()).append("\n```\n");
    }
    return section.toString();
  }

  private static String firstLine(final String detail) {
    if (detail == null || detail.isBlank()) {
      return "";
    }
    String[] lines = detail.strip().split("\n");
    // The LAST line, not the first: a failing phase's reason is at the end of its output.
    String line = lines[lines.length - 1].strip();
    // Escaped, because a table cell cannot contain a raw pipe.
    line = line.replace("|", "\\|");
    return line.length() > 160 ? line.substring(0, 160) + "…" : line;
  }

  private static String shortSha(final String sha) {
    if (sha == null) {
      return "unknown";
    }
    return sha.length() > 7 ? sha.substring(0, 7) : sha;
  }
}
