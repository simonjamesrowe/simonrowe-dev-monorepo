package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.domain.CiOutcome;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import com.simonrowe.factory.cvefix.github.CveFixPrGateway;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

/** Everything the CVE-fix workflow is not allowed to do itself. */
@ActivityInterface
public interface CveFixActivities {

  /**
   * Looks up whether the CVE-fix branch already has an open pull request.
   *
   * @return the URL of an already-open CVE pull request, or null when there is none
   */
  @ActivityMethod
  String findOpenPrUrl();

  /**
   * Reads Dependency-Track's current findings and drops components already recorded as unfixable.
   *
   * @return the components worth attempting this run, grouped by component
   */
  @ActivityMethod
  List<ComponentFindings> fetchActionableFindings();

  /**
   * Clones, runs the agent, validates the changed paths, commits and pushes, in one activity call.
   *
   * @param components the findings to address, grouped by component
   * @param failureContext CI failure output from the previous attempt, or null on the first
   *     attempt
   * @return what the push produced; a {@code headSha} of null means the agent changed nothing
   */
  @ActivityMethod
  PushResult proposeAndPush(List<ComponentFindings> components, String failureContext);

  /**
   * Renders the title and body from {@code summary}, then opens the pull request.
   *
   * @param summary the inputs for the pull request body
   * @return the opened pull request
   */
  @ActivityMethod
  CveFixPrGateway.OpenPullRequest openPullRequest(FixSummary summary);

  /**
   * Reads the aggregated CI outcome for a commit.
   *
   * @param headSha the commit sha to check
   * @return the aggregated CI outcome
   */
  @ActivityMethod
  CiOutcome checkCi(String headSha);

  /**
   * Reads the failure log excerpt for a commit's failed checks.
   *
   * @param headSha the commit sha to check
   * @return the concatenated failure log excerpt
   */
  @ActivityMethod
  String ciFailureLogs(String headSha);

  /**
   * Posts a comment on the CVE-fix pull request.
   *
   * @param number the pull request number
   * @param body the comment body
   */
  @ActivityMethod
  void commentOnPullRequest(int number, String body);

  /**
   * Persists the components the agent gave up on, so later runs skip them until their finding
   * set changes. {@code components} is this run's Dependency-Track data, from which the stored
   * fingerprint is computed — the agent never supplies one.
   *
   * @param unfixable the components the agent declined to bump, from this run
   * @param components every component with an open finding this run
   */
  @ActivityMethod
  void recordUnfixable(List<UnfixableComponent> unfixable, List<ComponentFindings> components);

  /**
   * Persists the outcome of one CVE-fix run.
   *
   * @param record the run record to persist
   */
  @ActivityMethod
  void recordRun(CveFixRunRecord record);

  /** What one push produced: the commit CI will run against, and what to put in the PR body. */
  record PushResult(String headSha, FixSummary summary) {
  }

  /** The pull request body's inputs. */
  record FixSummary(
      List<String> bumpDescriptions, List<UnfixableComponent> unfixable, String agentSummary) {
  }
}
