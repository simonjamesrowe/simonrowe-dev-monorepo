import { describe, expect, it } from 'vitest'

import { scrubDeep, scrubUrl, siteEnrichment } from './enrichment'

describe('scrubUrl', () => {
  it('removes a search query typed on the news page', () => {
    expect(scrubUrl('https://www.simonrowe.dev/news-events?q=kafka%20outage')).toBe(
      'https://www.simonrowe.dev/news-events',
    )
  })

  it("removes Auth0's code and state from a callback address", () => {
    expect(scrubUrl('https://coparents.simonrowe.dev/auth/callback?code=abc&state=xyz')).toBe(
      'https://coparents.simonrowe.dev/auth/callback',
    )
  })

  it('keeps campaign tags and the deep-link parameters the site uses', () => {
    expect(
      scrubUrl('https://www.simonrowe.dev/news-events?article=66f1&utm_source=linkedin&q=secret'),
    ).toBe('https://www.simonrowe.dev/news-events?article=66f1&utm_source=linkedin')
  })

  it('keeps the fragment', () => {
    expect(scrubUrl('https://www.simonrowe.dev/about?q=x#roles')).toBe('https://www.simonrowe.dev/about#roles')
  })

  it('handles a path with no origin', () => {
    expect(scrubUrl('/school/?q=when%20is%20inset')).toBe('/school/')
  })

  it('leaves an address with nothing private exactly as it was', () => {
    expect(scrubUrl('https://www.simonrowe.dev/about?job=global')).toBe('https://www.simonrowe.dev/about?job=global')
    expect(scrubUrl('https://www.simonrowe.dev/blogs')).toBe('https://www.simonrowe.dev/blogs')
  })
})

describe('scrubDeep', () => {
  it('scrubs strings inside nested objects and arrays, and leaves other values alone', () => {
    expect(
      scrubDeep({
        $set: { referrer: 'https://www.google.com/search?q=simon+rowe' },
        list: ['/x?q=1'],
        count: 3,
        flag: true,
        empty: null,
      }),
    ).toEqual({
      $set: { referrer: 'https://www.google.com/search' },
      list: ['/x'],
      count: 3,
      flag: true,
      empty: null,
    })
  })
})

describe('siteEnrichment', () => {
  it('tags every event with its app and scrubs its URLs', async () => {
    const plugin = siteEnrichment('term-time')
    const event = await plugin.execute({
      event_type: '[Amplitude] Page Viewed',
      event_properties: { '[Amplitude] Page URL': 'https://term-time.simonrowe.dev/?q=clubs' },
      user_properties: { $setOnce: { initial_referrer: 'https://x.example/?token=1' } },
    })

    expect(event.event_properties).toEqual({
      '[Amplitude] Page URL': 'https://term-time.simonrowe.dev/',
      app: 'term-time',
      page_path: window.location.pathname,
    })
    expect(event.user_properties).toEqual({ $setOnce: { initial_referrer: 'https://x.example/' } })
  })

  it('adds the app to an event that had no properties', async () => {
    const bare: { event_type: string; event_properties?: Record<string, unknown> } = {
      event_type: 'Recipe Cooked',
    }
    const event = await siteEnrichment('meals').execute(bare)
    expect(event.event_properties).toEqual({ app: 'meals', page_path: window.location.pathname })
  })
})
