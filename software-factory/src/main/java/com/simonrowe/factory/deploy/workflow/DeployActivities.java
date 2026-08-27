package com.simonrowe.factory.deploy.workflow;

import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import com.simonrowe.factory.deploy.persistence.DeployRunRecord;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

/**
 * Everything the deploy workflow is not allowed to do itself.
 *
 * <p>The implementation of this interface is the whole of the deployer's privilege. It is gated
 * on {@code factory.deploy.enabled}, so it exists only in the {@code deployer} JVM — which is why
 * {@code software-factory}, whose only route in is a public webhook, cannot execute a deploy step
 * even though it registers a poller on the same task queue.
 */
@ActivityInterface
public interface DeployActivities {

  /**
   * Runs one phase of {@code scripts/restart-prod.sh}.
   *
   * <p>Must be idempotent, because Temporal retries activities. The script guarantees that; see
   * specs/036-auto-deploy-on-merge/contracts/restart-prod-phases.md.
   *
   * @param phase the phase to run
   * @param imageTag the tag {@code pull} should fetch, or null for the script's default
   * @param dryRun whether to run the script with {@code DRY_RUN=1}
   * @return what the phase did, including its exit code
   */
  @ActivityMethod
  PhaseOutcome runPhase(DeployPhase phase, String imageTag, boolean dryRun);

  /**
   * Fast-forwards the deploy directory to {@code targetSha}, if that is provably safe.
   *
   * <p>Never throws for a decision the deploy is meant to survive — a dirty tree, a target that
   * is not on the mainline, a change affecting a non-allowlisted service, or a compose file
   * needing a variable the host does not define all come back as a {@link SyncOutcome} whose
   * decision says so. Only a git or fetch error is a failure.
   *
   * @param targetSha the commit to move to
   * @param dryRun whether to run the script with {@code DRY_RUN=1}
   * @return what was decided, and the previous commit when {@code HEAD} moved
   */
  @ActivityMethod
  SyncOutcome syncConfig(String targetSha, boolean dryRun);

  /**
   * Resets the deploy directory back to {@code previousSha}.
   *
   * <p>Called only on the rollback path, and only when {@link SyncOutcome} reported that {@code
   * HEAD} actually moved. Restoring the commit before rolling the images back is what makes the
   * rollback run the <em>previous</em> version of {@code restart-prod.sh} — which is what matters
   * when the thing that broke the deploy was a change to the script itself.
   *
   * @param previousSha the commit recorded before the deploy
   * @param dryRun whether to run the script with {@code DRY_RUN=1}
   * @return what the phase did
   */
  @ActivityMethod
  PhaseOutcome rollbackConfig(String previousSha, boolean dryRun);

  /**
   * Captures the evidence a failure diagnosis is built from, into a scratch directory.
   *
   * <p>Container logs, container states, the failing phase's output and the commit range. Bounded
   * per file, so a crash-looping container cannot fill the volume.
   *
   * @param failedPhase the phase that failed
   * @param phaseOutput that phase's captured output
   * @param previousSha the commit deployed before this run, or null when unknown
   * @param targetSha the commit this run deployed
   * @return an opaque handle to the evidence directory, for {@link #triage}
   */
  @ActivityMethod
  String captureEvidence(
      DeployPhase failedPhase, String phaseOutput, String previousSha, String targetSha);

  /**
   * Has the agent explain the captured evidence.
   *
   * <p>The agent is given {@code Read} against the evidence directory and <strong>no {@code Bash}
   * tool</strong>. It never touches Docker, git or a credential — it is handed captured output and
   * asked to explain it, exactly as the CVE-fix flow hands over Dependency-Track findings.
   *
   * @param evidenceDirectory the handle returned by {@link #captureEvidence}
   * @return the rendered diagnosis, or a fallback message when the agent could not produce one
   */
  @ActivityMethod
  Triage triage(String evidenceDirectory);

  /**
   * Posts the diagnosis as a comment on the deployed commit.
   *
   * <p>The tracked issue is no longer GitHub's: it is filed into Linear before this runs, which is
   * why the ticket's URL arrives as a parameter rather than being produced here. The order is
   * deliberate — the comment names the ticket, so the ticket has to exist first.
   *
   * @param record the run so far, which supplies every fact the report needs
   * @param triage the diagnosis, or null when none was produced
   * @param installationId the GitHub App installation, or null to resolve it at run time
   * @param linearIssueUrl the Linear issue this run filed, or null when nothing was filed
   * @return the URL of what was posted
   */
  @ActivityMethod
  Report report(
      DeployRunRecord record, Triage triage, Long installationId, String linearIssueUrl);

  /**
   * Renders a failure for the issue sink.
   *
   * <p>The title and body are the existing {@code DeployReportRenderer.issueTitle} and {@code
   * issueBody} — re-targeted from GitHub to Linear, not rewritten. Rendering is an activity
   * because a {@code @WorkflowImpl} holds no Spring bean and cannot reach the renderer.
   *
   * <p>It deliberately does NOT supply the fingerprint key parts. Those are the failing phase and
   * the deploy status, both already in workflow scope as parameters of {@code reportAndFinish} —
   * structured enum values rather than agent prose, which is what a fingerprint requires.
   *
   * @param record the run so far
   * @param triage the agent's diagnosis, which may be null
   * @return the rendered title and body
   */
  @ActivityMethod
  Rendered renderFailure(DeployRunRecord record, Triage triage);

  /**
   * Persists the outcome of one deploy.
   *
   * @param record the run record to upsert
   */
  @ActivityMethod
  void recordRun(DeployRunRecord record);

  /**
   * Deletes an evidence directory once the run has finished with it.
   *
   * @param evidenceDirectory the handle returned by {@link #captureEvidence}
   */
  @ActivityMethod
  void discardEvidence(String evidenceDirectory);

  /**
   * The agent's diagnosis of a failed deploy.
   *
   * @param headline one line naming the failing component and the symptom; the issue title
   * @param diagnosis markdown, resting on the quoted evidence
   * @param confidence {@code high} / {@code medium} / {@code low}; {@code low} is a legitimate
   *     answer when the evidence does not identify a cause
   * @param suspectedCause coarse category, so a run of these can be counted over time
   * @param failingServices compose services the evidence shows as unhealthy, exited or created
   * @param suspectCommits commits from the range that plausibly caused this
   * @param suggestedNextStep one concrete action for the operator
   */
  record Triage(
      String headline,
      String diagnosis,
      String confidence,
      String suspectedCause,
      List<String> failingServices,
      List<String> suspectCommits,
      String suggestedNextStep) {

    public Triage {
      failingServices = failingServices == null ? List.of() : List.copyOf(failingServices);
      suspectCommits = suspectCommits == null ? List.of() : List.copyOf(suspectCommits);
    }

    /** The diagnosis to report when the agent itself failed. */
    public static Triage unavailable(final String reason) {
      return new Triage(
          "Deploy failed, and the automated diagnosis could not be produced",
          "The triage agent did not return a diagnosis: " + reason,
          "low",
          "unknown",
          List.of(),
          List.of(),
          "Read the deploy run's container logs directly, and check the Temporal UI for the "
              + "failing phase.");
    }
  }

  /**
   * What reporting posted.
   *
   * @param commitCommentUrl the comment on the deployed commit
   */
  record Report(String commitCommentUrl) {
  }

  /**
   * A failure rendered for filing.
   *
   * @param title the issue title, agent prose — never part of the fingerprint
   * @param body the issue description, in Markdown
   */
  record Rendered(String title, String body) {
  }
}
