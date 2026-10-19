import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CookieConsentConfig } from 'vanilla-cookieconsent'

import { ANALYTICS_CATEGORY, startConsent } from './consent'

const accepted = new Set<string>()

vi.mock('vanilla-cookieconsent', () => ({
  run: vi.fn(async () => undefined),
  acceptedCategory: vi.fn((category: string) => accepted.has(category)),
  showPreferences: vi.fn(),
}))
vi.mock('vanilla-cookieconsent/dist/cookieconsent.css', () => ({}))

import * as CookieConsent from 'vanilla-cookieconsent'

function configFor(hostname: string) {
  const handlers = { onGranted: vi.fn(), onWithdrawn: vi.fn(), onDeclined: vi.fn() }
  startConsent(handlers, hostname)
  const calls = vi.mocked(CookieConsent.run).mock.calls
  const config = calls[calls.length - 1][0] as CookieConsentConfig
  return { config, handlers }
}

beforeEach(() => {
  vi.mocked(CookieConsent.run).mockClear()
  accepted.clear()
})

describe('consent configuration', () => {
  it('stores the choice on .simonrowe.dev, so one choice covers all four apps', () => {
    const { config } = configFor('term-time.simonrowe.dev')
    expect(config.cookie?.domain).toBe('.simonrowe.dev')
    const clear = (config.categories[ANALYTICS_CATEGORY].autoClear?.cookies ?? [])[0]
    expect(clear?.domain).toBe('.simonrowe.dev')
  })

  it('leaves the domain out entirely elsewhere, because an explicit undefined breaks Reject', () => {
    const { config } = configFor('localhost')
    expect(config.cookie).not.toHaveProperty('domain')
    const clear = (config.categories[ANALYTICS_CATEGORY].autoClear?.cookies ?? [])[0]
    expect(clear).not.toHaveProperty('domain')
  })

  it('gives Accept and Reject equal weight', () => {
    const { config } = configFor('www.simonrowe.dev')
    expect(config.guiOptions?.consentModal?.equalWeightButtons).toBe(true)
    expect(config.guiOptions?.preferencesModal?.equalWeightButtons).toBe(true)
  })
})

describe('consent callbacks', () => {
  it('turns analytics on when a page loads with analytics already accepted', () => {
    const { config, handlers } = configFor('www.simonrowe.dev')
    accepted.add(ANALYTICS_CATEGORY)

    config.onConsent?.({ cookie: { categories: [ANALYTICS_CATEGORY] } as never })

    expect(handlers.onGranted).toHaveBeenCalledTimes(1)
  })

  it('leaves analytics off, and clears any leftovers, when only the necessary category is accepted', () => {
    const { config, handlers } = configFor('www.simonrowe.dev')

    config.onConsent?.({ cookie: { categories: ['necessary'] } as never })

    expect(handlers.onGranted).not.toHaveBeenCalled()
    expect(handlers.onDeclined).toHaveBeenCalledTimes(1)
  })

  it('withdraws when analytics is switched off later', () => {
    const { config, handlers } = configFor('www.simonrowe.dev')

    config.onChange?.({ cookie: {} as never, changedCategories: [ANALYTICS_CATEGORY], changedServices: {} })

    expect(handlers.onWithdrawn).toHaveBeenCalledTimes(1)
    expect(handlers.onGranted).not.toHaveBeenCalled()
  })

  it('ignores a change that does not touch analytics', () => {
    const { config, handlers } = configFor('www.simonrowe.dev')

    config.onChange?.({ cookie: {} as never, changedCategories: ['necessary'], changedServices: {} })

    expect(handlers.onWithdrawn).not.toHaveBeenCalled()
    expect(handlers.onGranted).not.toHaveBeenCalled()
  })
})
