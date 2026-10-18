package com.simonrowe.snippet;

import java.util.List;

/**
 * Changes a snippet's note and tags. There is no way to change the quote: an edited quote is
 * no longer a quote.
 *
 * @param note the new note, empty to clear it
 * @param tags the new tags, replacing the old ones
 */
public record UpdateSnippetRequest(String note, List<String> tags) {
}
