import type { NewsWidgetPayload } from '../chatTypes'
import { resolveChatWidgetImageUrl } from './chatWidgetImages'

interface NewsWidgetProps {
  payload: NewsWidgetPayload
}

export function NewsWidget({ payload }: NewsWidgetProps) {
  if (!payload.articles?.length) return null

  return (
    <div className="chat-widget chat-widget--news">
      {payload.articles.map(article => {
        const imageUrl = resolveChatWidgetImageUrl(article.imageUrl)
        return (
          <article className="chat-widget__item" key={article.id ?? article.originalUrl ?? article.title}>
            {imageUrl && (
              <div className="chat-widget__media">
                <img src={imageUrl} alt="" className="chat-widget__image" />
              </div>
            )}
            <div className="chat-widget__item-head">
              <h4 className="chat-widget__title">{article.title}</h4>
              {article.publishedDate && (
                <span className="chat-widget__meta">{formatDate(article.publishedDate)}</span>
              )}
            </div>
            {article.sourceName && (
              <div className="chat-widget__source">{article.sourceName}</div>
            )}
            {article.summary && <p className="chat-widget__summary">{article.summary}</p>}
            {article.originalUrl && (
              <a
                aria-label={`Read source: ${article.title}`}
                className="chat-widget__link"
                href={article.originalUrl}
                rel="noopener noreferrer"
                target="_blank"
              >
                Read source
              </a>
            )}
          </article>
        )
      })}
    </div>
  )
}

function formatDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
