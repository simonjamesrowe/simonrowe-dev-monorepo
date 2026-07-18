package com.simonrowe.websearch;

/**
 * A single live web search result, surfaced to the chat model so it can cite the source inline
 * as a markdown link.
 *
 * @param title result title
 * @param url source URL (cited as a markdown link)
 * @param snippet short excerpt of the result content
 */
public record WebSearchResult(String title, String url, String snippet) {
}
