package com.simonrowe.snippet;

import java.time.Instant;
import java.util.List;

/**
 * A snippet as the browser sees it. Leaves out who made it, which only the store needs.
 *
 * @param id the snippet id
 * @param sourceType what it was taken from
 * @param contentId the aggregated article id, or the blog id
 * @param exact the quote
 * @param prefix the text before it
 * @param suffix the text after it
 * @param note the note, empty when there is none
 * @param tags the tags
 * @param articleTitle the article's title
 * @param sourceName the publisher
 * @param author the article's author, may be null
 * @param originalUrl the original article
 * @param articlePublishedAt when the article was published, may be null
 * @param createdAt when the snippet was kept
 * @param updatedAt when its note or tags last changed
 * @param inCurrentSummary whether the article's current summary, or the post as it reads now,
 *     still contains the quote
 * @param articleAvailable whether the article can still be opened on the news page, or the
 *     post is still published
 */
public record SnippetResponse(
    String id,
    SnippetSourceType sourceType,
    String contentId,
    String exact,
    String prefix,
    String suffix,
    String note,
    List<String> tags,
    String articleTitle,
    String sourceName,
    String author,
    String originalUrl,
    Instant articlePublishedAt,
    Instant createdAt,
    Instant updatedAt,
    boolean inCurrentSummary,
    boolean articleAvailable
) {

  static SnippetResponse from(
      final Snippet snippet, final boolean inCurrentSummary, final boolean articleAvailable) {
    return new SnippetResponse(snippet.id(), snippet.sourceType(), snippet.contentId(),
        snippet.exact(), snippet.prefix(), snippet.suffix(), snippet.note(), snippet.tags(),
        snippet.articleTitle(), snippet.sourceName(), snippet.author(), snippet.originalUrl(),
        snippet.articlePublishedAt(), snippet.createdAt(), snippet.updatedAt(),
        inCurrentSummary, articleAvailable);
  }
}
