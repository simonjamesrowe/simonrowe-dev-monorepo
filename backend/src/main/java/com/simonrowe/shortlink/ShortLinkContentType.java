package com.simonrowe.shortlink;

/**
 * What a share link points at, and where opening it lands.
 *
 * <p>This is deliberately a <em>new</em> enum rather than a reuse of either existing
 * content-type enum, because neither has the right member set:
 *
 * <ul>
 *   <li>{@code com.simonrowe.narration.NarrationContentType} is {@code BLOG} and
 *       {@code ARTICLE_SUMMARY} — no events at all, and it names the summary rather than
 *       the article.</li>
 *   <li>{@code com.simonrowe.events.ContentChangeEvent.ContentType} carries members such
 *       as jobs and skills that have no shareable page.</li>
 * </ul>
 *
 * <p>Sharing a third meaning through either would couple the values stored in
 * {@code short_links} — which are permanent, because the slug is a public identifier
 * already pasted elsewhere — to the evolution of an unrelated concern.
 *
 * <p>Note {@link #ARTICLE} is distinct from narration's {@code ARTICLE_SUMMARY} even
 * though its destination opens the summary panel: the link points at the aggregated
 * article, and {@code contentId} is an {@code aggregated_articles} id.
 */
public enum ShortLinkContentType {

  /** A first-party blog post. Lands on the post's own page. */
  BLOG {
    @Override
    public String destinationPath(final String contentId) {
      return "/blogs/" + contentId;
    }
  },

  /**
   * An aggregated news article. Lands on the news and events page with that article's
   * summary panel open — the first-party summary and narration audio, rather than
   * bouncing the visitor straight to the publisher.
   */
  ARTICLE {
    @Override
    public String destinationPath(final String contentId) {
      return "/news-events?article=" + contentId;
    }
  },

  /** An aggregated event. Lands on the news and events page focused on that event. */
  EVENT {
    @Override
    public String destinationPath(final String contentId) {
      return "/news-events?event=" + contentId;
    }
  };

  /**
   * The site-relative path a share link of this type redirects to.
   *
   * @param contentId the id of the content in its own collection
   * @return a path beginning with {@code /}
   */
  public abstract String destinationPath(String contentId);
}
