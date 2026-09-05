export interface FocusBounds {
  top: number
  right: number
  bottom: number
  left: number
}

const FOCUS_PADDING = 8

/**
 * The most of the viewport a spotlight may occupy vertically.
 *
 * Some tour targets are whole page sections — the news feed is over 7,000px tall — and a
 * spotlight that reaches every edge is indistinguishable from no spotlight at all. A tall
 * target is therefore shown from its top down to this fraction of the screen: the filters and
 * the first rows of content are lit, and the dimming below makes it read as a highlight rather
 * than a page that simply un-dimmed. `TourProvider` scrolls tall targets to their top for the
 * same reason, so what is lit is the start of the section and not an arbitrary middle slice.
 */
const MAX_VIEWPORT_FRACTION = 0.8

/**
 * A tour target's layout box is routinely far wider than anything a visitor can see in it:
 * a full-bleed band, or a flex row holding two centred buttons, both measure the width of
 * the page. Spotlighting that box leaves a large undimmed area that reads as a white block
 * rather than as a pointer at the thing being described.
 *
 * So the spotlight is measured from the target's visible content instead of its layout box:
 * the text runs, the replaced and interactive elements, and any descendant carrying its own
 * background, border or shadow. Those descendants are treated as whole surfaces and not
 * descended into, so a card keeps its padding and is never cropped to the words inside it.
 *
 * The result is always clamped to the target's own rect, so this can only ever tighten a
 * spotlight, never grow one past the element the step names.
 */
function isVisible(element: Element): boolean {
  const style = window.getComputedStyle(element)
  if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
    return false
  }
  const rect = element.getBoundingClientRect()
  return rect.width > 0 && rect.height > 0
}

function isSurface(element: Element): boolean {
  const style = window.getComputedStyle(element)
  const background = style.backgroundColor
  const hasBackground = background !== 'transparent'
    && background !== 'rgba(0, 0, 0, 0)'
    && background !== ''
  const hasBackgroundImage = style.backgroundImage !== 'none' && style.backgroundImage !== ''
  const hasBorder = ['borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth']
    .some((side) => Number.parseFloat(style[side as 'borderTopWidth']) > 0)
  const hasShadow = style.boxShadow !== 'none' && style.boxShadow !== ''
  return hasBackground || hasBackgroundImage || hasBorder || hasShadow
}

const SELF_CONTAINED = new Set([
  'IMG', 'SVG', 'VIDEO', 'CANVAS', 'PICTURE',
  'INPUT', 'TEXTAREA', 'SELECT', 'BUTTON',
])

function isSelfContained(element: Element): boolean {
  return SELF_CONTAINED.has(element.tagName)
}

/**
 * The rects of a node's own text runs, each clamped to the node's box.
 *
 * A range reports where text *would* be laid out, not what is on screen. A screen-reader-only
 * label is the case that matters: it is typically a 1x1 clipped box holding a full sentence, so
 * its range rect is as wide as the sentence and would widen the spotlight around something
 * nobody can see. Clamping to the containing element covers that along with `overflow: hidden`,
 * `text-indent` and `font-size: 0` in one rule, rather than sniffing for each trick in turn —
 * and note the containing element passing `isVisible` does not rule any of them out, since a
 * clipped 1x1 box is visible by every measure that function applies.
 */
function textRects(node: Element): DOMRect[] {
  const rects: DOMRect[] = []
  const bounds = node.getBoundingClientRect()
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeType !== Node.TEXT_NODE || !child.textContent?.trim()) {
      continue
    }
    const range = document.createRange()
    range.selectNodeContents(child)
    // Range measurement is unavailable in jsdom and could be absent in an old engine.
    // Losing a text run only widens the spotlight to the element box; throwing here
    // would take the whole overlay down, so the capability is checked rather than assumed.
    if (typeof range.getBoundingClientRect !== 'function') {
      continue
    }
    const rect = range.getBoundingClientRect()
    const left = Math.max(rect.left, bounds.left)
    const right = Math.min(rect.right, bounds.right)
    const top = Math.max(rect.top, bounds.top)
    const bottom = Math.min(rect.bottom, bounds.bottom)
    if (right > left && bottom > top) {
      rects.push(new DOMRect(left, top, right - left, bottom - top))
    }
  }
  return rects
}

/**
 * Content found inside a target, split by whether it is allowed to leave the target's box.
 *
 * `contained` is ordinary in-flow content and is clamped to the target, so the spotlight can
 * only ever tighten. `escaping` is out-of-flow content — an absolutely positioned dropdown,
 * popover or menu anchored to the target — which is deliberately *not* clamped: the search
 * step's whole purpose is to show its autocomplete results, and those render in a panel that
 * overflows the input it belongs to. Clamping them away lit the input and dimmed the results.
 */
interface ContentRects {
  contained: DOMRect[]
  escaping: DOMRect[]
}

function isOutOfFlow(element: Element): boolean {
  const position = window.getComputedStyle(element).position
  return position === 'absolute' || position === 'fixed'
}

function collectContentRects(root: Element): ContentRects {
  const contained: DOMRect[] = [...textRects(root)]
  const escaping: DOMRect[] = []
  for (const child of Array.from(root.children)) {
    if (!isVisible(child)) {
      continue
    }
    if (isOutOfFlow(child)) {
      escaping.push(child.getBoundingClientRect())
      continue
    }
    if (isSelfContained(child) || isSurface(child)) {
      contained.push(child.getBoundingClientRect())
      continue
    }
    const nested = collectContentRects(child)
    contained.push(...nested.contained)
    escaping.push(...nested.escaping)
  }
  return { contained, escaping }
}

export function getFocusBounds(element: Element): FocusBounds {
  const rect = element.getBoundingClientRect()
  const { contained, escaping } = collectContentRects(element)

  let top = rect.top
  let right = rect.right
  let bottom = rect.bottom
  let left = rect.left

  if (contained.length > 0) {
    // Hug the content, but never escape the element the tour step actually names.
    top = Math.max(rect.top, Math.min(...contained.map((r) => r.top)))
    right = Math.min(rect.right, Math.max(...contained.map((r) => r.right)))
    bottom = Math.min(rect.bottom, Math.max(...contained.map((r) => r.bottom)))
    left = Math.max(rect.left, Math.min(...contained.map((r) => r.left)))
  }

  for (const escaped of escaping) {
    top = Math.min(top, escaped.top)
    right = Math.max(right, escaped.right)
    bottom = Math.max(bottom, escaped.bottom)
    left = Math.min(left, escaped.left)
  }

  // A degenerate union (everything clipped or collapsed) is worse than the plain box.
  if (right - left <= 0 || bottom - top <= 0) {
    top = rect.top
    right = rect.right
    bottom = rect.bottom
    left = rect.left
  }

  const padded = {
    top: Math.max(0, top - FOCUS_PADDING),
    right: Math.min(window.innerWidth, right + FOCUS_PADDING),
    bottom: Math.min(window.innerHeight, bottom + FOCUS_PADDING),
    left: Math.max(0, left - FOCUS_PADDING),
  }

  const maxHeight = window.innerHeight * MAX_VIEWPORT_FRACTION
  if (padded.bottom - padded.top > maxHeight) {
    padded.bottom = padded.top + maxHeight
  }
  return padded
}
