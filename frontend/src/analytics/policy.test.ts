import { describe, expect, it } from 'vitest'

import { amplitudeOptions, replayOptions } from './amplitude'
import { consentCookieDomain } from './consent'
import { REPLAY_POLICIES, type AnalyticsApp } from './policy'

const APPS: AnalyticsApp[] = ['main', 'term-time', 'coparent', 'meals']

describe('replay policy', () => {
  it.each(APPS)('%s masks every form field and honours data-amp-mask and data-amp-block', (app) => {
    const policy = REPLAY_POLICIES[app]
    expect(['medium', 'conservative']).toContain(policy.defaultMaskLevel)
    expect(policy.maskSelector).toContain('[data-amp-mask]')
    expect(policy.blockSelector).toContain('[data-amp-block]')
  })

  it('masks all text and blocks every image in CoParent', () => {
    const policy = REPLAY_POLICIES.coparent
    expect(policy.defaultMaskLevel).toBe('conservative')
    expect(policy.blockSelector).toEqual(expect.arrayContaining(['img', 'iframe', 'canvas', 'video']))
  })

  it('keeps every CoParent screen inside its masked root, because nothing else hides its text', () => {
    // Amplitude's project setting overrides the mask level, so CoParent's text is hidden only by
    // data-amp-mask on its root element. A portal renders outside that root, and its text would
    // be visible in a replay.
    const sources = import.meta.glob(['../coparent/**/*.{ts,tsx}', '!../coparent/**/*.test.{ts,tsx}'], {
      query: '?raw',
      import: 'default',
      eager: true,
    }) as Record<string, string>
    const files = Object.keys(sources)
    expect(files.length).toBeGreaterThan(50)
    const portalling = files.filter((file) => /createPortal|from 'react-modal'|@radix-ui|@headlessui/.test(sources[file]))
    expect(portalling).toEqual([])
    expect(sources['../coparent/main.tsx']).toMatch(/className="coparent-app" data-amp-mask/)
  })

  it('passes each app its own policy and proxies replay through this origin', () => {
    const options = replayOptions('coparent', 'https://coparents.simonrowe.dev')
    expect(options.privacyConfig.defaultMaskLevel).toBe('conservative')
    expect(options.trackServerUrl).toBe('https://coparents.simonrowe.dev/amp/sr/sessions/v2/track')
    expect(options.configServerUrl).toBe('https://coparents.simonrowe.dev/amp/cfg/config')
  })
})

describe('Amplitude options', () => {
  const options = amplitudeOptions('https://www.simonrowe.dev')

  it('uses the EU data centre through the first-party proxy', () => {
    expect(options.serverZone).toBe('EU')
    expect(options.serverUrl).toBe('https://www.simonrowe.dev/amp/e')
    expect(options.remoteConfig.serverUrl).toBe('https://www.simonrowe.dev/amp/cfg/config')
  })

  it('turns diagnostics off, the one host the proxy does not cover', () => {
    expect(options.enableDiagnostics).toBe(false)
  })
})

describe('consent cookie domain', () => {
  it.each(['simonrowe.dev', 'www.simonrowe.dev', 'term-time.simonrowe.dev', 'coparents.simonrowe.dev'])(
    'shares one choice across the site from %s',
    (host) => {
      expect(consentCookieDomain(host)).toBe('.simonrowe.dev')
    },
  )

  it.each(['localhost', 'simonrowe.dev.attacker.example', 'notsimonrowe.dev'])(
    'leaves %s to the browser default',
    (host) => {
      expect(consentCookieDomain(host)).toBeUndefined()
    },
  )
})
