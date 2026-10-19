import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'

/**
 * Drives the REAL Amplitude SDK, configured exactly as production configures it, and reads what
 * it actually sends.
 *
 * The unit tests prove siteEnrichment scrubs what it is given. They cannot prove nothing runs
 * after it: the SDK registers its own plugins during init, after ours, and one of them
 * (page URL enrichment) used to stamp the full address, `?q=` search text included, onto every
 * event. That was only caught by reading a request body in a browser. This test is that check,
 * made permanent, so an SDK upgrade that adds another late plugin fails here rather than leaking.
 *
 * Session replay is mocked out: it needs IndexedDB and workers jsdom does not have, and it sends
 * nothing to the events endpoint this test reads.
 */
vi.mock('@amplitude/plugin-session-replay-browser', () => ({
  sessionReplayPlugin: () => ({
    name: 'session-replay-stub',
    type: 'enrichment',
    setup: async () => undefined,
    execute: async (event: unknown) => event,
  }),
}))

const SECRET = 'my-private-search'
const bodies: string[] = []
const urls: string[] = []

beforeAll(async () => {
  window.history.replaceState({}, '', `/news-events?q=${SECRET}&utm_source=linkedin`)
  // A reload of a searched page, or a link from another site that sends its full address.
  Object.defineProperty(document, 'referrer', {
    configurable: true,
    value: `https://elsewhere.example/results?q=${SECRET}`,
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      urls.push(String(url))
      bodies.push(typeof init?.body === 'string' ? init.body : '')
      return new Response(JSON.stringify({ code: 200 }), { status: 200 })
    }),
  )

  const { loadAmplitude } = await import('./amplitude')
  const amplitude = await import('@amplitude/analytics-browser')
  const handle = loadAmplitude('0123456789abcdef0123456789abcdef', 'main', window.location.origin)

  // Let init finish registering its plugins and record the initial page view.
  await new Promise((resolve) => setTimeout(resolve, 200))
  handle.track('Search Performed', { surface: 'news', resultCount: 3 })
  const button = document.createElement('button')
  button.textContent = 'Load more'
  document.body.appendChild(button)
  button.click()
  await new Promise((resolve) => setTimeout(resolve, 200))
  await amplitude.flush().promise
})

afterAll(() => {
  vi.unstubAllGlobals()
  Object.defineProperty(document, 'referrer', { configurable: true, value: '' })
  window.history.replaceState({}, '', '/')
})

describe('what the real SDK sends', () => {
  it('sends events only to the first-party proxy', () => {
    expect(urls.length).toBeGreaterThan(0)
    for (const url of urls) expect(url).toBe(`${window.location.origin}/amp/e`)
  })

  it('never sends the search text from the address bar or the referrer, on any event', () => {
    const sent = bodies.join('\n')
    expect(sent).toContain('Search Performed')
    expect(sent).not.toContain(SECRET)
  })

  it('keeps the campaign tag and tags every event with its app and page', () => {
    const events = bodies.flatMap((body) => (JSON.parse(body) as { events: Array<{ event_properties?: Record<string, unknown> }> }).events)
    expect(events.length).toBeGreaterThan(1)
    for (const event of events) {
      expect(event.event_properties?.app).toBe('main')
      expect(event.event_properties?.page_path).toBe('/news-events')
    }
    expect(bodies.join('\n')).toContain('utm_source=linkedin')
  })
})
