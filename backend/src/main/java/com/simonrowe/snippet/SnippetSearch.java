package com.simonrowe.snippet;

import java.time.LocalDate;
import java.util.List;

/**
 * The console's filters, all optional.
 *
 * @param query free text, every word of which must appear in the quote, note, tags, article
 *     title or source
 * @param tags tags the snippet must all carry
 * @param sources publishers, any of which matches
 * @param from kept on or after this day (UTC)
 * @param to kept on or before this day (UTC)
 * @param sort {@code newest} (newest snippet first) or {@code article} (newest article first)
 */
public record SnippetSearch(
    String query,
    List<String> tags,
    List<String> sources,
    LocalDate from,
    LocalDate to,
    String sort
) {

  public static final String SORT_NEWEST = "newest";
  public static final String SORT_ARTICLE = "article";

  public SnippetSearch {
    tags = tags == null ? List.of() : List.copyOf(tags);
    sources = sources == null ? List.of() : List.copyOf(sources);
    sort = SORT_ARTICLE.equals(sort) ? SORT_ARTICLE : SORT_NEWEST;
  }
}
