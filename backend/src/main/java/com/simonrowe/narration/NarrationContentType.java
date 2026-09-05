package com.simonrowe.narration;

/**
 * What a {@code Narration} is audio of.
 *
 * <p>Deliberately leaves room for a future {@code ARTICLE_FULL} — narrating an aggregated
 * article's own body rather than our generated summary of it. That was scoped out because
 * scraped article text varies from full page content to a bare RSS snippet, and because
 * hosting a complete audio rendition of a third party's article is a reproduction rather
 * than a transformation. {@link NarrationSource} is the interface it would plug into;
 * adding it later is additive.
 */
public enum NarrationContentType {
  BLOG,
  ARTICLE_SUMMARY,
  TOUR_STEP
}
