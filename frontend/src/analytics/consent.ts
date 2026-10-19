import * as CookieConsent from 'vanilla-cookieconsent'
import 'vanilla-cookieconsent/dist/cookieconsent.css'

/**
 * The consent banner, shared by all four apps.
 *
 * vanilla-cookieconsent is self-hosted (MIT, no third-party service), so asking for consent does
 * not itself send anything anywhere. Its choice is stored in one cookie on `.simonrowe.dev`, so a
 * visitor who chose on the main site is not asked again on Term Time, CoParent or Meals.
 *
 * "Necessary" covers what the apps cannot work without and needs no consent: Auth0's tokens, the
 * theme preference and this choice itself. "Analytics" is Amplitude, its session replay included.
 */
export const ANALYTICS_CATEGORY = 'analytics'
export const CONSENT_COOKIE = 'sr_consent'

/** `.simonrowe.dev` on any of the site's hostnames; the browser's default (this host) elsewhere. */
export function consentCookieDomain(hostname: string): string | undefined {
  return hostname === 'simonrowe.dev' || hostname.endsWith('.simonrowe.dev') ? '.simonrowe.dev' : undefined
}

export interface ConsentHandlers {
  onGranted: () => void
  onWithdrawn: () => void
  /** A page loaded with a choice on record that does not include analytics. */
  onDeclined: () => void
}

export function startConsent(handlers: ConsentHandlers, hostname = window.location.hostname): void {
  // Spread rather than `domain: undefined`: the library reads cookie.domain with .slice() and an
  // explicit undefined replaces its own default (this host), so off the site's hostnames every
  // "Reject" threw inside autoClear and the change callback that withdraws analytics never ran.
  const domain = consentCookieDomain(hostname)
  const onDomain = domain ? { domain } : {}

  void CookieConsent.run({
    cookie: { name: CONSENT_COOKIE, expiresAfterDays: 182, ...onDomain },
    guiOptions: {
      consentModal: { layout: 'bar inline', position: 'bottom', equalWeightButtons: true },
      preferencesModal: { layout: 'box', equalWeightButtons: true },
    },
    categories: {
      necessary: { enabled: true, readOnly: true },
      [ANALYTICS_CATEGORY]: {
        autoClear: { cookies: [{ name: /^AMP_/, ...onDomain }] },
      },
    },
    // Fires on the first choice and again on every page load once a choice exists.
    onConsent: () => {
      if (CookieConsent.acceptedCategory(ANALYTICS_CATEGORY)) handlers.onGranted()
      else handlers.onDeclined()
    },
    onChange: ({ changedCategories }) => {
      if (!changedCategories.includes(ANALYTICS_CATEGORY)) return
      if (CookieConsent.acceptedCategory(ANALYTICS_CATEGORY)) handlers.onGranted()
      else handlers.onWithdrawn()
    },
    language: {
      default: 'en',
      translations: {
        en: {
          consentModal: {
            title: 'Help improve this site?',
            description:
              'With your permission, this site uses Amplitude to see which pages are used and to replay ' +
              'visits: what was clicked and scrolled. Anything you type is hidden from the replay. The data ' +
              'is held in the EU, and nothing is sent unless you accept.',
            acceptAllBtn: 'Accept',
            acceptNecessaryBtn: 'Reject',
            showPreferencesBtn: 'Choose',
          },
          preferencesModal: {
            title: 'Cookie settings',
            acceptAllBtn: 'Accept all',
            acceptNecessaryBtn: 'Reject all',
            savePreferencesBtn: 'Save',
            closeIconLabel: 'Close',
            sections: [
              {
                title: 'Strictly necessary',
                description:
                  'Signing in, your light or dark theme, and remembering this choice. These are always on.',
                linkedCategory: 'necessary',
              },
              {
                title: 'Analytics and session replay',
                description:
                  'Amplitude records page views, clicks and an anonymised replay of the visit, so the site ' +
                  'owner can see where people get stuck. Form fields are always hidden, and on CoParent all ' +
                  'text and images are hidden too. Data is stored in Amplitude’s EU data centre.',
                linkedCategory: ANALYTICS_CATEGORY,
              },
            ],
          },
        },
      },
    },
  })
}

export function showConsentPreferences(): void {
  CookieConsent.showPreferences()
}
