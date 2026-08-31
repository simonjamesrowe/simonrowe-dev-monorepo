package com.simonrowe.factory.logwatch.domain;

/**
 * The two severities in scope, most severe first.
 *
 * <p>Declaration order is load-bearing: {@code compareTo} orders the per-run cap, so
 * {@code ERROR} must be declared before {@code WARN}.
 */
public enum Severity {
  ERROR,
  WARN
}
