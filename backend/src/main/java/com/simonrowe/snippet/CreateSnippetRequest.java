package com.simonrowe.snippet;

import java.util.List;

/**
 * Keeps a passage of a summary. The article details and the summary id are never taken from
 * here: the server reads them itself, so a client cannot attach a quote to the wrong article
 * or save one the summary never contained.
 *
 * @param contentId the aggregated article id
 * @param exact the quote, as the reader saw it
 * @param prefix the text just before it, used only to find it again
 * @param suffix the text just after it, used only to find it again
 * @param note optional note
 * @param tags optional tags, normalised on the way in
 */
public record CreateSnippetRequest(
    String contentId,
    String exact,
    String prefix,
    String suffix,
    String note,
    List<String> tags
) {
}
