package com.simonrowe.factory.pageread.domain;

/**
 * The {@code readPage} activity's result, as the page-reader's {@code ReadPageResult}.
 *
 * @param outcome how the read ended
 * @param httpStatus the status of the page's final document, null when none answered
 * @param finalUrl where the browser ended up, after any redirects
 * @param title the article's title
 * @param text the article text, present only when {@code outcome} is {@code READ}
 */
public record PageReadResult(
    PageReadOutcome outcome, Integer httpStatus, String finalUrl, String title, String text) {
}
