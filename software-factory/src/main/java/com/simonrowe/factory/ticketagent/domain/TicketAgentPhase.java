package com.simonrowe.factory.ticketagent.domain;

/** Where a ticket run is. The console's drawer shows it beside the attempt count. */
public enum TicketAgentPhase {
  ACCEPTED,
  CLAIMING,
  IMPLEMENTING,
  WAITING_FOR_CHECKS,
  REPAIRING,
  AWAITING_MERGE,
  MERGED,
  IN_REVIEW,
  HANDED_BACK,
  DRY_RUN,
  FAILED
}
