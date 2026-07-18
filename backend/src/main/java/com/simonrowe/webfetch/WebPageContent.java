package com.simonrowe.webfetch;

/**
 * Readable content extracted from a fetched web page (e.g. a job posting), passed to the chat
 * model so it can assess fit or enrich a grounded answer.
 *
 * @param title page title
 * @param url the (final) URL that was fetched
 * @param text extracted, truncated plain text of the page body
 */
public record WebPageContent(String title, String url, String text) {
}
