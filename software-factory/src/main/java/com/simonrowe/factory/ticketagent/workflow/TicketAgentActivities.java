package com.simonrowe.factory.ticketagent.workflow;

import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.ticketagent.domain.AttemptRequest;
import com.simonrowe.factory.ticketagent.domain.AttemptResult;
import com.simonrowe.factory.ticketagent.domain.ChecksReport;
import com.simonrowe.factory.ticketagent.domain.PullRequestRef;
import com.simonrowe.factory.ticketagent.domain.PullRequestState;
import com.simonrowe.factory.ticketagent.domain.TicketAgentSettings;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** The ticket agent's side effects, all on {@code software-factory}. */
@ActivityInterface
public interface TicketAgentActivities {

  @ActivityMethod
  TicketAgentSettings settings();

  /** The ticket, or null when Linear has no such issue. */
  @ActivityMethod
  LinearTicket loadTicket(String identifier);

  /** Moves the ticket to In Progress and says which branch the agent will use. */
  @ActivityMethod
  void claim(String issueId, String branch);

  /** One agent run: clone, edit, check the paths, and push (or, on a dry run, diff). */
  @ActivityMethod
  AttemptResult attempt(AttemptRequest request);

  /** Opens the pull request, or finds the open one, and attaches it to the ticket. */
  @ActivityMethod
  PullRequestRef openPullRequest(
      String issueId, String branch, String title, String body, String ticketUrl);

  /**
   * Labels {@code no-auto-merge} for silencing or any build/dependency file in the full PR diff.
   * The activity name stays stable for Temporal histories recorded before dependency edits.
   */
  @ActivityMethod
  void markSilencing(int pullNumber, boolean silencing);

  @ActivityMethod
  ChecksReport readChecks(String headSha);

  @ActivityMethod
  String failureBrief(ChecksReport checks, int pullNumber);

  @ActivityMethod
  PullRequestState readPullRequest(int pullNumber);

  @ActivityMethod
  void comment(String issueId, String body);

  /** Moves the ticket to In Review, with a comment saying who merges it. */
  @ActivityMethod
  void moveToReview(String issueId, String body);

  /** Comments and moves the ticket to Done. */
  @ActivityMethod
  void complete(String issueId, String body);

  /**
   * Gives the ticket back: comment, remove the hand-over label, add the stuck label when asked,
   * move it to Todo, and park the pull request as a draft if there is one.
   */
  @ActivityMethod
  void handBack(String issueId, String body, boolean stuck, String pullRequestNodeId);

  /** The intake: start runs for labelled tickets, up to the in-flight cap. */
  @ActivityMethod
  int dispatch();
}
