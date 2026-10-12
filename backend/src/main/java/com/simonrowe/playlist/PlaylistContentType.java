package com.simonrowe.playlist;

import com.simonrowe.narration.NarrationContentType;
import java.util.Arrays;
import java.util.Optional;

/**
 * What a playlist item is audio of: the summary of an aggregated article, or one of the
 * site's own blog posts read in full.
 */
public enum PlaylistContentType {
  ARTICLE_SUMMARY("articles", NarrationContentType.ARTICLE_SUMMARY),
  BLOG("blogs", NarrationContentType.BLOG);

  private final String pathSegment;
  private final NarrationContentType narrationType;

  PlaylistContentType(final String pathSegment, final NarrationContentType narrationType) {
    this.pathSegment = pathSegment;
    this.narrationType = narrationType;
  }

  public NarrationContentType narrationType() {
    return narrationType;
  }

  /**
   * Resolves the URL segment used by {@code /api/playlist/items/{type}/{id}}.
   *
   * @param segment {@code articles} or {@code blogs}
   * @return the type, or empty for anything else
   */
  public static Optional<PlaylistContentType> fromPathSegment(final String segment) {
    return Arrays.stream(values())
        .filter(type -> type.pathSegment.equals(segment))
        .findFirst();
  }
}
