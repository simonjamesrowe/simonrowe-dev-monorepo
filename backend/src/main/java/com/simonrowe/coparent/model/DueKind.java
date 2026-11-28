package com.simonrowe.coparent.model;

/** What a document's {@code dueOn} date means. Present exactly when {@code dueOn} is. */
public enum DueKind {
  /** The document stops being valid on that date, like a passport. */
  EXPIRES,
  /** The document should be looked at again by that date, like a care plan. */
  REVIEW
}
