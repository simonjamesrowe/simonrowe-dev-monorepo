package com.simonrowe.snippet;

/**
 * What a snippet was taken from. A Term Time document would be another member, with no
 * migration of the existing rows.
 */
public enum SnippetSourceType {
  /** An AI summary of an aggregated article; {@code contentId} is the article id. */
  ARTICLE_SUMMARY,
  /** A post on the site's own blog; {@code contentId} is the blog id. */
  BLOG
}
