import type { AmplitudeHandle } from './amplitude'
import { sanitiseProperties, type EventArgs, type EventName } from './events'
import type { AnalyticsApp } from './policy'

/**
 * The only analytics module the apps import. Tests mock this one file.
 *
 * Nothing happens until two things are true: the build carries an Amplitude key
 * (VITE_AMPLITUDE_API_KEY, unset locally) and the visitor has accepted analytics in the consent
 * banner. Before that every call is a no-op and nothing is queued, so an event from before consent
 * can never be sent after it. Without a key the banner is not shown either: there is nothing to
 * ask about.
 */

export interface AnalyticsDependencies {
  apiKey: string | undefined
  origin: string
  startConsent: (handlers: { onGranted: () => void; onWithdrawn: () => void; onDeclined: () => void }) => void
  showPreferences: () => void
  load: (apiKey: string, app: AnalyticsApp, origin: string) => Promise<AmplitudeHandle>
  /** Deletes everything Amplitude stored in this browser, then reloads if asked to. */
  purge: (reload: boolean) => void
}

interface Identity {
  userId: string
  isOwner: boolean
}

interface State {
  deps?: AnalyticsDependencies
  app?: AnalyticsApp
  handle?: AmplitudeHandle
  loading?: Promise<void>
  pending: Array<(handle: AmplitudeHandle) => void>
  identity?: Identity
}

let state: State = { pending: [] }

const defaultDependencies = (): AnalyticsDependencies => ({
  apiKey: import.meta.env.VITE_AMPLITUDE_API_KEY || undefined,
  origin: window.location.origin,
  startConsent: (handlers) => {
    void import('./consent').then(({ startConsent }) => startConsent(handlers))
  },
  showPreferences: () => {
    void import('./consent').then(({ showConsentPreferences }) => showConsentPreferences())
  },
  load: async (apiKey, app, origin) => {
    const { loadAmplitude } = await import('./amplitude')
    return loadAmplitude(apiKey, app, origin)
  },
  purge: (reload) => void clearStoredAnalytics(reload),
})

/** Amplitude's IndexedDB stores: `AMP_diagnostics_<key>` and `<key>_amp_session_replay_events…`. */
export function isAmplitudeDatabase(name: string): boolean {
  return name.startsWith('AMP_') || name.includes('_amp_session_replay_events')
}

/**
 * Removes what Amplitude keeps in the browser: its cookies (on this host and on `.simonrowe.dev`,
 * where it sets them so one visitor is one device across subdomains), its local storage queue and
 * remote config, and the IndexedDB stores where replay buffers a recording before upload.
 *
 * On withdrawal it then reloads, which is the only way to be sure a running replay recorder has
 * stopped: the SDK is never loaded again while consent is withdrawn. It also runs, without the
 * reload, on every page load where analytics is not accepted, because the SDK writes its opt-out
 * flag and its unsent queue as the withdrawing page unloads, after the first purge has run.
 */
export async function clearStoredAnalytics(reload: boolean): Promise<void> {
  const host = window.location.hostname
  const domains = ['', `; domain=${host}`, '; domain=.simonrowe.dev']
  for (const cookie of document.cookie.split(';')) {
    const name = cookie.split('=')[0].trim()
    if (!name.startsWith('AMP_')) continue
    for (const domain of domains) {
      document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/${domain}`
    }
  }
  for (const key of Object.keys(window.localStorage)) {
    if (key.startsWith('AMP_')) window.localStorage.removeItem(key)
  }
  // databases() is missing from older browsers; there the stores are left, which is the most
  // that can be done without knowing their names. A delete blocked by the SDK's own open
  // connection completes when the reload closes it.
  if (typeof indexedDB !== 'undefined' && typeof indexedDB.databases === 'function') {
    try {
      for (const { name } of await indexedDB.databases()) {
        if (name && isAmplitudeDatabase(name)) indexedDB.deleteDatabase(name)
      }
    } catch {
      // Listing can be refused (private mode in some browsers). The cookies are already gone.
    }
  }
  if (reload) window.location.reload()
}

export function isAnalyticsConfigured(): boolean {
  return Boolean(state.deps?.apiKey)
}

/** Called once from each app's entry point. Safe to call again (StrictMode, tests): it is ignored. */
export function initAnalytics(app: AnalyticsApp, deps: AnalyticsDependencies = defaultDependencies()): void {
  if (state.app) return
  state = { ...state, app, deps }
  if (!deps.apiKey) return
  deps.startConsent({ onGranted: enable, onWithdrawn: withdraw, onDeclined: () => deps.purge(false) })
}

function enable(): void {
  const { deps, app } = state
  if (!deps?.apiKey || !app || state.loading) return
  state.loading = deps
    .load(deps.apiKey, app, deps.origin)
    .then((handle) => {
      state.handle = handle
      if (state.identity) handle.setUser(state.identity.userId, state.identity.isOwner)
      const queued = state.pending
      state.pending = []
      for (const call of queued) call(handle)
    })
    .catch(() => {
      // A failed load (blocked script, offline) leaves analytics off for this page view.
      state.loading = undefined
      state.pending = []
    })
}

function withdraw(): void {
  state.handle?.optOut()
  state.handle = undefined
  state.pending = []
  state.deps?.purge(true)
}

function whenLoaded(call: (handle: AmplitudeHandle) => void): void {
  if (state.handle) call(state.handle)
  else if (state.loading) state.pending.push(call)
}

export function track<N extends EventName>(name: N, ...args: EventArgs<N>): void {
  const properties = sanitiseProperties(name, args[0])
  whenLoaded((handle) => handle.track(name, properties))
}

/** Signed-in visitors are identified by their Auth0 subject only: never a name or an email. */
export function identifyUser(userId: string, isOwner: boolean): void {
  const previous = state.identity
  if (previous?.userId === userId && previous.isOwner === isOwner) return
  state.identity = { userId, isOwner }
  whenLoaded((handle) => handle.setUser(userId, isOwner))
}

/**
 * Called on sign-out. Only acts if someone had been identified: resetting on every anonymous
 * page load would mint a new device id each time and split one visitor into many.
 */
export function clearIdentity(): void {
  if (!state.identity) return
  state.identity = undefined
  whenLoaded((handle) => handle.resetUser())
}

export function openCookieSettings(): void {
  state.deps?.showPreferences()
}

/** Test seam: forget everything, as a fresh page load would. */
export function resetAnalyticsForTests(): void {
  state = { pending: [] }
}
