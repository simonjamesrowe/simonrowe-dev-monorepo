package com.simonrowe.factory.deploy.domain;

/**
 * What configuration sync decided.
 *
 * <p>Only {@link #APPLIED} moved {@code HEAD}, and therefore only {@link #APPLIED} makes the
 * rollback path restore the previous commit. That one invariant is why the decision is an enum
 * rather than a boolean plus a reason string — the two could disagree.
 */
public enum SyncDecision {
  /** {@code HEAD} was fast-forwarded to the target commit. */
  APPLIED,
  /**
   * {@code HEAD} was already the target commit. A success and a no-op — this is what a rehearsal
   * deploy of the version already in production reports, and it must not read as a failure.
   */
  ALREADY_CURRENT,
  /** {@code factory.deploy.sync-config} is false. */
  DISABLED,
  /** A tracked file in the checkout is modified — someone is working on the box. */
  DIRTY_TREE,
  /** The target commit is not an ancestor of the fetched mainline. */
  NOT_AN_ANCESTOR,
  /** The change affects a service outside the recreate allowlist. */
  HELD_BACK,
  /** The candidate compose file references a variable the host's env file does not define. */
  MISSING_VARIABLE,
  /** A git or fetch error. */
  FAILED;

  /** Whether this decision moved {@code HEAD}, and therefore needs restoring on rollback. */
  public boolean movedHead() {
    return this == APPLIED;
  }

  /** Whether the deploy should continue with images only rather than stopping. */
  public boolean deployImagesAnyway() {
    return this != FAILED;
  }
}
