import { useEffect, useState } from 'react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { trackPageView } from '../services/analytics'
import { fetchNews } from '../services/newsApi'
import type { ArticleResponse } from '../types/news'

export function NewsPage() {
  const [articles, setArticles] = useState<ArticleResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    trackPageView('/news')
    document.title = 'News'
  }, [])

  useEffect(() => {
    fetchNews()
      .then((page) => setArticles(page.content))
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }, [])

  if (loading) {
    return <LoadingIndicator />
  }

  if (error) {
    return <ErrorMessage message={error} />
  }

  return (
    <div className="news-page">
      <h1 className="news-page__title">News</h1>
      {articles.length === 0 ? (
        <p className="news-page__empty">News articles are being gathered. Check back soon!</p>
      ) : (
        <div className="news-page__grid">
          {articles.map((article) => (
            <article className="news-card" key={article.id}>
              <span className="news-card__source">{article.sourceName}</span>
              <h2 className="news-card__title">{article.title}</h2>
              {article.summary && (
                <p className="news-card__summary">{article.summary}</p>
              )}
              <div className="news-card__meta">
                <span className="news-card__date">
                  {article.publishedDate
                    ? new Date(article.publishedDate).toLocaleDateString(undefined, {
                        year: 'numeric',
                        month: 'short',
                        day: 'numeric',
                      })
                    : ''}
                </span>
                <a
                  className="news-card__link"
                  href={article.originalUrl}
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  Read more
                </a>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  )
}
