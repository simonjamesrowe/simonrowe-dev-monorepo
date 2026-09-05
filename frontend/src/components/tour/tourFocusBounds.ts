export interface FocusBounds {
  top: number
  right: number
  bottom: number
  left: number
}

const FOCUS_PADDING = 8

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
    .some((side) => parseFloat(style[side as 'borderTopWidth']) > 0)
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

function textRects(node: Element): DOMRect[] {
  const rects: DOMRect[] = []
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
    if (rect.width > 0 && rect.height > 0) {
      rects.push(rect)
    }
  }
  return rects
}

function collectContentRects(root: Element): DOMRect[] {
  const rects: DOMRect[] = [...textRects(root)]
  for (const child of Array.from(root.children)) {
    if (!isVisible(child)) {
      continue
    }
    if (isSelfContained(child) || isSurface(child)) {
      rects.push(child.getBoundingClientRect())
      continue
    }
    rects.push(...collectContentRects(child))
  }
  return rects
}

export function getFocusBounds(element: Element): FocusBounds {
  const rect = element.getBoundingClientRect()
  const contentRects = collectContentRects(element)

  let top = rect.top
  let right = rect.right
  let bottom = rect.bottom
  let left = rect.left

  if (contentRects.length > 0) {
    // Hug the content, but never escape the element the tour step actually names.
    top = Math.max(rect.top, Math.min(...contentRects.map((r) => r.top)))
    right = Math.min(rect.right, Math.max(...contentRects.map((r) => r.right)))
    bottom = Math.min(rect.bottom, Math.max(...contentRects.map((r) => r.bottom)))
    left = Math.max(rect.left, Math.min(...contentRects.map((r) => r.left)))
  }

  // A degenerate union (everything clipped or collapsed) is worse than the plain box.
  if (right - left <= 0 || bottom - top <= 0) {
    top = rect.top
    right = rect.right
    bottom = rect.bottom
    left = rect.left
  }

  return {
    top: Math.max(0, top - FOCUS_PADDING),
    right: Math.min(window.innerWidth, right + FOCUS_PADDING),
    bottom: Math.min(window.innerHeight, bottom + FOCUS_PADDING),
    left: Math.max(0, left - FOCUS_PADDING),
  }
}
