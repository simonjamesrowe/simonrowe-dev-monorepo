package com.simonrowe.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/** Bounded operation results: no provider messages, content IDs or exception text. */
public enum OperationOutcome {
  SUCCESS(false), SKIPPED(false), UNAVAILABLE(false), QUEUED(false),
  STALE(false), REJECTED(false), FAILED(true), UNCERTAIN(true);

  private final boolean error;

  OperationOutcome(final boolean error) {
    this.error = error;
  }

  /** Apply only inside the meaningful operation's own observation scope. */
  public void record() {
    Span span = Span.current();
    span.setAttribute("app.outcome", name().toLowerCase(java.util.Locale.ROOT));
    if (error) {
      span.setStatus(StatusCode.ERROR);
      span.setAttribute("error.type", "operation." + name().toLowerCase(java.util.Locale.ROOT));
    }
  }

  /** The exception's class is useful context; its message and stack are not exported here. */
  public static void failed(final Throwable failure) {
    FAILED.record();
    Span.current().setAttribute("error.type", failure.getClass().getName());
  }
}
