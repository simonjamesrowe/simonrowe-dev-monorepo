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
  // replay shows layout and clicks and nothing that was on screen. The app's root also carries
  // data-amp-mask, which is what keeps clicked text out of autocaptured events.
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
