package com.simonrowe.summary;

/**
 * One summary's state change, pushed to every open news page.
 *
 * <p>Carries the article id and the state only, never the body. Summaries are public, so
 * the topic needs no authorisation, but a body would push several kilobytes to every
 * visitor for each summary written. A page that wants the text fetches it.
 *
 * @param articleId the aggregated article id
 * @param state the state the summary has just entered
 */
public record SummaryStatusUpdate(String articleId, ArticleSummaryResponse.PublicState state) {
}
