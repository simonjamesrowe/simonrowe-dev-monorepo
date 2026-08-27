package com.simonrowe.factory.linear.domain;

import java.util.Locale;

/**
 * Linear's {@code WorkflowState.type} values, plus the open/closed distinction the filing decision
 * turns on.
 *
 * <p>Declining an issue from Triage sets {@code canceled}; finishing it sets {@code completed}.
 * Using Linear's own semantics means the way a human triages tickets <em>is</em> the control
 * surface, with no extra concepts to configure.
 */
public enum IssueStateType {
  /** Machine-filed, awaiting a human's accept or decline. */
  TRIAGE(true),
  /** Accepted, not scheduled. */
  BACKLOG(true),
  /** Scheduled, not started. */
  UNSTARTED(true),
  /** In progress. */
  STARTED(true),
  /** Fixed. A recurrence is a regression. */
  COMPLETED(false),
  /** Declined — "not a bug, never tell me again". */
  CANCELED(false),
  /**
   * Declined as a duplicate of another issue.
   *
   * <p>Linear sets {@code canceledAt} (not {@code completedAt}) when an issue moves to a
   * {@code duplicate}-type state, so this is treated as a decline in the same precedence band as
   * {@link #CANCELED}. Moving an issue into this state requires a duplicate issue relation to
   * already exist between it and another issue.
   */
  DUPLICATE(false),
  /**
   * A state type this code does not recognise.
   *
   * <p>Treated as open on purpose: if Linear adds a type, the safe failure is "comment on the
   * existing issue", not "file another one".
   */
  UNKNOWN(true);

  private final boolean open;

  IssueStateType(final boolean open) {
    this.open = open;
  }

  /**
   * Whether an issue in this state is still being tracked.
   *
   * @return true when the issue is open
   */
  public boolean open() {
    return open;
  }

  /**
   * Maps a Linear state type string onto this enum.
   *
   * @param linearType the {@code state.type} value from the API, may be null
   * @return the matching constant, or {@link #UNKNOWN}
   */
  public static IssueStateType from(final String linearType) {
    if (linearType == null) {
      return UNKNOWN;
    }
    // Linear spells it "canceled"; accept the British spelling too so a hand-written fixture or a
    // future API change cannot silently downgrade a suppression to a regression.
    String normalised = linearType.trim().toLowerCase(Locale.ROOT);
    if ("cancelled".equals(normalised)) {
      return CANCELED;
    }
    for (IssueStateType candidate : values()) {
      if (candidate != UNKNOWN && candidate.name().toLowerCase(Locale.ROOT).equals(normalised)) {
        return candidate;
      }
    }
    return UNKNOWN;
  }
}
