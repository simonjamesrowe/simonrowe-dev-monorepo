import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { AmplitudeHandle } from './amplitude'
import {
  clearIdentity,
  isAmplitudeDatabase,
  identifyUser,
  initAnalytics,
  isAnalyticsConfigured,
  openCookieSettings,
  resetAnalyticsForTests,
  track,
  type AnalyticsDependencies,
} from './analytics'

function fakeHandle(): AmplitudeHandle {
  return { track: vi.fn(), setUser: vi.fn(), resetUser: vi.fn(), optOut: vi.fn() }
}

/** A consent banner the test drives by hand, and a loader that resolves to a fake SDK. */
function harness(apiKey: string | null = 'key-123') {
  const handle = fakeHandle()
  let handlers: { onGranted: () => void; onWithdrawn: () => void; onDeclined: () => void } | undefined
  const deps: AnalyticsDependencies = {
    apiKey: apiKey ?? undefined,
    origin: 'https://simonrowe.dev',
    startConsent: vi.fn((h) => {
      handlers = h
    }),
    showPreferences: vi.fn(),
    load: vi.fn(async () => handle),
    purge: vi.fn(),
  }
  return {
    deps,
    handle,
    grant: () => handlers?.onGranted(),
    withdraw: () => handlers?.onWithdrawn(),
    decline: () => handlers?.onDeclined(),
  }
}

/** Lets the loader's promise chain settle. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => resetAnalyticsForTests())
afterEach(() => resetAnalyticsForTests())

describe('analytics before consent', () => {
  it('does not ask for consent, load anything or send anything in a build with no key', async () => {
    const h = harness(null)
    initAnalytics('main', h.deps)

    track('Chat Question Sent')
    await settle()

    expect(h.deps.startConsent).not.toHaveBeenCalled()
    expect(h.deps.load).not.toHaveBeenCalled()
    expect(isAnalyticsConfigured()).toBe(false)
  })

  it('asks for consent but loads nothing until the visitor accepts', async () => {
    const h = harness()
    initAnalytics('main', h.deps)

    track('Chat Question Sent')
    await settle()

    expect(h.deps.startConsent).toHaveBeenCalledTimes(1)
    expect(h.deps.load).not.toHaveBeenCalled()
  })

  it('never sends an event from before consent, even once consent arrives', async () => {
    const h = harness()
    initAnalytics('main', h.deps)
    track('Contact Form Submitted')

    h.grant()
    await settle()

    expect(h.handle.track).not.toHaveBeenCalled()
  })
})

describe('analytics after consent', () => {
  it('loads the SDK once for this app and origin', async () => {
    const h = harness()
    initAnalytics('term-time', h.deps)

    h.grant()
    h.grant()
    await settle()

    expect(h.deps.load).toHaveBeenCalledTimes(1)
    expect(h.deps.load).toHaveBeenCalledWith('key-123', 'term-time', 'https://simonrowe.dev')
  })

  it('ignores a second init, as StrictMode or a remount would cause', () => {
    const h = harness()
    const other = harness()
    initAnalytics('main', h.deps)
    initAnalytics('meals', other.deps)

    expect(other.deps.startConsent).not.toHaveBeenCalled()
  })

  it('queues events sent while the SDK downloads and delivers them in order', async () => {
    const h = harness()
    initAnalytics('main', h.deps)
    h.grant()

    track('Chat Question Sent')
    track('Chat Answer Received', { toolCount: 2, latencyMs: 1400, failed: false })
    await settle()

    expect(vi.mocked(h.handle.track).mock.calls).toEqual([
      ['Chat Question Sent', {}],
      ['Chat Answer Received', { toolCount: 2, latencyMs: 1400, failed: false }],
    ])
  })

  it('drops properties that do not fit the schema before they reach the SDK', async () => {
    const h = harness()
    initAnalytics('main', h.deps)
    h.grant()
    await settle()

    // A cast is the only way past the compiler; the runtime check is what holds.
    const smuggled = { blogId: 'how do I contact you, my email is a@b.com', extra: 'free text' }
    track('Blog Viewed', smuggled as never)

    expect(h.handle.track).toHaveBeenCalledWith('Blog Viewed', {})
  })

  it('stays off for this page view if the SDK fails to load', async () => {
    const h = harness()
    vi.mocked(h.deps.load).mockRejectedValueOnce(new Error('blocked'))
    initAnalytics('main', h.deps)
    h.grant()
    await settle()

    expect(() => track('Chat Question Sent')).not.toThrow()
  })
})

describe('withdrawing consent', () => {
  it('opts the SDK out, then clears what it stored', async () => {
    const h = harness()
    initAnalytics('main', h.deps)
    h.grant()
    await settle()

    h.withdraw()
    track('Chat Question Sent')

    expect(h.handle.optOut).toHaveBeenCalledTimes(1)
    expect(h.deps.purge).toHaveBeenCalledWith(true)
    expect(h.handle.track).not.toHaveBeenCalled()
  })

  it('purges leftovers without reloading on a later page load that has analytics declined', async () => {
    const h = harness()
    initAnalytics('main', h.deps)

    h.decline()
    await settle()

    expect(h.deps.purge).toHaveBeenCalledWith(false)
    expect(h.deps.load).not.toHaveBeenCalled()
  })
})

describe('identity', () => {
  it('applies an identity set before the SDK loaded once it has', async () => {
    const h = harness()
    initAnalytics('meals', h.deps)
    identifyUser('auth0|owner', true)

    h.grant()
    await settle()

    expect(h.handle.setUser).toHaveBeenCalledWith('auth0|owner', true)
  })

  it('does not re-send an unchanged identity on every render', async () => {
    const h = harness()
    initAnalytics('main', h.deps)
    h.grant()
    await settle()

    identifyUser('auth0|visitor', false)
    identifyUser('auth0|visitor', false)

    expect(h.handle.setUser).toHaveBeenCalledTimes(1)
  })

  it('resets only after someone was identified, so anonymous visits keep one device id', async () => {
    const h = harness()
    initAnalytics('main', h.deps)
    h.grant()
    await settle()

    clearIdentity()
    expect(h.handle.resetUser).not.toHaveBeenCalled()

    identifyUser('auth0|visitor', false)
    clearIdentity()
    expect(h.handle.resetUser).toHaveBeenCalledTimes(1)
  })
})

describe('cookie settings', () => {
  it('re-opens the consent choices', () => {
    const h = harness()
    initAnalytics('main', h.deps)

    openCookieSettings()

    expect(h.deps.showPreferences).toHaveBeenCalledTimes(1)
  })
})

describe('isAmplitudeDatabase', () => {
  it.each(['AMP_diagnostics_0123456789', '0123456789_amp_session_replay_events', '0123456789_amp_session_replay_events_tab1'])(
    'recognises %s as Amplitude\'s',
    (name) => expect(isAmplitudeDatabase(name)).toBe(true),
  )

  it.each(['coparent-cache', 'auth0spajs', 'workbox-precache'])('leaves %s alone', (name) =>
    expect(isAmplitudeDatabase(name)).toBe(false),
  )
})
