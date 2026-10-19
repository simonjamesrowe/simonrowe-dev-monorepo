/**
 * Every named analytics event, and the only properties each may carry.
 *
 * This is a runtime schema rather than a TypeScript interface on purpose: `track` checks each
 * call against it and drops anything that does not fit, so the privacy rule below holds even
 * for a call site that gets past the compiler with a cast.
 *
 * The rule: an event property is never free text and never money. There is no "text" kind to
 * reach for. A property is one of
 *   - an enum, given as the list of values it may take;
 *   - 'id', an opaque identifier (a blog id, a slug), checked against ID_PATTERN at runtime so a
 *     sentence or an email address cannot pass for one;
 *   - 'count' or 'duration', a non-negative number;
 *   - 'flag', a boolean.
 * A question, a recipe title, an amount or a child's name has no kind it could be sent as.
 *
 * Every event also gets `app` (which of the four apps sent it), added by the enrichment plugin
 * rather than here, so autocaptured events carry it too.
 */
export const EVENTS = {
  // Main site
  'Chat Opened': { source: ['hero', 'pill', 'header', 'search', 'mobile', 'tour'] },
  'Chat Question Sent': {},
  'Chat Answer Received': { toolCount: 'count', latencyMs: 'duration', failed: 'flag' },
  'Search Performed': { surface: ['header', 'mobile', 'news', 'blogs'], resultCount: 'count' },
  'Blog Viewed': { blogId: 'id' },
  'Share Clicked': { contentType: ['blog', 'article', 'event'], method: ['native', 'copy'] },
  'Summary Opened': { articleId: 'id', ready: 'flag' },
  'Summary Requested': { articleId: 'id' },
  'Listen Pressed': { contentType: ['blog', 'article'], ready: 'flag' },
  'Playlist Played': { queueLength: 'count' },
  'Narration Completed': { contentType: ['blog', 'article'], fromPlaylist: 'flag' },
  'Tour Started': { stepCount: 'count' },
  'Tour Step Viewed': { step: 'count' },
  'Tour Completed': {},
  'Tour Dismissed': { step: 'count' },
  'Contact Form Submitted': {},
  'Portfolio Project Viewed': { slug: 'id' },
  'Favourite Toggled': { contentType: ['article', 'event'], favourited: 'flag' },

  // Term Time
  'Question Asked': { source: ['input', 'chip', 'calendar', 'link'], yearGroupCount: 'count' },
  'Answer Received': { toolCount: 'count', latencyMs: 'duration', failed: 'flag' },
  'Calendar Event Clicked': {
    eventType: ['TERM_BOUNDARY', 'HALF_TERM', 'INSET', 'CLUB', 'TRIP', 'OTHER'],
    section: ['today', 'heads-up', 'day'],
  },
  'Year Group Selected': { yearGroupCount: 'count' },
  'Connection Lost': {},

  // CoParent
  'Expense Created': { source: ['manual', 'quick-add', 'statement'], count: 'count' },
  'Expense Agreed': { count: 'count' },
  'Expense Disputed': {},
  'Settlement Recorded': { expenseCount: 'count' },
  'Statement Uploaded': { rowCount: 'count', suggestedCount: 'count' },
  'Invite Sent': {},
  'Message Sent': { kind: ['message', 'permission'] },

  // CoParent and Meals
  'Quick Add Used': { itemCount: 'count', withPhoto: 'flag' },

  // Meals
  'Recipe Viewed': { recipeId: 'id' },
  'Recipe Cooked': {},
  'Recipe Imported': { via: ['link', 'photo', 'text', 'bundle'] },
  'Shopping List Viewed': { itemCount: 'count' },
  'Up Next Viewed': { itemCount: 'count' },
} as const satisfies Record<string, Record<string, PropertyKind>>

export type PropertyKind = readonly string[] | 'id' | 'count' | 'duration' | 'flag'

/** Letters, digits and `_ : . -` only, so no spaces, `@` or `/`: an id, never a sentence or an email. */
export const ID_PATTERN = /^[A-Za-z0-9_:.-]{1,128}$/

type ValueOf<K> = K extends readonly (infer V)[]
  ? V
  : K extends 'id'
    ? string
    : K extends 'count' | 'duration'
      ? number
      : K extends 'flag'
        ? boolean
        : never

export type EventName = keyof typeof EVENTS
export type EventProperties<N extends EventName> = {
  -readonly [P in keyof (typeof EVENTS)[N]]: ValueOf<(typeof EVENTS)[N][P]>
}

/** Arguments after the event name: none for an event with no properties, else its properties. */
export type EventArgs<N extends EventName> = keyof (typeof EVENTS)[N] extends never
  ? []
  : [properties: EventProperties<N>]

function fits(kind: PropertyKind, value: unknown): boolean {
  if (Array.isArray(kind)) return typeof value === 'string' && kind.includes(value)
  switch (kind) {
    case 'id':
      return typeof value === 'string' && ID_PATTERN.test(value)
    case 'count':
    case 'duration':
      return typeof value === 'number' && Number.isFinite(value) && value >= 0
    case 'flag':
      return typeof value === 'boolean'
    default:
      return false
  }
}

/**
 * Keeps only the properties the schema declares, and only values of the declared kind.
 * A property that does not fit is dropped rather than the whole event, so a bad id still
 * leaves the fact that something happened.
 */
export function sanitiseProperties(name: EventName, properties: unknown): Record<string, unknown> {
  const schema: Record<string, PropertyKind> = EVENTS[name]
  const clean: Record<string, unknown> = {}
  if (properties === null || typeof properties !== 'object') return clean
  for (const [key, value] of Object.entries(properties)) {
    const kind = schema[key]
    if (kind !== undefined && fits(kind, value)) clean[key] = value
  }
  return clean
}
