package com.simonrowe.aggregation;

/**
 * A news source and how many visible articles it holds.
 *
 * <p>The count drives the news page's filter pills: sources sort by volume, and the
 * long tail of one- and two-article sources collapses behind a "More" overflow rather
 * than crowding the row.
 *
 * @param name  the source name as stored on each article
 * @param count how many visible articles carry that name
 */
public record SourceSummary(String name, long count) {
}
