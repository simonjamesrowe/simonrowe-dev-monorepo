package com.simonrowe.snippet;

/**
 * A tag and how many snippets carry it, for the console's filter chips and the tag
 * suggestions in the summary drawer.
 *
 * @param tag the tag
 * @param count snippets carrying it
 */
public record TagCount(String tag, long count) {
}
