package com.simonrowe.factory.pageread.api;

/**
 * A read's state as the backend sees it: use the answer, or fall back.
 *
 * <p>{@code RUNNING} means the wait ran out first; the backend falls back rather than waiting,
 * and the next request for the same address gets the finished read. {@code TIMED_OUT},
 * {@code TERMINATED} and {@code CANCELED} all read as {@code FAILED}.
 */
public enum PageReadState {
  COMPLETED,
  RUNNING,
  FAILED
}
