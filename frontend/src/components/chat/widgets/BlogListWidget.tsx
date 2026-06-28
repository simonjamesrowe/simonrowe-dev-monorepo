import type { BlogWidgetPayload } from '../chatTypes'

interface BlogListWidgetProps {
  payload: BlogWidgetPayload
}

export function BlogListWidget({ payload }: BlogListWidgetProps) {
  if (!payload.posts?.length) return null

  return (
    <div className="chat-widget chat-widget--blogs">
      {payload.posts.map(post => (
        <article className="chat-widget__item" key={post.id ?? post.url ?? post.title}>
          <div className="chat-widget__item-head">
            <h4 className="chat-widget__title">{post.title}</h4>
            {post.publishedDate && (
              <span className="chat-widget__meta">{formatDate(post.publishedDate)}</span>
            )}
          </div>
          {post.summary && <p className="chat-widget__summary">{post.summary}</p>}
          {!!post.tags?.length && (
            <div className="chat-widget__chips">
              {post.tags.map(tag => (
                <span className="chat-widget__chip" key={tag}>{tag}</span>
              ))}
            </div>
          )}
          {post.url && (
            <a className="chat-widget__link" href={post.url}>
              Read post
            </a>
          )}
        </article>
      ))}
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
