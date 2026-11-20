import '@testing-library/jest-dom'
import { vi } from 'vitest'

function createMemoryStorage(): Storage {
  const values = new Map<string, string>()

  return {
    get length() { return values.size },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => { values.delete(key) },
    setItem: (key, value) => { values.set(key, value) },
  }
}

for (const name of ['localStorage', 'sessionStorage'] as const) {
  const storage = createMemoryStorage()
  Object.defineProperty(globalThis, name, { configurable: true, value: storage })
  Object.defineProperty(window, name, { configurable: true, value: storage })
}

HTMLElement.prototype.setPointerCapture ??= () => {}
HTMLElement.prototype.releasePointerCapture ??= () => {}

const browserGetComputedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = ((element: Element, pseudoElement?: string | null) => {
  const styles = browserGetComputedStyle(element, pseudoElement)
  return new Proxy(styles, {
    get(target, property, receiver) {
      if (property === 'transform' || property === 'webkitTransform'
          || property === 'mozTransform') {
        return Reflect.get(target, property, receiver) || 'none'
      }
      return Reflect.get(target, property, receiver)
    },
  })
}) as typeof window.getComputedStyle

// The news page follows summary state over a STOMP socket. The test environment's
// VITE_API_BASE_URL is production, so an unmocked socket would connect to the live site from
// every test that renders that page. Tests of the socket itself `vi.unmock` this.
vi.mock('../services/summaryStatusSocket', () => ({
  SUMMARY_TOPIC: '/topic/summaries',
  parseUpdate: () => null,
  subscribeToSummaryStatus: vi.fn(() => () => {}),
}))
