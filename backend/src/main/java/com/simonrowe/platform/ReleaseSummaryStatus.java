package com.simonrowe.platform;

/** Where a release's AI summary has got to. */
public enum ReleaseSummaryStatus {

  /** Seeded, not yet summarised. */
  PENDING,

  /** Claimed by a sweep tick. Guards against two ticks summarising the same release. */
  GENERATING,

  /** Summarised. */
  READY,

  /** Gave up after the attempt limit; the entry renders from its commit subject. */
  FAILED
}
