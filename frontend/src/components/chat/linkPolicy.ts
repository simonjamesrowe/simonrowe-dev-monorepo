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
    if (sameOrigin(href, window.location.origin)) {
      return 'external-allowed'
    }
  }
  if (/^https:\/\//i.test(href) && allowlist.has(href)) {
    return 'external-allowed'
  }
  if (
    /^https:\/\//i.test(href) &&
    allowedPrefixes.some(
      (prefix) =>
        // BOTH conditions, and neither is redundant. The origin comparison is what stops a
        // domain-suffix forgery: with a bare `startsWith`, the prefix
        // "https://www.kilmorieschool.co.uk" also matches
        // "https://www.kilmorieschool.co.uk.attacker.example/phish", which then rendered as a
        // clickable link wearing the school's name. Answers are composed from scraped pages
        // and emailed PDFs, so a hostile URL really can arrive in the model's context.
        // The startsWith is kept so a prefix carrying a path still NARROWS to that path
        // rather than being widened to the whole origin.
        sameOrigin(href, prefix) && href.toLowerCase().startsWith(prefix.toLowerCase()),
    )
  ) {
    return 'external-allowed'
  }
  return 'strip'
}

/**
 * Whether two absolute URLs share an origin (scheme, host and port).
 *
 * <p>Parsed rather than string-compared: an origin boundary cannot be expressed reliably by
 * concatenating a separator, because a prefix may or may not already end in one.
 *
 * @returns false for anything unparseable, so a malformed href is never allowed through
 */
function sameOrigin(href: string, other: string): boolean {
  const a = originOf(href)
  return a !== null && a === originOf(other)
}

function originOf(value: string): string | null {
  try {
    return new URL(value).origin.toLowerCase()
  } catch {
    return null
  }
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
