package com.simonrowe.factory.linear.workflow;

import com.simonrowe.factory.linear.domain.AbsenceSweep;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.SweepReport;
import com.simonrowe.factory.linear.domain.LinkedIssue;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

/**
 * The sink's activities.
 *
 * <p>Scheduled by producer workflows with {@code ActivityOptions.setTaskQueue(
 * LinearTaskQueues.LINEAR)}, so the filing executes in whichever container polls that queue —
 * {@code software-factory} — rather than in the container that ran the producing workflow. That is
 * what keeps {@code LINEAR_API_KEY} off the {@code deployer}, which holds the Docker socket.
 */
@ActivityInterface
public interface LinearActivities {

  /**
   * Files one occurrence into Linear, exactly once per distinct problem.
   *
   * @param filing the occurrence
   * @return what was done
   */
  @ActivityMethod
  FiledIssue fileIssue(IssueFiling filing);

  /**
   * Closes the issues a producer has stopped reporting.
   *
   * <p>Separate from {@link #fileIssue} rather than folded into it, because it answers the
   * opposite question and is scheduled at a different moment: filing is per-problem and happens
   * while a producer is enumerating what it found, whereas a sweep is per-run and happens once,
   * afterwards, when the producer knows its observation was complete.
   *
   * @param sweep the request
   * @return what was done
   */
  @ActivityMethod
  SweepReport sweepResolved(AbsenceSweep sweep);

  /** Attaches a related URL, such as a guidance pull request, exactly once. */
  @ActivityMethod
  void attachUrl(String issueId, String url, String title);

  /**
   * Every issue a URL is attached to, with each one's attachments.
   *
   * @param url the attached URL
   * @return the issues; empty when none
   */
  @ActivityMethod
  List<LinkedIssue> issuesLinkedTo(String url);

  /**
   * Comments on an issue. Honours {@code factory.linear.dry-run}.
   *
   * @param issueId the Linear issue UUID
   * @param body the comment, in Markdown
   */
  @ActivityMethod
  void commentOn(String issueId, String body);

  /**
   * Comments on an issue and moves it to the team's completed state. Honours {@code
   * factory.linear.dry-run}.
   *
   * @param issueId the Linear issue UUID
   * @param body the comment explaining why it is done, in Markdown
   * @return true when it was moved; false when the team has no completed state or this is a
   *     dry run
   */
  @ActivityMethod
  boolean complete(String issueId, String body);
}
