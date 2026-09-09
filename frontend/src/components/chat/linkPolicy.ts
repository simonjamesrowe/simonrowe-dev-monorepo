import { API_BASE_URL } from '../../config/api'
import type {
  BlogWidgetPayload,
  ChatBlock,
  EventWidgetPayload,
  NewsWidgetPayload,
} from './chatTypes'

// Internal SPA routes an answer is allowed to link to. The pathname (query/hash
// stripped) must match one of these EXACTLY, so a fabricated destination such as
// "/experience Macquarie Group," does not slip through as an internal route.
const INTERNAL_PATH_PATTERNS: RegExp[] = [
  /^\/$/,
  /^\/profile$/,
  /^\/experience$/,
  /^\/blogs$/,
  /^\/blogs\/[^/?#\s]+$/,
  /^\/news-events$/,
]

export type LinkClassification = 'internal' | 'external-allowed' | 'strip'

/** True when href is a relative path matching a known internal route (query/hash allowed). */
export function isInternalRoute(href: string): boolean {
  if (!href.startsWith('/')) {
    return false
  }
  const pathname = href.split(/[?#]/)[0]
  return INTERNAL_PATH_PATTERNS.some((pattern) => pattern.test(pathname))
}

/**
 * Decide how a markdown link should render:
 * - internal route → in-site navigation (React Router Link)
 * - allowlisted https URL → safe new-tab anchor
 * - anything else (non-allowlisted https, http:, javascript:, data:, fabricated) → plain text
 */
export function classifyLink(
  href: string | undefined,
  allowlist: ReadonlySet<string>,
  /**
   * Whole origins the message may link anywhere within.
   *
   * The exact-URL allowlist is built from streamed widget payloads, which works when every
   * linkable URL was handed to the browser. Term Time cites deep links the model got from a
   * tool result — a calendar event, a PDF — that the browser never sees, so exact matching
   * strips them all. A prefix is safe here because it is a fixed first-party origin supplied
   * by our own code, never by the model.
   */
  allowedPrefixes: readonly string[] = [],
): LinkClassification {
  if (!href) {
    return 'strip'
  }
  if (isInternalRoute(href)) {
    return 'internal'
  }
  // Same-origin absolute links are as trustworthy as the page serving them, and are not
  // subject to the https rule below: Term Time's PDF citations point at its own
  // /api/school/attachments/<id>, and local development serves that origin over http.
  // They must be absolute rather than relative — a model handed a bare path invents an
  // origin to write a markdown link with, and picks whichever domain the answer cites.
  if (typeof window !== 'undefined' && window.location?.origin) {
    const origin = window.location.origin
    if (href.toLowerCase().startsWith(`${origin.toLowerCase()}/`)) {
      return 'external-allowed'
    }
  }
  if (/^https:\/\//i.test(href) && allowlist.has(href)) {
    return 'external-allowed'
  }
  if (
    /^https:\/\//i.test(href) &&
    allowedPrefixes.some((prefix) => href.toLowerCase().startsWith(prefix.toLowerCase()))
  ) {
    return 'external-allowed'
  }
  return 'strip'
}

function isUploadsOrigin(src: string): boolean {
  if (src.startsWith('/uploads/')) {
    return true
  }
  return API_BASE_URL.length > 0 && src.startsWith(`${API_BASE_URL}/uploads/`)
}

/** An image renders only from our own uploads origin or an allowlisted URL. */
export function isAllowedImage(src: string | undefined, allowlist: ReadonlySet<string>): boolean {
  if (!src) {
    return false
  }
  return isUploadsOrigin(src) || allowlist.has(src)
}

/**
 * Build the per-message link/image allowlist from the widget payloads already streamed
 * for that message, plus any extra known-safe URLs (e.g. the assistant avatar image).
 */
export function buildAllowlist(
  blocks: ChatBlock[] | undefined,
  extraUrls: Array<string | null | undefined> = [],
): Set<string> {
  const urls = new Set<string>()
  const add = (url?: string | null): void => {
    if (url) {
      urls.add(url)
    }
  }

  for (const block of blocks ?? []) {
    if (block.kind !== 'widget') {
      continue
    }
    if (block.widgetKind === 'blogs') {
      const payload = block.payload as BlogWidgetPayload
      for (const post of payload.posts ?? []) {
        add(post.url)
        add(post.imageUrl)
      }
    } else if (block.widgetKind === 'news') {
      const payload = block.payload as NewsWidgetPayload
      for (const article of payload.articles ?? []) {
        add(article.originalUrl)
        add(article.imageUrl)
      }
    } else if (block.widgetKind === 'events') {
      const payload = block.payload as EventWidgetPayload
      for (const chatEvent of payload.events ?? []) {
        add(chatEvent.originalUrl)
        add(chatEvent.imageUrl)
      }
    }
  }

  for (const url of extraUrls) {
    add(url)
  }
  return urls
}
