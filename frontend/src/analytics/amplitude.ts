import * as amplitude from '@amplitude/analytics-browser'
import { sessionReplayPlugin } from '@amplitude/plugin-session-replay-browser'

import { siteEnrichment } from './enrichment'
import { REPLAY_POLICIES, type AnalyticsApp } from './policy'

/**
 * The one module that imports the Amplitude SDK. analytics.ts reaches it only through a dynamic
 * import after consent, so a visitor who has not opted in never downloads it.
 *
 * Every request goes to this origin's /amp/ paths, which frontend/nginx.conf proxies to
 * Amplitude's EU endpoints. Ad blockers drop requests to *.amplitude.com, and this site's
 * audience runs them. The four paths are every Amplitude host the SDK contacts once diagnostics
 * are off (read from the SDK's own constants): events, replay upload, and one config host shared
 * by replay sampling and remote autocapture settings.
 */
export const PROXY_PATHS = {
  events: '/amp/e',
  replay: '/amp/sr',
  config: '/amp/cfg',
} as const

export interface AmplitudeHandle {
  track(name: string, properties: Record<string, unknown>): void
  setUser(userId: string | undefined, isOwner: boolean): void
  resetUser(): void
  optOut(): void
}

export function amplitudeOptions(origin: string) {
  return {
    serverZone: 'EU' as const,
    serverUrl: `${origin}${PROXY_PATHS.events}`,
    // What is captured is decided here, in code review, and nowhere else. Remote config would let
    // a toggle in Amplitude's UI switch on capture this file has ruled out (network requests and
    // their URLs, say), so the analytics SDK does not fetch it. Replay still fetches its own
    // remote config (sampling), through /amp/cfg/, from the replay plugin.
    remoteConfig: { fetchRemoteConfig: false, serverUrl: `${origin}${PROXY_PATHS.config}/config` },
    // Diagnostics go to a fifth host and report on the SDK, not on visitors.
    enableDiagnostics: false,
    autocapture: {
      // Where a visitor came from is recorded once, as user properties on an identify event,
      // which passes through siteEnrichment like any other event. The default also copies the
      // campaign, `referrer` included, onto EVERY event from a plugin that runs after ours, so a
      // reload of /news-events?q=... sent the search text as the referrer of each click.
      // Internal referrers are this site's own pages, which are not a traffic source.
      attribution: { trackingMethod: 'userProperty' as const, excludeInternalReferrers: true },
      pageViews: { trackHistoryChanges: 'all' as const },
      sessions: true,
      formInteractions: true,
      fileDownloads: true,
      elementInteractions: true,
      frustrationInteractions: true,
      // Off for the same reason: it registers late in init and stamps the full page address,
      // query string included, onto every event after siteEnrichment has scrubbed it.
      // siteEnrichment adds a query-free `page_path` instead.
      //
      // Both of these were found by reading a real request body, not by a unit test, because
      // the problem is plugin ORDER: anything that edits events and registers after ours undoes
      // the scrub. analyticsWire.test.ts drives the real SDK and fails if a query string gets out.
      pageUrlEnrichment: false,
    },
  }
}

export function replayOptions(app: AnalyticsApp, origin: string) {
  const policy = REPLAY_POLICIES[app]
  return {
    // Amplitude's remote replay settings override this, so the rate can be lowered from the
    // Amplitude UI without a deploy when the free plan's monthly quota runs short.
    sampleRate: 1,
    trackServerUrl: `${origin}${PROXY_PATHS.replay}/sessions/v2/track`,
    configServerUrl: `${origin}${PROXY_PATHS.config}/config`,
    privacyConfig: {
      defaultMaskLevel: policy.defaultMaskLevel,
      maskSelector: policy.maskSelector,
      blockSelector: policy.blockSelector,
    },
  }
}

export function loadAmplitude(apiKey: string, app: AnalyticsApp, origin: string): AmplitudeHandle {
  amplitude.add(siteEnrichment(app))
  amplitude.add(sessionReplayPlugin(replayOptions(app, origin)))
  amplitude.init(apiKey, amplitudeOptions(origin))

  return {
    track: (name, properties) => {
      amplitude.track(name, properties)
    },
    setUser: (userId, isOwner) => {
      amplitude.setUserId(userId)
      // Tagged, never excluded: Meals has no other user, so dropping the owner would leave it
      // empty. Charts filter on is_owner instead.
      amplitude.identify(new amplitude.Identify().set('is_owner', isOwner))
    },
    resetUser: () => {
      amplitude.reset()
    },
    optOut: () => {
      amplitude.setOptOut(true)
    },
  }
}
