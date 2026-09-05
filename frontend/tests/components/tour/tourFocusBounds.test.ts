import { afterEach, describe, expect, it, vi } from 'vitest'

import { getFocusBounds } from '../../../src/components/tour/tourFocusBounds'

/**
 * jsdom gives every element a zero-sized rect, so each test stubs the geometry it needs.
 * `rects` maps a marker attribute to the box that element should report.
 */
function stubGeometry(rects: Record<string, [number, number, number, number]>) {
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: Element,
  ) {
    const key = this.getAttribute('data-rect')
    const [left, top, width, height] = key && rects[key] ? rects[key] : [0, 0, 0, 0]
    return {
      left, top, width, height, right: left + width, bottom: top + height,
      x: left, y: top, toJSON: () => ({}),
    } as DOMRect
  })
}

describe('getFocusBounds', () => {
  afterEach(() => {
    vi.restoreAllMocks()
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
