package com.simonrowe.snippet;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A passage the site owner kept from an AI summary or a blog post, with an optional note and
 * tags (spec 054).
 *
 * <p><b>The quote is a copy, not only a pointer.</b> {@code exact} holds the text itself, so a
 * snippet still reads correctly after its summary is regenerated, the article is pruned, the
 * summary format version changes or the post is edited, and it is what goes into a slide.
 * {@code prefix} and {@code suffix} are only there to find the passage again in the rendered
 * text, which is done by matching text, never by character offsets.
 *
 * <p>The source's details are copied in when the snippet is made, so the citation survives
 * the source and the console can search one collection with no join. The {@code article*}
 * fields keep their names for a blog post too: they hold the post's title, the site as the
 * source, Simon as the author, the post's address and its date.
 *
 * <p>Indexes are created by {@code V061CreateSnippets}: {@code auto-index-creation} is off.
 *
 * @param id generated id
 * @param sourceType what kind of content the passage came from
 * @param contentId the aggregated article id, or the blog id for {@link SnippetSourceType#BLOG}
 * @param summaryId the summary the passage was taken from; null for a blog post
 * @param exact the quote, as rendered text
 * @param prefix up to {@link SnippetText#CONTEXT_LENGTH} characters before it
 * @param suffix up to {@link SnippetText#CONTEXT_LENGTH} characters after it
 * @param note optional plain-text note, never null
 * @param tags normalised tags, never null
 * @param articleTitle the article's title when the snippet was made
 * @param sourceName the article's publisher
 * @param author the article's author, may be null
 * @param originalUrl the original article's address, for the citation
 * @param articlePublishedAt the article's publication date, may be null
 * @param createdBy the Auth0 subject who kept it
 * @param createdAt when it was kept
 * @param updatedAt when its note or tags last changed
 */
@Document(collection = Snippet.COLLECTION)
public record Snippet(
    @Id String id,
    SnippetSourceType sourceType,
    String contentId,
    String summaryId,
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
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {

  public static final String COLLECTION = "snippets";

  public Snippet {
    note = note == null ? "" : note;
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  /** The same snippet with a new note and tags. The quote itself never changes. */
  public Snippet withNoteAndTags(
      final String newNote, final List<String> newTags, final Instant now) {
    return new Snippet(id, sourceType, contentId, summaryId, exact, prefix, suffix, newNote,
        newTags, articleTitle, sourceName, author, originalUrl, articlePublishedAt, createdBy,
        createdAt, now);
  }
}
