package com.simonrowe.summary;

/**
 * Published in-process when a summary has just been written.
 *
 * <p>Fired only by a generation that finished, never by a request that found a summary
 * already there, so a listener sees each summary once. It is how work that waits on a
 * summary (the playlist making audio of one it was asked to voice) hears that it can go
 * ahead, without the summary package knowing who is waiting.
 *
 * @param articleId the aggregated article id
 */
public record ArticleSummaryReadyEvent(String articleId) {
}
