package com.simonrowe.snippet;

import java.util.List;

/**
 * Keeps a passage of a summary or a blog post. The source's details and the summary id are
 * never taken from here: the server reads them itself, so a client cannot attach a quote to the
 * wrong source or save one the source never contained.
 *
 * @param sourceType what the passage is from; {@code ARTICLE_SUMMARY} when omitted, which is
 *     what every client sent before blog posts could be quoted
 * @param contentId the aggregated article id, or the blog id
 * @param exact the quote, as the reader saw it
 * @param prefix the text just before it, used only to find it again
 * @param suffix the text just after it, used only to find it again
 * @param note optional note
 * @param tags optional tags, normalised on the way in
 */
public record CreateSnippetRequest(
    SnippetSourceType sourceType,
    String contentId,
    String exact,
    String prefix,
    String suffix,
    String note,
    List<String> tags
) {

  public CreateSnippetRequest {
    sourceType = sourceType == null ? SnippetSourceType.ARTICLE_SUMMARY : sourceType;
  }
}
