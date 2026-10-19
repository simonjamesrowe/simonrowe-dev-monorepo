import { KEPT_QUERY_PARAMS, type AnalyticsApp } from './policy'

/**
 * Removes every query parameter not in KEPT_QUERY_PARAMS. Anything that does not parse as a
 * URL is returned unchanged, and so is a URL with nothing to remove (its formatting included).
 */
export function scrubUrl(value: string): string {
  const queryAt = value.indexOf('?')
  if (queryAt === -1) return value
  let url: URL
  try {
    url = new URL(value, 'https://placeholder.invalid')
  } catch {
    return value
  }
  const kept = new URLSearchParams()
  let removed = false
  for (const [key, param] of url.searchParams) {
    if (KEPT_QUERY_PARAMS.has(key)) kept.append(key, param)
    else removed = true
  }
  if (!removed) return value
  const hashAt = value.indexOf('#', queryAt)
  const hash = hashAt === -1 ? '' : value.slice(hashAt)
  const query = kept.toString()
  return `${value.slice(0, queryAt)}${query ? `?${query}` : ''}${hash}`
}

/** Applies scrubUrl to every string inside a value, however deeply nested (user properties nest). */
export function scrubDeep(value: unknown): unknown {
  if (typeof value === 'string') return value.includes('?') ? scrubUrl(value) : value
  if (Array.isArray(value)) return value.map(scrubDeep)
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, inner]) => [key, scrubDeep(inner)]))
  }
  return value
}

interface EnrichableEvent {
  event_type: string
  event_properties?: Record<string, unknown>
  user_properties?: Record<string, unknown>
}

/**
 * An Amplitude enrichment plugin, run on every event (named and autocaptured alike) before it is
 * queued. It tags the event with the app and page that sent it, and strips private query
 * parameters from every URL it carries: page views, clicks, attribution and referrers all record
 * addresses.
 *
 * Shaped structurally rather than typed against the SDK, so this module stays out of the lazily
 * loaded Amplitude chunk.
 */
export function siteEnrichment(app: AnalyticsApp) {
  return {
    name: 'simonrowe-site-enrichment',
    type: 'enrichment' as const,
    setup: async () => undefined,
    execute: async <E extends EnrichableEvent>(event: E): Promise<E> => {
      event.event_properties = {
        ...(scrubDeep(event.event_properties ?? {}) as object),
        app,
        // Which page the event happened on, without its query string or fragment.
        page_path: window.location.pathname,
      }
      if (event.user_properties) {
        event.user_properties = scrubDeep(event.user_properties) as Record<string, unknown>
      }
      return event
    },
  }
}
