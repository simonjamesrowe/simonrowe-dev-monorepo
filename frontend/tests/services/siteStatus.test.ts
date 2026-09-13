import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { probeSiteStatus } from '../../src/services/siteStatus'

/**
 * The mapping from nginx's status line to a decision the page acts on.
 *
 * Worth pinning because the two interesting values are the ones that look alike: 503 means a
 * deploy is under way and the page should hand over to the maintenance page, 502 means an
 * upstream is down and it should not. Swapping them shows an outage page during every deploy,
 * or sits through a deploy showing a dead chat.
 */
describe('probing what state the site is in', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads 503 as a deploy in progress', async () => {
    fetchMock.mockResolvedValue({ status: 503 })
    await expect(probeSiteStatus()).resolves.toBe('maintenance')
  })

  it.each([502, 504])('reads %i as an upstream being down, not a deploy', async (status) => {
    fetchMock.mockResolvedValue({ status })
    await expect(probeSiteStatus()).resolves.toBe('unavailable')
  })

  it('reads anything else as the site serving normally', async () => {
    fetchMock.mockResolvedValue({ status: 200 })
    await expect(probeSiteStatus()).resolves.toBe('ok')
  })

  it('reads a failed request as unreachable rather than throwing', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    // The caller is an effect reacting to a lost socket. A rejection here would be an
    // unhandled one, in the exact circumstances where nothing is going well already.
    await expect(probeSiteStatus()).resolves.toBe('unreachable')
  })

  it('asks about the page the visitor is on, and refuses a cached answer', async () => {
    fetchMock.mockResolvedValue({ status: 200 })
    await probeSiteStatus()

    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toBe(window.location.origin + window.location.pathname)
    expect(init).toMatchObject({ method: 'HEAD', cache: 'no-store' })
  })

  it('leaves the query and hash out of the probe', async () => {
    window.history.replaceState({}, '', '/news-events?article=123#top')
    fetchMock.mockResolvedValue({ status: 200 })
    await probeSiteStatus()

    // No server block's maintenance decision depends on either, and they are the only parts of
    // the address a third party can influence. Returning the visitor to their exact address is
    // reloadPage()'s job, and reload keeps the whole href — so nothing is lost by dropping them.
    const [url] = fetchMock.mock.calls[0]
    expect(String(url)).toBe(`${window.location.origin}/news-events`)
    expect(String(url)).not.toContain('article=123')
    expect(String(url)).not.toContain('#top')
  })
})
