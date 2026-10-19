package com.simonrowe.factory.ticketagent.domain;

/**
 * How a ticket run ended.
 *
 * <p>A hand-back is a run that worked: the agent correctly gave the ticket back to a person. Only
 * a crashed run fails in Temporal, so the console's failed count means something broke.
 */
public enum TicketAgentOutcome {
  /** The pull request merged and the ticket is done. */
  MERGED,
  /** The pull request is green but needs a person to merge it. */
  IN_REVIEW,
  /** The agent gave the ticket back: no change was warranted, or it ran out of attempts. */
  HANDED_BACK,
  /** A dry run; the would-be diff was posted to the ticket. */
  DRY_RUN,
  /** The ticket was not one the agent may work on when the run began. */
  NOT_ELIGIBLE
}
