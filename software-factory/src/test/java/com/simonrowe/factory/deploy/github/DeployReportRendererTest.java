package com.simonrowe.factory.deploy.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.DeployStatus;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncDecision;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import com.simonrowe.factory.deploy.persistence.DeployRunRecord;
import com.simonrowe.factory.deploy.workflow.DeployActivities;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The report's most important job is to say, accurately, whether the site is currently up.
 *
 * <p>Getting that wrong is worse than saying nothing: an operator who reads "the site is up" on a
 * run that left the maintenance page in place will not go and look. So most of this file is about
 * that one sentence.
 */
class DeployReportRendererTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final String LINEAR_URL = "https://linear.app/simonrowe/issue/SIM-9";

  private final DeployReportRenderer renderer = new DeployReportRenderer();

  private static DeployRunRecord record(
      final DeployStatus status,
      final boolean rollbackTaken,
      final DeployStatus rollbackStatus,
      final boolean pageLeftUp,
      final SyncOutcome sync) {
    return new DeployRunRecord(
        "run-1",
        "deploy-prod",
        SHA,
        "workflow_run",
        Instant.parse("2026-08-26T10:00:00Z"),
        Instant.parse("2026-08-26T10:05:00Z"),
        status,
        List.of(
            new PhaseOutcome(DeployPhase.PULL, true, 0, "pulled three images", 1000L),
            new PhaseOutcome(DeployPhase.VERIFY, false, 1, "backend never became healthy", 2000L)),
        sync,
        rollbackTaken,
        rollbackStatus,
        pageLeftUp,
        null,
        null,
        "detail",
        false);
  }

  private static SyncOutcome applied() {
    return new SyncOutcome(
        SyncDecision.APPLIED, "old-sha", SHA, List.of("backend"), List.of(), null, null, "applied");
  }

  private static DeployActivities.Triage triage() {
    return new DeployActivities.Triage(
        "backend never became healthy",
        "compose-ps.txt shows backend `starting` for the whole window.",
        "high",
        "container-startup",
        List.of("backend"),
        List.of("abc1234 — changed the healthcheck"),
        "Run `docker compose logs backend --tail 200` on the host.");
  }

  // ---------------------------------------------------------------------------
  // Is the site up? The one thing that must never be wrong.
  // ---------------------------------------------------------------------------

  @Test
  void saysHumanIsNeededWhenMaintenancePageWasLeftUp() {
    String body =
        renderer.issueBody(
            record(DeployStatus.ROLLBACK_FAILED, true, DeployStatus.ROLLBACK_FAILED, true,
                applied()),
            triage());

    assertThat(body).contains("CAUTION");
    assertThat(body).contains("showing the maintenance page and needs a human");
    // Must not also claim the site is up.
    assertThat(body).doesNotContain("**The site is up.**");
  }

  @Test
  void maintenancePageWarningWinsOverTheStatus() {
    // Belt and braces: if the two ever disagree, the page being up is the fact that matters,
    // because it is the one that means visitors are seeing nothing.
    String body =
        renderer.issueBody(record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, true,
            applied()), triage());

    assertThat(body).contains("needs a human");
  }

  @Test
  void saysTheSiteIsUpOnPreviousVersionAfterCleanRollback() {
    String body =
        renderer.issueBody(
            record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, false, applied()),
            triage());

    assertThat(body).contains("The site is up, on the previous version");
    assertThat(body).doesNotContain("needs a human");
  }

  @Test
  void saysTheBrokenVersionIsStillDeployedWhenRollbackWasDisabled() {
    String body =
        renderer.issueBody(
            record(DeployStatus.ROLLBACK_DISABLED, false, null, true, applied()), triage());

    assertThat(body).contains("needs a human");
  }

  // ---------------------------------------------------------------------------
  // Title
  // ---------------------------------------------------------------------------

  @Test
  void putsTheHeadlineAndShortShaInTheTitle() {
    String title =
        renderer.issueTitle(
            record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, false, applied()),
            triage());

    assertThat(title).isEqualTo("Deploy failed: backend never became healthy (0123456)");
  }

  @Test
  void survivesDiagnosisThatCouldNotBeProduced() {
    // The agent failing must not cost the whole report.
    DeployRunRecord run =
        record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, false, applied());

    assertThat(renderer.issueTitle(run, null)).contains("Deploy failed");
    assertThat(renderer.issueBody(run, null)).contains("What happened");
  }

  // ---------------------------------------------------------------------------
  // Body content
  // ---------------------------------------------------------------------------

  @Test
  void carriesTheDiagnosisTheConfidenceAndTheNextStep() {
    String body =
        renderer.issueBody(
            record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, false, applied()),
            triage());

    assertThat(body)
        .contains("Confidence: high")
        .contains("container-startup")
        .contains("compose-ps.txt shows backend")
        .contains("**Failing services**: backend")
        .contains("abc1234")
        .contains("docker compose logs backend");
  }

  @Test
  void tabulatesEveryPhaseAndMarksTheFailingOne() {
    String body =
        renderer.issueBody(
            record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, false, applied()),
            triage());

    assertThat(body).contains("| `pull` | ok |");
    assertThat(body).contains("| `verify` | **failed** |");
  }

  @Test
  void escapesPipeSoPhaseDetailCannotBreakTheTable() {
    DeployRunRecord run =
        new DeployRunRecord(
            "run-1", "deploy-prod", SHA, "workflow_run",
            Instant.parse("2026-08-26T10:00:00Z"), Instant.parse("2026-08-26T10:05:00Z"),
            DeployStatus.FAILED,
            List.of(new PhaseOutcome(DeployPhase.VERIFY, false, 1, "a | b | c", 1L)),
            applied(), false, null, false, null, null, "detail", false);

    assertThat(renderer.issueBody(run, null)).contains("a \\| b \\| c");
  }

  @Test
  void omitsConfigSectionWhenTheSyncApplied() {
    String body =
        renderer.issueBody(
            record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, false, applied()),
            triage());

    assertThat(body).doesNotContain("Configuration was not synced");
  }

  // ---------------------------------------------------------------------------
  // Held-back configuration
  // ---------------------------------------------------------------------------

  @Test
  void namesTheHeldBackServicesAndTheManualCommand() {
    SyncOutcome heldBack =
        new SyncOutcome(
            SyncDecision.HELD_BACK, null, SHA, List.of("mongodb"), List.of("mongodb"), null,
            "docker compose -f docker-compose.prod.yml up -d mongodb",
            "a change affects a service outside the allowlist");

    String body =
        renderer.issueBody(record(DeployStatus.FAILED, false, null, false, heldBack), null);

    assertThat(body)
        .contains("Configuration was not synced")
        .contains("HELD_BACK")
        .contains("`mongodb`")
        .contains("up -d mongodb");
  }

  @Test
  void explainsMissingEnvironmentVariable() {
    SyncOutcome missing =
        new SyncOutcome(
            SyncDecision.MISSING_VARIABLE, null, SHA, List.of(), List.of(), "NEW_THING", null,
            "needs a variable this host does not define");

    String body =
        renderer.issueBody(record(DeployStatus.FAILED, false, null, false, missing), null);

    assertThat(body).contains("NEW_THING").contains("host-managed and never synced");
  }

  @Test
  void warnsOnSuccessWhenOnlyImagesWereDeployed() {
    // Posted on a SUCCESSFUL deploy, deliberately: "deployed, but not all of it" must not be
    // silent, because that half-applied state is the one this feature exists to make visible.
    SyncOutcome heldBack =
        new SyncOutcome(
            SyncDecision.HELD_BACK, null, SHA, List.of("mongodb"), List.of("mongodb"), null,
            "docker compose up -d mongodb", "held back");

    String comment =
        renderer.partialDeployComment(
            record(DeployStatus.DEPLOYED_IMAGES_ONLY, false, null, false, heldBack));

    assertThat(comment)
        .contains("Deployed — images only")
        .contains("not** applied")
        .contains("mongodb");
  }

  @Test
  void saysNothingAboutPartialDeployWhenTheDeployWasComplete() {
    assertThat(
            renderer.partialDeployComment(
                record(DeployStatus.DEPLOYED, false, null, false, applied())))
        .isNull();
  }

  // ---------------------------------------------------------------------------
  // Commit comment
  // ---------------------------------------------------------------------------

  @Test
  void theCommitCommentIsShortAndNamesTheLinearTicket() {
    String comment =
        renderer.commitComment(
            record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, false, applied()),
            triage(),
            LINEAR_URL);

    assertThat(comment)
        .contains("The site is up, on the previous version")
        .contains("backend never became healthy")
        .contains("Linear")
        .contains(LINEAR_URL);
    // Short: the long form belongs in the ticket.
    assertThat(comment).doesNotContain("## Phases");
  }

  @Test
  void theCommitCommentOmitsTheTicketLinkWhenNothingWasFiled() {
    // Null covers three cases that must all read the same way in the comment: the sink disabled,
    // the filing having failed, and a deploy with nothing to file.
    String comment =
        renderer.commitComment(
            record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, false, applied()),
            triage(),
            null);

    assertThat(comment).doesNotContain("Full diagnosis").doesNotContain("Linear");
  }

  @Test
  void theCommitCommentOmitsTheTicketLinkWhenTheUrlIsBlank() {
    String comment =
        renderer.commitComment(
            record(DeployStatus.ROLLED_BACK, true, DeployStatus.ROLLED_BACK, false, applied()),
            triage(),
            "  ");

    assertThat(comment).doesNotContain("Full diagnosis");
  }
}
