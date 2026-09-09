package com.simonrowe.school.model;

/**
 * Which audience a piece of school content may be served to.
 *
 * <p>There is deliberately no {@code UNKNOWN} or {@code PENDING} member. Every access decision
 * reads this one value, and a third state would force every reader to decide what to do with it —
 * which is exactly the kind of choice that gets made differently in two places and leaks. Content
 * awaiting a decision is {@link #RESTRICTED} and carries its proposal on a separate field.
 */
public enum Visibility {

  /**
   * Servable to anyone, including unauthenticated visitors. Only ever reached through explicit
   * human approval — never by a classifier, a default, or the absence of a classification.
   */
  PUBLIC,

  /**
   * Servable only to a request carrying the school role. This is the default for everything, and
   * the only safe value to fall back to.
   */
  RESTRICTED
}
