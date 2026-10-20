/**
 * The rendered summary or post as one string, and the way between that string and the DOM.
 *
 * A snippet is stored as text (`exact`, plus a little `prefix` and `suffix`) and found again by
 * searching for that text, never by character offsets, so it survives the summary being
 * re-rendered or regenerated (spec 054 §4.3). This module owns both directions: a selection
 * becomes a quote, and a quote becomes a `Range` to highlight.
 */

/** Longest quote the server accepts. Longer is refused with a message, never cut short. */
export const MAX_QUOTE_LENGTH = 2000
/** How much surrounding text is kept to find a quote again. */
export const CONTEXT_LENGTH = 32

/** Elements that start a new block, so text either side of their edge is a new paragraph. */
const BLOCK_TAGS = new Set([
  'P', 'LI', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BLOCKQUOTE', 'PRE', 'TD', 'TH', 'DT', 'DD',
])
/** Containers whose whitespace-only text is markup layout, not words a reader sees. */
const LAYOUT_PARENTS = new Set(['UL', 'OL', 'BLOCKQUOTE', 'TABLE', 'THEAD', 'TBODY', 'TR'])
/**
 * Text that is in the DOM but is not prose a reader selects: a post's Mermaid diagram is an
 * SVG whose labels are text nodes, and raw HTML can carry a style or script element. The server
 * leaves the same things out when it checks a quote.
 */
const NOT_PROSE = 'svg, style, script'

interface Segment {
  node: Text
  /** Offset of the node's first character in `text`. */
  start: number
  end: number
}

export interface ProseText {
  /** Every block's text, blocks separated by a blank line. */
  text: string
  segments: Segment[]
}

export interface Quote {
  exact: string
  prefix: string
  suffix: string
  start: number
  end: number
}

function blockOf(node: Node, root: Element): Element | null {
  for (let current = node.parentElement; current && current !== root;
    current = current.parentElement) {
    if (BLOCK_TAGS.has(current.tagName)) return current
  }
  return null
}

function isNotProse(node: Text): boolean {
  return node.parentElement?.closest(NOT_PROSE) != null
}

function isLayoutWhitespace(node: Text, root: Element): boolean {
  if (node.data.trim() !== '') return false
  const parent = node.parentElement
  return parent === root || (parent !== null && LAYOUT_PARENTS.has(parent.tagName))
}

/**
 * Reads the prose into one string. react-markdown puts a newline text node between blocks;
 * those are dropped, and each block boundary becomes a blank line instead, which is what a
 * quote across paragraphs keeps and what the server collapses to a space when it checks it.
 */
export function readProse(root: Element): ProseText {
  const segments: Segment[] = []
  let text = ''
  let lastBlock: Element | null | undefined
  const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const textNode = node as Text
    if (textNode.data.length === 0 || isLayoutWhitespace(textNode, root) || isNotProse(textNode)) {
      continue
    }
    const block = blockOf(textNode, root)
    if (segments.length > 0 && block !== lastBlock) text += '\n\n'
    const start = text.length
    text += textNode.data
    segments.push({ node: textNode, start, end: text.length })
    lastBlock = block
  }
  return { text, segments }
}

/**
 * The part of a selection that falls inside the prose, as a quote. Anything selected outside
 * the prose (the title, the disclosure) is left out, because only the segments of the prose
 * are looked at. Leading and trailing whitespace is trimmed.
 *
 * @returns null when no prose text is selected
 */
export function quoteFromRange(prose: ProseText, range: Range): Quote | null {
  let start = -1
  let end = -1
  for (const segment of prose.segments) {
    if (!range.intersectsNode(segment.node)) continue
    const from = range.startContainer === segment.node ? range.startOffset : 0
    const to = range.endContainer === segment.node ? range.endOffset : segment.node.length
    if (to <= from) continue
    if (start < 0) start = segment.start + from
    end = segment.start + to
  }
  if (start < 0) return null
  while (start < end && /\s/.test(prose.text[start])) start += 1
  while (end > start && /\s/.test(prose.text[end - 1])) end -= 1
  if (end <= start) return null
  return {
    exact: prose.text.slice(start, end),
    prefix: prose.text.slice(Math.max(0, start - CONTEXT_LENGTH), start),
    suffix: prose.text.slice(end, end + CONTEXT_LENGTH),
    start,
    end,
  }
}

/**
 * Where a stored quote is in the prose now: first with its surrounding text, which tells two
 * identical phrases apart, then on its own.
 *
 * @returns the start offset, or -1 when the quote is no longer there
 */
export function locate(
  text: string,
  quote: { exact: string; prefix: string; suffix: string },
): number {
  if (!quote.exact) return -1
  if (quote.prefix || quote.suffix) {
    const withContext = text.indexOf(quote.prefix + quote.exact + quote.suffix)
    if (withContext >= 0) return withContext + quote.prefix.length
  }
  return text.indexOf(quote.exact)
}

/** A DOM range covering `[start, end)` of the prose text, or null if nothing is there. */
export function rangeFor(prose: ProseText, start: number, end: number): Range | null {
  const first = prose.segments.find(segment => segment.end > start)
  const last = [...prose.segments].reverse().find(segment => segment.start < end)
  if (!first || !last || end <= start) return null
  const range = first.node.ownerDocument.createRange()
  range.setStart(first.node, Math.max(0, start - first.start))
  range.setEnd(last.node, Math.min(last.node.length, end - last.start))
  return range
}

/** The prose offset of a text position, or -1 when the position is not in the prose. */
export function offsetOf(prose: ProseText, node: Node, offset: number): number {
  const segment = prose.segments.find(candidate => candidate.node === node)
  return segment ? segment.start + Math.min(offset, segment.node.length) : -1
}

/**
 * The text position under a point. `caretPositionFromPoint` is the standard and
 * `caretRangeFromPoint` the WebKit spelling; jsdom has neither.
 */
export function caretAt(x: number, y: number): { node: Node; offset: number } | null {
  const doc = document as Document & {
    caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null
    caretRangeFromPoint?: (x: number, y: number) => Range | null
  }
  if (typeof doc.caretPositionFromPoint === 'function') {
    const position = doc.caretPositionFromPoint(x, y)
    return position ? { node: position.offsetNode, offset: position.offset } : null
  }
  if (typeof doc.caretRangeFromPoint === 'function') {
    const range = doc.caretRangeFromPoint(x, y)
    return range ? { node: range.startContainer, offset: range.startOffset } : null
  }
  return null
}

/** Whether this browser can draw highlights without touching the DOM. */
export function supportsHighlights(): boolean {
  return typeof CSS !== 'undefined' && 'highlights' in CSS && typeof Highlight !== 'undefined'
}
