package com.simonrowe.factory.codereview.domain;

/**
 * Whether the reviewer armed GitHub auto-merge on a pull request, and why or why not.
 *
 * <p>The reviewer decides <em>whether</em>; GitHub decides <em>when</em>. Arming hands the merge
 * to GitHub, which performs it only once the {@code main} ruleset is satisfied — every required
 * check green, every conversation resolved. So {@link Outcome#ARMED} means "will merge when the
 * gate opens", never "merged".
 *
 * @param outcome what happened
 * @param reason for anything but {@code ARMED}/{@code ELIGIBLE}, the one condition that stopped
 *     it, phrased to follow "not armed: "
 */
public record MergeDecision(Outcome outcome, String reason) {

  /** The five things that can happen. */
  public enum Outcome {
    /** {@code factory.codereview.auto-merge.enabled} is off; the reviewer says nothing. */
    OFF,
    /** Every rule passed, but this was a dry run, so nothing was armed. */
    ELIGIBLE,
    /** Auto-merge is armed on the reviewed commit. */
    ARMED,
    /** A rule failed; {@link #reason()} names it. */
    INELIGIBLE,
    /** Every rule passed, or could not be checked, and arming failed; nothing is armed. */
    ARM_FAILED
  }

  public static MergeDecision off() {
    return new MergeDecision(Outcome.OFF, null);
  }

  public static MergeDecision eligible() {
    return new MergeDecision(Outcome.ELIGIBLE, null);
  }

  public static MergeDecision armed() {
    return new MergeDecision(Outcome.ARMED, null);
  }

  public static MergeDecision ineligible(final String reason) {
    return new MergeDecision(Outcome.INELIGIBLE, reason);
  }

  public static MergeDecision armFailed(final String reason) {
    return new MergeDecision(Outcome.ARM_FAILED, reason);
  }

  /** One line for the run's progress detail and the review summary; null when switched off. */
  public String describe() {
    return switch (outcome) {
      case OFF -> null;
      case ELIGIBLE -> "would arm (dry run)";
      case ARMED -> "armed";
      case INELIGIBLE -> "not armed: " + reason;
      case ARM_FAILED -> "not armed: arming failed (" + reason + ")";
    };
  }
}
