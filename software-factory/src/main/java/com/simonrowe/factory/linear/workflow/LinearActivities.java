package com.simonrowe.factory.linear.workflow;

import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.IssueFiling;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The sink's only activity.
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

  /** Attaches a related URL, such as a guidance pull request, exactly once. */
  @ActivityMethod
  void attachUrl(String issueId, String url, String title);
}
