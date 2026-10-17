package com.simonrowe.factory.writing.api;

/**
 * A writing job's state as callers see it.
 *
 * <p>Coarser than Temporal's execution status on purpose: a caller only needs to know whether to
 * wait, use the text, or fall back. {@code TIMED_OUT}, {@code TERMINATED} and {@code CANCELED}
 * all mean "fall back", so they all read as {@code FAILED}.
 */
public enum WritingJobState {
  RUNNING,
  COMPLETED,
  FAILED,
  NOT_FOUND
}
