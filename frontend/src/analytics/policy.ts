/**
 * What each app lets session replay see.
 *
 * Masking is driven by two attributes in the markup rather than per-component CSS selectors, so
 * whether something is hidden is visible where it is rendered:
 *   - `data-amp-mask`: replay shows the element's text as asterisks, and autocapture records
 *     "*****" as the text of anything clicked inside it. Both Amplitude SDKs honour it natively.
 *   - `data-amp-block`: replay draws an empty box of the same size. Used for the whole admin area
 *     and, in CoParent, for every image.
 *
 * Mask levels are Amplitude's: `medium` masks every form field but leaves page text visible,
 * `conservative` masks all text as well.
 *
 * The level set here is only a fallback. The project's masking setting in Amplitude (Settings →
 * Session Replay & Heatmaps) overrides `defaultMaskLevel` (remote wins in the replay SDK's
 * joined config), and the plugin passes no per-URL levels through. Selectors are different: the
 * SDK keeps ours alongside any set remotely. So anything that MUST stay hidden is hidden by a
 * selector or attribute, never by the level alone.
 */
export type AnalyticsApp = 'main' | 'term-time' | 'coparent' | 'meals'

export const MASK_ATTRIBUTE = 'data-amp-mask'
export const BLOCK_ATTRIBUTE = 'data-amp-block'

export interface ReplayPolicy {
  defaultMaskLevel: 'medium' | 'conservative'
  maskSelector: string[]
  blockSelector: string[]
}

const MASKED = [`[${MASK_ATTRIBUTE}]`]
const BLOCKED = [`[${BLOCK_ATTRIBUTE}]`]

export const REPLAY_POLICIES: Record<AnalyticsApp, ReplayPolicy> = {
  main: { defaultMaskLevel: 'medium', maskSelector: MASKED, blockSelector: BLOCKED },
  'term-time': { defaultMaskLevel: 'medium', maskSelector: MASKED, blockSelector: BLOCKED },
  // Family finances, receipts, bank statements and messages between parents. Every word is
  // masked and every image (receipts, avatars, statement previews) is blocked, so a CoParent
  // replay shows layout and clicks and nothing that was on screen.
  //
  // What guarantees it is NOT the level below, which Amplitude's project setting overrides. It is
  // `data-amp-mask` on the app's root (masking checks `closest('[data-amp-mask]')`, so it covers
  // every element inside) plus these block selectors. CoParent renders nothing outside that root:
  // no portals and no portal-based UI library. Adding one would put its text outside the mask.
  coparent: {
    defaultMaskLevel: 'conservative',
    maskSelector: MASKED,
    blockSelector: [...BLOCKED, 'img', 'picture', 'svg image', 'canvas', 'video', 'iframe', 'object', 'embed'],
  },
  meals: { defaultMaskLevel: 'medium', maskSelector: MASKED, blockSelector: BLOCKED },
}

/**
 * Query parameters an analytics URL may keep. Everything else is removed from every URL-shaped
 * property before an event leaves the browser: `?q=` holds what a visitor typed into search,
 * and Auth0's callback carries `code` and `state`. An allowlist rather than a blocklist, so a
 * parameter added later is private until someone decides otherwise.
 */
export const KEPT_QUERY_PARAMS = new Set([
  'utm_source',
  'utm_medium',
  'utm_campaign',
  'utm_term',
  'utm_content',
  'article',
  'event',
  'job',
  'skillGroup',
])
