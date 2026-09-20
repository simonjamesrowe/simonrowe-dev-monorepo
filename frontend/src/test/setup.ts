import '@testing-library/jest-dom'

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
