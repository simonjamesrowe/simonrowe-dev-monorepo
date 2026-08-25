import { useEffect } from 'react'
import { ExternalLink, Loader2, Sparkles, X } from 'lucide-react'
import ReactMarkdown, { type Components } from 'react-markdown'

import { FavouriteButton } from '../common/FavouriteButton'
import { classifyLink, isAllowedImage } from '../chat/linkPolicy'
import type { ArticleSummaryResponse } from '../../types/articleSummary'
import type { ArticleResponse } from '../../types/news'

interface NewsSummaryDrawerProps {
  article: ArticleResponse
  summary: ArticleSummaryResponse | null
  loading: boolean
  delayed: boolean
  error: string | null
  isFavourite: boolean
  onToggleFavourite: () => void
  onRetry: () => void
  onClose: () => void
  /** Slot for the audio panel, so the drawer stays ignorant of narration plumbing. */
  audioPanel?: React.ReactNode
}

/**
 * Markdown renderers enforcing the same link/image policy chat answers get.
 *
 * The summary is model output published on Simon's site, so it cannot be trusted to only
 * emit real destinations. The prompt forbids links and images outright, which makes the
 * practical effect of this policy: anything the model invents degrades to plain text
 * (links) or is dropped (images). The allowlist is deliberately empty — the summary has no
 * widget payload to derive safe URLs from, so nothing external survives.
 */
const EMPTY_ALLOWLIST: ReadonlySet<string> = new Set<string>()

const MARKDOWN_COMPONENTS: Components = {
  a({ href, children }) {
    const classification = classifyLink(href, EMPTY_ALLOWLIST)
    if (classification === 'internal' && href) {
      return (
        <a
          className="news-summary__link"
          href={href}
          rel="noopener noreferrer"
          target="_blank"
        >
          {children}
        </a>
      )
    }
    return <>{children}</>
  },
  img({ src, alt }) {
    const source = typeof src === 'string' ? src : undefined
    if (!isAllowedImage(source, EMPTY_ALLOWLIST)) {
      return null
    }
    return <img alt={alt ?? ''} className="news-summary__image" loading="lazy" src={source} />
  },
}

function formatDate(value: string | null | undefined): string | null {
  if (!value) return null
  return new Date(value).toLocaleDateString('en-GB', {
    day: 'numeric', month: 'short', year: 'numeric',
  })
}

export function NewsSummaryDrawer({
  article,
  summary,
  loading,
  delayed,
  error,
  isFavourite,
  onToggleFavourite,
  onRetry,
  onClose,
  audioPanel,
}: NewsSummaryDrawerProps) {
  useEffect(() => {
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = '' }
  }, [])

  useEffect(() => {
    const handleEsc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleEsc)
    return () => document.removeEventListener('keydown', handleEsc)
  }, [onClose])

  const publishedDate = formatDate(article.publishedDate)

  let body: React.ReactNode

  if (error) {
    body = (
      <div className="news-summary__feedback">
        <p className="news-summary__message news-summary__message--error" role="alert">
          {error}
        </p>
        <button className="button button--secondary" onClick={onRetry} type="button">
          Try again
        </button>
      </div>
    )
  } else if (loading || summary?.state === 'GENERATING') {
    body = delayed ? (
      <div className="news-summary__feedback">
        <p aria-live="polite" className="news-summary__message" role="status">
          This is taking longer than usual. You can close this and check back shortly.
        </p>
        <button className="button button--secondary" onClick={onRetry} type="button">
          Check again
        </button>
      </div>
    ) : (
      <p aria-live="polite" className="news-summary__status" role="status">
        <Loader2 aria-hidden="true" className="news-summary__spinner" size={18} />
        Writing the summary. This usually takes under a minute.
      </p>
    )
  } else if (summary?.state === 'READY') {
    body = (
      <div className="news-summary__prose">
        <ReactMarkdown components={MARKDOWN_COMPONENTS}>{summary.body}</ReactMarkdown>
      </div>
    )
  } else if (summary?.state === 'FAILED') {
    body = (
      <div className="news-summary__feedback">
        <p className="news-summary__message news-summary__message--error" role="alert">
          {summary.message}
        </p>
        {/* No retry offered for a non-retryable failure: an article with too little text
            available will fail identically every time, and pretending otherwise wastes
            the reader's time and Simon's money. */}
        {summary.retryable && (
          <button className="button button--secondary" onClick={onRetry} type="button">
            Try again
          </button>
        )}
      </div>
    )
  } else {
    body = (
      <p className="news-summary__status" role="status">
        No summary has been written for this article yet.
      </p>
    )
  }

  return (
    <div className="drawer-overlay" onClick={onClose}>
      <div
        aria-label={`Summary of ${article.title}`}
        className="drawer"
        onClick={e => e.stopPropagation()}
        role="dialog"
      >
        <div className="drawer__header">
          <span className="drawer__title">Summary</span>
          <button
            aria-label="Close"
            className="drawer__close"
            onClick={onClose}
            type="button"
          >
            <X size={18} />
          </button>
        </div>
        <div className="drawer__body">
          <div className="news-summary__meta">
            <span className="news-summary__source">{article.sourceName}</span>
            {publishedDate && (
              <span className="news-summary__date">{publishedDate}</span>
            )}
          </div>

          <h2 className="news-summary__title">
            <a
              className="news-summary__title-link"
              href={article.originalUrl}
              rel="noopener noreferrer"
              target="_blank"
            >
              {article.title}
            </a>
          </h2>

          {/* Not decoration. This is machine-written prose about someone else's article,
              published on Simon's site under his name — a reader has to be able to tell at
              a glance that neither Simon nor the original author wrote it. */}
          <p className="news-summary__disclosure">
            <Sparkles aria-hidden="true" size={14} />
            <span>AI-generated summary</span>
          </p>

          {body}

          {audioPanel}

          <div className="news-summary__footer">
            <a
              className="news-summary__original"
              href={article.originalUrl}
              rel="noopener noreferrer"
              target="_blank"
            >
              Read the original <ExternalLink aria-hidden="true" size={14} />
            </a>
            <FavouriteButton
              active={isFavourite}
              label={article.title}
              onClick={onToggleFavourite}
            />
          </div>
        </div>
      </div>
    </div>
  )
}
