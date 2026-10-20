import type { BlogDetail } from '../../types/blog'
import type { ArticleResponse } from '../../types/news'
import type { SnippetSourceType } from '../../types/snippets'

/** What the passages are taken from, and how a copy of one is cited. */
export interface SnippetSource {
  sourceType: SnippetSourceType
  contentId: string
  title: string
  sourceName: string
  author: string | null
  url: string
  publishedAt: string | null
}

/** A news article's AI summary, cited as a summary of the original article. */
export function summarySource(article: ArticleResponse): SnippetSource {
  return {
    sourceType: 'ARTICLE_SUMMARY',
    contentId: article.id,
    title: article.title,
    sourceName: article.sourceName,
    author: article.author,
    url: article.originalUrl,
    publishedAt: article.publishedDate,
  }
}

/**
 * One of the site's own posts. The server stores the same details from the post itself;
 * these only cite a passage copied before it is saved.
 */
export function postSource(blog: BlogDetail): SnippetSource {
  return {
    sourceType: 'BLOG',
    contentId: blog.id,
    title: blog.title,
    sourceName: 'simonrowe.dev',
    author: 'Simon Rowe',
    url: `${window.location.origin}/blogs/${blog.id}`,
    publishedAt: blog.createdDate,
  }
}
