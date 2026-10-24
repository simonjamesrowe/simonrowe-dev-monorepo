import type { BlogDetail } from '../../types/blog'
import type { NarrationTrack } from '../../types/narrationAudio'
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

/**
 * Whatever the player is on: a summary, cited as one of the original article, or a post, cited
 * exactly as `postSource` cites it. The playlist does not carry an article's author, so a copy
 * made before saving names none; the saved snippet's citation comes from the server and does.
 */
export function trackSource(track: NarrationTrack, publishedAt: string | null): SnippetSource {
  if (track.contentType === 'BLOG') {
    return {
      sourceType: 'BLOG',
      contentId: track.contentId,
      title: track.title,
      sourceName: 'simonrowe.dev',
      author: 'Simon Rowe',
      url: `${window.location.origin}/blogs/${track.contentId}`,
      publishedAt,
    }
  }
  return {
    sourceType: 'ARTICLE_SUMMARY',
    contentId: track.contentId,
    title: track.title,
    sourceName: track.sourceName ?? '',
    author: null,
    url: track.href,
    publishedAt,
  }
}
