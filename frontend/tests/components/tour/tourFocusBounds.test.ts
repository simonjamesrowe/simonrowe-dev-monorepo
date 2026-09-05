import { afterEach, describe, expect, it, vi } from 'vitest'

import { getFocusBounds } from '../../../src/components/tour/tourFocusBounds'

/**
 * jsdom gives every element a zero-sized rect, so each test stubs the geometry it needs.
 * `rects` maps a marker attribute to the box that element should report.
 */
function rect(left: number, top: number, width: number, height: number): DOMRect {
  return {
    left, top, width, height, right: left + width, bottom: top + height,
    x: left, y: top, toJSON: () => ({}),
  } as DOMRect
}

function stubGeometry(rects: Record<string, [number, number, number, number]>) {
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: Element,
  ) {
    const key = this.getAttribute('data-rect')
    const [left, top, width, height] = key && rects[key] ? rects[key] : [0, 0, 0, 0]
    return rect(left, top, width, height)
  })
}

/**
 * jsdom's Range has no `getBoundingClientRect`, so text measurement is stubbed by mapping a
 * text node's content to the box it should report.
 */
function stubTextGeometry(byText: Record<string, [number, number, number, number]>) {
  // Assigned rather than spied on: the method does not exist here, so `vi.spyOn` throws.
  Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
    configurable: true,
    writable: true,
    value(this: Range) {
      const key = this.toString().trim()
      const [left, top, width, height] = byText[key] ?? [0, 0, 0, 0]
      return rect(left, top, width, height)
    },
  })
}

function clearTextGeometry() {
  delete (Range.prototype as { getBoundingClientRect?: unknown }).getBoundingClientRect
}

describe('getFocusBounds', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    clearTextGeometry()
  })

  it('hugs the visible content of a band far wider than what it displays', () => {
    document.body.innerHTML = `
      <section data-rect="band">
        <div data-rect="inner"><button data-rect="cta">Get in touch</button></div>
      </section>`
    stubGeometry({
      band: [0, 100, 1440, 200],   // full-bleed section
      inner: [0, 120, 1440, 160],  // transparent flex row, still full width
      cta: [620, 150, 200, 48],    // the only thing a visitor can see
    })

    const bounds = getFocusBounds(document.querySelector('[data-rect="band"]')!)

    // 8px of padding around the button, not the width of the page.
    expect(bounds.left).toBe(612)
    expect(bounds.right).toBe(828)
    expect(bounds.top).toBe(142)
    expect(bounds.bottom).toBe(206)
  })

  it('keeps a card whole rather than cropping it to the words inside', () => {
    document.body.innerHTML = `
      <section data-rect="band">
        <div data-rect="card" style="background-color: rgb(240, 240, 240)">
          <span data-rect="label">Currently</span>
        </div>
      </section>`
    stubGeometry({
      band: [0, 0, 1440, 300],
      card: [100, 40, 600, 200],
      label: [120, 60, 90, 20],
    })

    const bounds = getFocusBounds(document.querySelector('[data-rect="band"]')!)

    // The card carries its own background, so its padding is part of what is being shown.
    expect(bounds.left).toBe(92)
    expect(bounds.right).toBe(708)
  })

  it('never grows the spotlight beyond the element the step names', () => {
    document.body.innerHTML = `
      <div data-rect="target"><span data-rect="overflow">spills out</span></div>`
    stubGeometry({
      target: [100, 100, 200, 50],
      overflow: [0, 80, 900, 400],
    })

    const bounds = getFocusBounds(document.querySelector('[data-rect="target"]')!)

    expect(bounds.left).toBe(92)
    expect(bounds.right).toBe(308)
    expect(bounds.top).toBe(92)
    expect(bounds.bottom).toBe(158)
  })

  it('ignores a screen-reader-only label that is clipped out of sight', () => {
    document.body.innerHTML = `
      <div data-rect="target">
        <span data-rect="srOnly">A long label only a screen reader hears</span>
        <span data-rect="visible">Filter</span>
      </div>`
    stubGeometry({
      target: [100, 100, 900, 40],
      srOnly: [100, 100, 1, 1],      // clipped to 1x1, as sr-only markup is
      visible: [100, 110, 60, 20],
    })
    stubTextGeometry({
      // The range reports where the sentence WOULD lay out, ignoring the clip.
      'A long label only a screen reader hears': [100, 100, 800, 20],
      Filter: [100, 110, 60, 20],
    })

    const bounds = getFocusBounds(document.querySelector('[data-rect="target"]')!)

    // The spotlight hugs "Filter", not the 800px the hidden sentence would have occupied.
    expect(bounds.right).toBe(168)
  })

  it('falls back to the element box when it holds no measurable content', () => {
    document.body.innerHTML = '<div data-rect="empty"></div>'
    stubGeometry({ empty: [50, 60, 300, 120] })

    const bounds = getFocusBounds(document.querySelector('[data-rect="empty"]')!)

    expect(bounds.left).toBe(42)
    expect(bounds.right).toBe(358)
    expect(bounds.top).toBe(52)
    expect(bounds.bottom).toBe(188)
  })
})
