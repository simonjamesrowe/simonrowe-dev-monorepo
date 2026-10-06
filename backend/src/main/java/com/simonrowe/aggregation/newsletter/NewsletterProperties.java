package com.simonrowe.aggregation.newsletter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the email newsletter sources.
 *
 * @param relevanceThreshold the best cosine similarity to a hearted article a story needs to be
 *     saved without review. 0.50 was calibrated on 2026-10-06 against 36 stories from three TLDR
 *     editions and 51 hearted articles: it accepted 8, the agent and Java stories, and left the
 *     product, hardware and general-science stories for review.
 * @param maxAcceptedPerRun how many stories one source may save per run. Each saved story can
 *     cost an image generation, so this bounds spend however the threshold is set. Stories over
 *     the cap go to review, best-scoring first in.
 * @param lookbackDays how far back each run reads the mailbox. The ledger makes overlap free, so
 *     this only needs to outlast a few missed runs.
 */
@ConfigurationProperties("aggregation.newsletter")
public record NewsletterProperties(
    double relevanceThreshold,
    int maxAcceptedPerRun,
    int lookbackDays
) {

  /** Applies defaults for anything unset. */
  public NewsletterProperties {
    if (relevanceThreshold <= 0) {
      relevanceThreshold = 0.50;
    }
    if (maxAcceptedPerRun <= 0) {
      maxAcceptedPerRun = 15;
    }
    if (lookbackDays <= 0) {
      lookbackDays = 3;
    }
  }
}
